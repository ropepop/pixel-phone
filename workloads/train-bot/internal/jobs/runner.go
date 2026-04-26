package jobs

import (
	"context"
	"log"
	"time"

	"telegramtrainapp/internal/schedule"
	"telegramtrainapp/internal/scrape"
	"telegramtrainapp/internal/store"
)

const (
	dailyRetryInitialBackoff = 15 * time.Minute
	dailyRetryMaxBackoff     = 60 * time.Minute
)

type Runner struct {
	store                      store.Store
	schedules                  *schedule.Manager
	retention                  time.Duration
	loc                        *time.Location
	scraper                    *scrape.Orchestrator
	scraperDailyHour           int
	runtimeSnapshotGCEnabled   bool
	lastDailyScrapeAttemptDate string
	nextDailyRetryAt           time.Time
	dailyRetryBackoff          time.Duration
	lastFreshnessWarn          string
}

func NewRunner(
	st store.Store,
	schedules *schedule.Manager,
	retention time.Duration,
	loc *time.Location,
	scraperJob *scrape.Orchestrator,
	scraperDailyHour int,
	runtimeSnapshotGCEnabled bool,
) *Runner {
	if scraperDailyHour < 0 || scraperDailyHour > 23 {
		scraperDailyHour = 3
	}
	return &Runner{
		store:                    st,
		schedules:                schedules,
		retention:                retention,
		loc:                      loc,
		scraper:                  scraperJob,
		scraperDailyHour:         scraperDailyHour,
		runtimeSnapshotGCEnabled: runtimeSnapshotGCEnabled,
	}
}

func (r *Runner) Start(ctx context.Context) {
	now := time.Now()
	date := now.In(r.loc).Format("2006-01-02")
	if r.scraper != nil {
		r.runScrape(ctx, now, "scrape_startup")
	}
	if err := r.schedules.LoadForAccess(ctx, now); err != nil {
		log.Printf("schedule startup load failed: %v", err)
		_ = r.store.UpsertDailyMetric(ctx, date, "schedule_startup_load_success", 0)
	} else {
		_ = r.store.UpsertDailyMetric(ctx, date, "schedule_startup_load_success", 1)
	}
	go r.loop(ctx)
}

func (r *Runner) loop(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			r.tick(ctx, now)
		}
	}
}

func (r *Runner) tick(ctx context.Context, now time.Time) {
	res, err := r.store.CleanupExpired(ctx, now, r.retention, r.loc)
	if err != nil {
		log.Printf("cleanup failed: %v", err)
	} else {
		date := now.In(r.loc).Format("2006-01-02")
		_ = r.store.UpsertDailyMetric(ctx, date, "cleanup_checkins_deleted", res.CheckinsDeleted)
		_ = r.store.UpsertDailyMetric(ctx, date, "cleanup_subscriptions_deleted", res.SubscriptionsDeleted)
		_ = r.store.UpsertDailyMetric(ctx, date, "cleanup_reports_deleted", res.ReportsDeleted)
		_ = r.store.UpsertDailyMetric(ctx, date, "cleanup_train_stops_deleted", res.TrainStopsDeleted)
		_ = r.store.UpsertDailyMetric(ctx, date, "cleanup_trains_deleted", res.TrainsDeleted)
	}

	r.ensureDailyScheduleFreshness(ctx, now)

	localNow := now.In(r.loc)
	date := localNow.Format("2006-01-02")
	if localNow.Hour() == 4 && localNow.Minute() == 45 {
		if r.lastFreshnessWarn != date {
			r.lastFreshnessWarn = date
			if !r.schedules.IsFreshFor(now) {
				_ = r.store.UpsertDailyMetric(ctx, date, "schedule_freshness_warning", 1)
			} else {
				_ = r.store.UpsertDailyMetric(ctx, date, "schedule_freshness_warning", 0)
			}
		}
	}
}

func (r *Runner) ensureDailyScheduleFreshness(ctx context.Context, now time.Time) {
	if r.scraper == nil {
		return
	}
	if r.schedules.IsFreshFor(now) {
		r.resetDailyRetryState()
		return
	}

	localNow := now.In(r.loc)
	serviceDate := localNow.Format("2006-01-02")
	dailyCutoff := time.Date(localNow.Year(), localNow.Month(), localNow.Day(), r.scraperDailyHour, 0, 0, 0, r.loc)
	if localNow.Before(dailyCutoff) {
		return
	}

	if serviceDate != r.lastDailyScrapeAttemptDate {
		r.lastDailyScrapeAttemptDate = serviceDate
		if r.runScrape(ctx, now, "scrape_daily_catchup") {
			r.resetDailyRetryState()
		} else {
			r.scheduleNextDailyRetry(now)
		}
		return
	}

	if r.nextDailyRetryAt.IsZero() || now.Before(r.nextDailyRetryAt) {
		return
	}

	if r.runScrape(ctx, now, "scrape_daily_catchup") {
		r.resetDailyRetryState()
		return
	}
	r.scheduleNextDailyRetry(now)
}

func (r *Runner) resetDailyRetryState() {
	r.lastDailyScrapeAttemptDate = ""
	r.nextDailyRetryAt = time.Time{}
	r.dailyRetryBackoff = 0
}

func (r *Runner) scheduleNextDailyRetry(now time.Time) {
	if r.dailyRetryBackoff <= 0 {
		r.dailyRetryBackoff = dailyRetryInitialBackoff
	} else {
		r.dailyRetryBackoff *= 2
		if r.dailyRetryBackoff > dailyRetryMaxBackoff {
			r.dailyRetryBackoff = dailyRetryMaxBackoff
		}
	}
	r.nextDailyRetryAt = now.Add(r.dailyRetryBackoff)
}

func (r *Runner) runScrape(ctx context.Context, now time.Time, metricPrefix string) bool {
	if r.scraper == nil {
		return false
	}
	localNow := now.In(r.loc)
	date := localNow.Format("2006-01-02")
	serviceDate := time.Date(localNow.Year(), localNow.Month(), localNow.Day(), 0, 0, 0, 0, r.loc)

	result, err := r.scraper.Run(ctx, serviceDate)
	if err != nil {
		log.Printf("%s scrape failed: %v", metricPrefix, err)
		_ = r.store.UpsertDailyMetric(ctx, date, metricPrefix+"_success", 0)
		_ = r.store.UpsertDailyMetric(ctx, date, "scrape_warning", 1)
		return false
	}

	_ = r.store.UpsertDailyMetric(ctx, date, metricPrefix+"_success", 1)
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_warning", 0)
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_providers_tried", int64(result.Stats.ProvidersTried))
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_providers_succeeded", int64(result.Stats.ProvidersSucceeded))
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_trains_merged", int64(result.Stats.TrainsMerged))
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_trains_dropped", int64(result.Stats.TrainsDropped))
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_conflicts_resolved", int64(result.Stats.ConflictsResolved))
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_stops_filled_from_secondary", int64(result.Stats.StopsFilledFromB))
	_ = r.store.UpsertDailyMetric(ctx, date, "scrape_last_success_unix", now.UTC().Unix())

	if err := r.schedules.LoadToday(ctx, now); err != nil {
		log.Printf("%s schedule reload failed after scrape (%s): %v", metricPrefix, result.OutputPath, err)
		_ = r.store.UpsertDailyMetric(ctx, date, "scrape_warning", 1)
		return false
	} else {
		log.Printf("%s scrape succeeded (%s)", metricPrefix, result.OutputPath)
	}
	if err := r.cleanupPreviousServiceDate(ctx, localNow); err != nil {
		log.Printf("%s cleanup previous service date failed: %v", metricPrefix, err)
	}
	return true
}

func (r *Runner) cleanupPreviousServiceDate(ctx context.Context, localNow time.Time) error {
	dailyCutoff := time.Date(localNow.Year(), localNow.Month(), localNow.Day(), r.scraperDailyHour, 0, 0, 0, r.loc)
	if localNow.Before(dailyCutoff) {
		return nil
	}
	serviceDate := localNow.AddDate(0, 0, -1).Format("2006-01-02")
	if serviceDate == "" {
		return nil
	}
	if _, err := r.store.DeleteTrainDataByServiceDate(ctx, serviceDate); err != nil {
		return err
	}
	if !r.runtimeSnapshotGCEnabled {
		return nil
	}
	return r.schedules.DeleteSnapshot(serviceDate)
}
