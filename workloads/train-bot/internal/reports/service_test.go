package reports

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/store"
)

func setupStore(t *testing.T) *store.SQLiteStore {
	t.Helper()
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "reports.db"))
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	if err := st.Migrate(context.Background()); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return st
}

func seedTrain(t *testing.T, st *store.SQLiteStore, trainID string, dep, arr time.Time) {
	t.Helper()
	serviceDate := dep.Format("2006-01-02")
	err := st.UpsertTrainInstances(context.Background(), serviceDate, "test", []domain.TrainInstance{
		{
			ID:            trainID,
			ServiceDate:   serviceDate,
			FromStation:   "A",
			ToStation:     "B",
			DepartureAt:   dep,
			ArrivalAt:     arr,
			SourceVersion: "test",
		},
	})
	if err != nil {
		t.Fatalf("seed train: %v", err)
	}
}

func seedTrainStops(t *testing.T, st *store.SQLiteStore, trainID string, dep, arr time.Time) {
	t.Helper()
	serviceDate := dep.Format("2006-01-02")
	stops := map[string][]domain.TrainStop{
		trainID: {
			{TrainInstanceID: trainID, StationName: "Riga", Seq: 1, DepartureAt: &dep},
			{TrainInstanceID: trainID, StationName: "Jelgava", Seq: 2, ArrivalAt: &arr},
		},
	}
	if err := st.UpsertTrainStops(context.Background(), serviceDate, stops); err != nil {
		t.Fatalf("seed train stops: %v", err)
	}
}

func TestSubmitReportCooldownAndDedupe(t *testing.T) {
	ctx := context.Background()
	st := setupStore(t)
	defer st.Close()

	now := time.Date(2026, 2, 25, 12, 0, 0, 0, time.UTC)
	seedTrain(t, st, "train-1", now.Add(-30*time.Minute), now.Add(30*time.Minute))

	svc := NewService(st, 3*time.Minute, 90*time.Second)

	first, err := svc.SubmitReport(ctx, 1, "train-1", domain.SignalInspectionStarted, now)
	if err != nil {
		t.Fatalf("first submit err: %v", err)
	}
	if !first.Accepted {
		t.Fatalf("expected first accepted")
	}

	dedupe, err := svc.SubmitReport(ctx, 1, "train-1", domain.SignalInspectionStarted, now.Add(30*time.Second))
	if err != nil {
		t.Fatalf("dedupe submit err: %v", err)
	}
	if !dedupe.Deduped {
		t.Fatalf("expected dedupe")
	}

	cooldown, err := svc.SubmitReport(ctx, 1, "train-1", domain.SignalInspectionInCar, now.Add(1*time.Minute))
	if err != nil {
		t.Fatalf("cooldown submit err: %v", err)
	}
	if cooldown.CooldownRemaining <= 0 {
		t.Fatalf("expected cooldown remaining")
	}

	allowed, err := svc.SubmitReport(ctx, 1, "train-1", domain.SignalInspectionInCar, now.Add(4*time.Minute))
	if err != nil {
		t.Fatalf("allowed submit err: %v", err)
	}
	if !allowed.Accepted {
		t.Fatalf("expected accepted after cooldown")
	}
}

func TestBuildStatusMixedAndConfidence(t *testing.T) {
	ctx := context.Background()
	st := setupStore(t)
	defer st.Close()

	now := time.Date(2026, 2, 25, 12, 0, 0, 0, time.UTC)
	seedTrain(t, st, "train-2", now.Add(-1*time.Hour), now.Add(2*time.Hour))

	events := []domain.ReportEvent{
		{ID: "1", TrainInstanceID: "train-2", UserID: 10, Signal: domain.SignalInspectionStarted, CreatedAt: now.Add(-5 * time.Minute)},
		{ID: "2", TrainInstanceID: "train-2", UserID: 11, Signal: domain.SignalInspectionEnded, CreatedAt: now.Add(-3 * time.Minute)},
		{ID: "3", TrainInstanceID: "train-2", UserID: 12, Signal: domain.SignalInspectionInCar, CreatedAt: now.Add(-2 * time.Minute)},
	}
	for _, e := range events {
		if err := st.InsertReportEvent(ctx, e); err != nil {
			t.Fatalf("insert event %s: %v", e.ID, err)
		}
	}

	svc := NewService(st, 3*time.Minute, 90*time.Second)
	status, err := svc.BuildStatus(ctx, "train-2", now)
	if err != nil {
		t.Fatalf("build status: %v", err)
	}
	if status.State != domain.StatusMixedReports {
		t.Fatalf("expected mixed state, got %s", status.State)
	}
	if status.Confidence != domain.ConfidenceHigh {
		t.Fatalf("expected high confidence, got %s", status.Confidence)
	}
	if status.UniqueReporters != 3 {
		t.Fatalf("expected 3 unique reporters, got %d", status.UniqueReporters)
	}
}

func TestBuildStatusStaleIsLowConfidence(t *testing.T) {
	ctx := context.Background()
	st := setupStore(t)
	defer st.Close()

	now := time.Date(2026, 2, 25, 12, 0, 0, 0, time.UTC)
	seedTrain(t, st, "train-3", now.Add(-2*time.Hour), now.Add(2*time.Hour))

	events := []domain.ReportEvent{
		{ID: "a", TrainInstanceID: "train-3", UserID: 1, Signal: domain.SignalInspectionStarted, CreatedAt: now.Add(-20 * time.Minute)},
		{ID: "b", TrainInstanceID: "train-3", UserID: 2, Signal: domain.SignalInspectionInCar, CreatedAt: now.Add(-19 * time.Minute)},
		{ID: "c", TrainInstanceID: "train-3", UserID: 3, Signal: domain.SignalInspectionInCar, CreatedAt: now.Add(-18 * time.Minute)},
	}
	for _, e := range events {
		if err := st.InsertReportEvent(ctx, e); err != nil {
			t.Fatalf("insert event %s: %v", e.ID, err)
		}
	}

	svc := NewService(st, 3*time.Minute, 90*time.Second)
	status, err := svc.BuildStatus(ctx, "train-3", now)
	if err != nil {
		t.Fatalf("build status: %v", err)
	}
	if status.Confidence != domain.ConfidenceLow {
		t.Fatalf("expected low confidence for stale reports, got %s", status.Confidence)
	}
}

func TestSubmitStationSightingCooldownAndDedupe(t *testing.T) {
	ctx := context.Background()
	st := setupStore(t)
	defer st.Close()

	now := time.Date(2026, 2, 25, 12, 0, 0, 0, time.UTC)
	dep := now.Add(20 * time.Minute)
	arr := dep.Add(45 * time.Minute)
	seedTrain(t, st, "train-4", dep, arr)
	seedTrainStops(t, st, "train-4", dep, arr)

	svc := NewService(st, 3*time.Minute, 90*time.Second)
	destinationID := "jelgava"
	matchedTrainID := "train-4"

	first, err := svc.SubmitStationSighting(ctx, 1, "riga", &destinationID, &matchedTrainID, now)
	if err != nil {
		t.Fatalf("first station sighting err: %v", err)
	}
	if !first.Accepted || first.Event == nil {
		t.Fatalf("expected first station sighting accepted, got %+v", first)
	}

	deduped, err := svc.SubmitStationSighting(ctx, 1, "riga", &destinationID, &matchedTrainID, now.Add(30*time.Second))
	if err != nil {
		t.Fatalf("deduped station sighting err: %v", err)
	}
	if !deduped.Deduped {
		t.Fatalf("expected station sighting dedupe, got %+v", deduped)
	}

	cooldown, err := svc.SubmitStationSighting(ctx, 1, "riga", &destinationID, &matchedTrainID, now.Add(2*time.Minute))
	if err != nil {
		t.Fatalf("cooldown station sighting err: %v", err)
	}
	if cooldown.CooldownRemaining <= 0 {
		t.Fatalf("expected station sighting cooldown remaining, got %+v", cooldown)
	}

	allowed, err := svc.SubmitStationSighting(ctx, 1, "riga", &destinationID, &matchedTrainID, now.Add(4*time.Minute))
	if err != nil {
		t.Fatalf("allowed station sighting err: %v", err)
	}
	if !allowed.Accepted || allowed.Event == nil {
		t.Fatalf("expected station sighting accepted after cooldown, got %+v", allowed)
	}
}
