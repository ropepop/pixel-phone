package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"satiksmebot/internal/bot"
	"satiksmebot/internal/catalog"
	"satiksmebot/internal/config"
	"satiksmebot/internal/reports"
	"satiksmebot/internal/runtime"
	"satiksmebot/internal/store"
	"satiksmebot/internal/telegram"
	"satiksmebot/internal/version"
	"satiksmebot/internal/web"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	runtimeState := runtime.New(
		time.Now().UTC(),
		cfg.SatiksmeWebEnabled,
		net.JoinHostPort(cfg.SatiksmeWebBindAddr, fmt.Sprintf("%d", cfg.SatiksmeWebPort)),
	)

	lockPath := resolveSingleInstanceLockPath(cfg)
	lockFile, err := acquireSingleInstanceLock(lockPath)
	if err != nil {
		fatalf(runtimeState, "single instance lock: %v", err)
	}
	if lockFile != nil {
		defer releaseSingleInstanceLock(lockFile)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	loc, err := time.LoadLocation(cfg.Timezone)
	if err != nil {
		fatalf(runtimeState, "load timezone: %v", err)
	}

	st, err := store.NewSQLiteStore(cfg.DBPath)
	if err != nil {
		fatalf(runtimeState, "store: %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		fatalf(runtimeState, "migrate: %v", err)
	}

	httpClient := &http.Client{Timeout: time.Duration(cfg.HTTPTimeoutSec) * time.Second}
	catalogManager := catalog.NewManager(catalog.Settings{
		StopsURL:     cfg.SourceStopsURL,
		RoutesURL:    cfg.SourceRoutesURL,
		GTFSURL:      cfg.SourceGTFSURL,
		MirrorDir:    cfg.CatalogMirrorDir,
		OutputPath:   cfg.CatalogOutputPath,
		RefreshAfter: time.Duration(cfg.CatalogRefreshHours) * time.Hour,
		HTTPClient:   httpClient,
		RuntimeState: runtimeState,
	})
	if _, err := catalogManager.LoadOrRefresh(ctx, false); err != nil {
		fatalf(runtimeState, "catalog load: %v", err)
	}
	runtimeState.UpdateCatalog(catalogManager.Status())

	reportsSvc := reports.NewService(
		st,
		time.Duration(cfg.ReportCooldownMinutes)*time.Minute,
		time.Duration(cfg.ReportDedupeSeconds)*time.Second,
		time.Duration(cfg.ReportVisibilityMinutes)*time.Minute,
	)

	telegramClient := telegram.NewClient(cfg.BotToken, time.Duration(cfg.HTTPTimeoutSec)*time.Second)
	var webServer *web.Server
	var miniAppURL string
	var publicURL string
	dumpDispatcher := bot.NewDumpDispatcher(telegramClient, st, runtimeState, cfg.ReportDumpChat, time.Second, loc)

	if cfg.SatiksmeWebEnabled {
		webServer, err = web.NewServer(cfg, catalogManager, reportsSvc, dumpDispatcher, st, runtimeState, loc)
		if err != nil {
			fatalf(runtimeState, "web server: %v", err)
		}
		miniAppURL = webServer.AppURL()
		publicURL = webServer.PublicURL()
	}

	botService := bot.NewService(telegramClient, cfg.LongPollTimeout, miniAppURL, publicURL, cfg.ReportsChannelURL, runtimeState)

	runCtx, runCancel := context.WithCancel(ctx)
	defer runCancel()

	type componentResult struct {
		name string
		err  error
	}
	results := make(chan componentResult, 5)
	components := 0
	start := func(name string, fn func(context.Context) error) {
		components++
		go func() {
			results <- componentResult{name: name, err: fn(runCtx)}
		}()
	}

	start("catalog", func(ctx context.Context) error {
		return catalogRefreshLoop(ctx, catalogManager, runtimeState, time.Duration(cfg.CatalogRefreshHours)*time.Hour)
	})
	start("cleanup", func(ctx context.Context) error {
		return cleanupLoop(ctx, st, time.Duration(cfg.DataRetentionHours)*time.Hour, time.Duration(cfg.CleanupIntervalMinutes)*time.Minute)
	})
	start("telegram-bot", botService.Start)
	start("report-dump", dumpDispatcher.Run)
	if webServer != nil {
		start("web", webServer.Run)
	}

	log.Printf("satiksme bot started (%s)", version.Display())

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
		fatalf(runtimeState, "satiksme bot stopped with error: %v", firstErr)
	}
}

func fatalf(runtimeState *runtime.State, format string, args ...any) {
	message := fmt.Sprintf(format, args...)
	runtimeState.SetFatalError(message)
	log.Fatal(message)
}

func catalogRefreshLoop(ctx context.Context, manager *catalog.Manager, runtimeState *runtime.State, interval time.Duration) error {
	if interval <= 0 {
		interval = 24 * time.Hour
	}
	runtimeState.UpdateCatalog(manager.Status())

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if _, err := manager.Refresh(ctx, false); err != nil {
				log.Printf("catalog refresh failed: %v", err)
			}
			runtimeState.UpdateCatalog(manager.Status())
		}
	}
}

func cleanupLoop(ctx context.Context, st *store.SQLiteStore, retention, interval time.Duration) error {
	if interval <= 0 {
		interval = 10 * time.Minute
	}
	if retention <= 0 {
		retention = 24 * time.Hour
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			_, _ = st.CleanupExpired(ctx, time.Now().UTC().Add(-retention))
		}
	}
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
			return nil, fmt.Errorf("another satiksme-bot instance is already running")
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
