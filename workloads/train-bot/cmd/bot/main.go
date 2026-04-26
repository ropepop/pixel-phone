package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	trainapp "telegramtrainapp/internal/app"
	"telegramtrainapp/internal/bot"
	"telegramtrainapp/internal/config"
	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
	"telegramtrainapp/internal/jobs"
	"telegramtrainapp/internal/reports"
	"telegramtrainapp/internal/ride"
	"telegramtrainapp/internal/schedule"
	"telegramtrainapp/internal/scrape"
	"telegramtrainapp/internal/store"
	"telegramtrainapp/internal/util"
	appversion "telegramtrainapp/internal/version"
	"telegramtrainapp/internal/web"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	lockPath := resolveSingleInstanceLockPath(cfg)
	lockFile, err := acquireSingleInstanceLock(lockPath)
	if err != nil {
		log.Fatalf("single instance lock: %v", err)
	}
	if lockFile != nil {
		defer releaseSingleInstanceLock(lockFile)
		log.Printf("single-instance lock acquired: %s", lockPath)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	loc := util.MustLoadLocation(cfg.Timezone)

	st, err := store.NewSQLiteStore(cfg.DBPath)
	if err != nil {
		log.Fatalf("store: %v", err)
	}
	defer st.Close()

	if err := st.Migrate(ctx); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	schedules := schedule.NewManager(st, cfg.ScheduleDir, loc, cfg.ScraperDailyHour)

	reportsSvc := reports.NewService(
		st,
		time.Duration(cfg.CooldownMin)*time.Minute,
		time.Duration(cfg.DedupeSec)*time.Second,
	)
	rides := ride.NewService(st)
	appSvc := trainapp.NewService(st, schedules, rides, reportsSvc, loc, cfg.FeatureStationCheckin)
	catalog := i18n.NewCatalog()
	client := bot.NewClient(cfg.BotToken, time.Duration(cfg.HTTPTimeoutSec)*time.Second)
	webBaseURL := strings.TrimRight(strings.TrimSpace(cfg.TrainWebPublicBaseURL), "/")
	notifier := bot.NewNotifier(client, st, catalog, loc, webBaseURL, cfg.ReportDumpChatID)

	if cfg.FeatureInspectionSignalsConfigured {
		log.Printf("FEATURE_INSPECTION_SIGNALS is deprecated and ignored (value=%v)", cfg.FeatureInspectionSignals)
	}

	var scraperJob *scrape.Orchestrator
	timeout := time.Duration(cfg.HTTPTimeoutSec) * time.Second
	if strings.TrimSpace(cfg.ScraperViviPageURL) == "" || strings.TrimSpace(cfg.ScraperViviGTFSURL) == "" {
		log.Printf("vivi scraper URLs are empty; runtime scraper disabled")
	} else {
		providers := []scrape.Provider{
			scrape.NewViviPDFProvider("vivi_pdf", cfg.ScraperViviPageURL, cfg.ScraperUserAgent, timeout),
			scrape.NewViviGTFSProvider("vivi_gtfs", cfg.ScraperViviGTFSURL, cfg.ScraperUserAgent, timeout),
		}
		scraperJob = scrape.NewOrchestrator(providers, cfg.ScraperOutputDir, cfg.ScraperMinTrains)
	}

	jobRunner := jobs.NewRunner(
		st,
		schedules,
		time.Duration(cfg.DataRetentionHrs)*time.Hour,
		loc,
		scraperJob,
		cfg.ScraperDailyHour,
		cfg.RuntimeSnapshotGCEnabled,
	)
	jobRunner.Start(ctx)

	service := bot.NewService(
		client,
		notifier,
		st,
		schedules,
		rides,
		reportsSvc,
		catalog,
		loc,
		cfg.LongPollTimeout,
		cfg.FeatureStationCheckin,
		webBaseURL,
	)

	var webServer *web.Server
	if cfg.TrainWebEnabled {
		webServer, err = web.NewServer(cfg, appSvc, catalog, loc)
		if err != nil {
			log.Fatalf("train web server: %v", err)
		}
		webServer.SetNotifier(trainWebRideNotifier{
			schedules: schedules,
			notifier:  notifier,
		})
		log.Printf("train web enabled: %s", webServer.AppURL())
	}

	runCtx, runCancel := context.WithCancel(ctx)
	defer runCancel()

	type componentResult struct {
		name string
		err  error
	}

	results := make(chan componentResult, 3)
	components := 0
	startComponent := func(name string, fn func(context.Context) error) {
		components++
		go func() {
			results <- componentResult{name: name, err: fn(runCtx)}
		}()
	}

	startComponent("bot", service.Start)
	startComponent("notifier", notifier.Run)
	if webServer != nil {
		startComponent("train web", webServer.Run)
	}

	log.Printf("train bot started (%s)", appversion.Display())

	var firstErr error
	for components > 0 {
		result := <-results
		components--
		if result.err != nil && firstErr == nil {
			firstErr = fmt.Errorf("%s: %w", result.name, result.err)
			runCancel()
		}
	}
	if firstErr != nil {
		log.Fatalf("train bot stopped with error: %v", firstErr)
	}
}

type trainWebRideNotifier struct {
	schedules *schedule.Manager
	notifier  trainAlertNotifier
}

type trainAlertNotifier interface {
	DispatchRideAlert(ctx context.Context, payload bot.RideAlertPayload, now time.Time) error
	DispatchStationSighting(ctx context.Context, event domain.StationSighting, now time.Time) error
}

func (n trainWebRideNotifier) NotifyRideUsers(ctx context.Context, reporterID int64, trainID string, signal domain.SignalType, now time.Time) error {
	if n.notifier == nil {
		return nil
	}
	payload := bot.RideAlertPayload{
		TrainID:    trainID,
		Signal:     signal,
		ReportedAt: now,
		ReporterID: reporterID,
	}
	if n.schedules != nil {
		train, err := n.schedules.GetTrain(ctx, trainID)
		if err != nil {
			return err
		}
		if train != nil {
			payload.FromStation = train.FromStation
			payload.ToStation = train.ToStation
			payload.DepartureAt = train.DepartureAt
			payload.ArrivalAt = train.ArrivalAt
		}
	}
	return n.notifier.DispatchRideAlert(ctx, payload, now)
}

func (n trainWebRideNotifier) NotifyStationSighting(ctx context.Context, event domain.StationSighting, now time.Time) error {
	if n.notifier == nil {
		return nil
	}
	return n.notifier.DispatchStationSighting(ctx, event, now)
}

func resolveSingleInstanceLockPath(cfg config.Config) string {
	if strings.TrimSpace(cfg.SingleInstanceLockPath) != "" {
		return cfg.SingleInstanceLockPath
	}
	return cfg.DBPath + ".lock"
}

func acquireSingleInstanceLock(lockPath string) (*os.File, error) {
	if strings.TrimSpace(lockPath) == "" {
		return nil, nil
	}
	dir := filepath.Dir(lockPath)
	if dir != "." && dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create lock dir: %w", err)
		}
	}
	f, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		return nil, fmt.Errorf("open lock file: %w", err)
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) || errors.Is(err, syscall.EAGAIN) {
			return nil, fmt.Errorf("another train-bot instance is already running")
		}
		return nil, fmt.Errorf("acquire lock: %w", err)
	}
	_ = f.Truncate(0)
	_, _ = f.WriteString(fmt.Sprintf("%d\n", os.Getpid()))
	return f, nil
}

func releaseSingleInstanceLock(f *os.File) {
	if f == nil {
		return
	}
	_ = syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
	_ = f.Close()
}
