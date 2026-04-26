package store

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"satiksmebot/internal/domain"
)

func TestSQLiteStoreRoundTripAndCleanup(t *testing.T) {
	ctx := context.Background()
	st, err := NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	now := time.Date(2026, 3, 10, 15, 0, 0, 0, time.UTC)
	if err := st.InsertStopSighting(ctx, domain.StopSighting{
		ID:        "stop-1",
		StopID:    "3012",
		UserID:    11,
		CreatedAt: now,
	}); err != nil {
		t.Fatalf("InsertStopSighting() error = %v", err)
	}
	if err := st.InsertVehicleSighting(ctx, domain.VehicleSighting{
		ID:               "veh-1",
		StopID:           "3012",
		UserID:           11,
		Mode:             "bus",
		RouteLabel:       "22",
		Direction:        "a-b",
		Destination:      "Lidosta",
		DepartureSeconds: 68542,
		LiveRowID:        "78648",
		ScopeKey:         "live:3012:78648",
		CreatedAt:        now,
	}); err != nil {
		t.Fatalf("InsertVehicleSighting() error = %v", err)
	}

	stopItems, err := st.ListStopSightingsSince(ctx, now.Add(-time.Hour), "", 10)
	if err != nil {
		t.Fatalf("ListStopSightingsSince() error = %v", err)
	}
	if len(stopItems) != 1 {
		t.Fatalf("len(stopItems) = %d, want 1", len(stopItems))
	}
	vehicleItems, err := st.ListVehicleSightingsSince(ctx, now.Add(-time.Hour), "3012", 10)
	if err != nil {
		t.Fatalf("ListVehicleSightingsSince() error = %v", err)
	}
	if len(vehicleItems) != 1 {
		t.Fatalf("len(vehicleItems) = %d, want 1", len(vehicleItems))
	}

	result, err := st.CleanupExpired(ctx, now.Add(time.Minute))
	if err != nil {
		t.Fatalf("CleanupExpired() error = %v", err)
	}
	if result.StopSightingsDeleted != 1 || result.VehicleSightingsDeleted != 1 {
		t.Fatalf("CleanupExpired() = %+v", result)
	}
}

func TestSQLiteStoreReportDumpQueueLifecycle(t *testing.T) {
	ctx := context.Background()
	st, err := NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	now := time.Date(2026, 3, 10, 15, 0, 0, 0, time.UTC)
	item := ReportDumpItem{
		ID:            "dump-1",
		Payload:       "payload",
		CreatedAt:     now,
		NextAttemptAt: now,
	}
	if err := st.EnqueueReportDump(ctx, item); err != nil {
		t.Fatalf("EnqueueReportDump() error = %v", err)
	}

	pending, err := st.PendingReportDumpCount(ctx)
	if err != nil {
		t.Fatalf("PendingReportDumpCount() error = %v", err)
	}
	if pending != 1 {
		t.Fatalf("pending = %d, want 1", pending)
	}

	loaded, err := st.NextReportDump(ctx, now)
	if err != nil {
		t.Fatalf("NextReportDump() error = %v", err)
	}
	if loaded == nil || loaded.ID != item.ID || loaded.Payload != item.Payload {
		t.Fatalf("NextReportDump() = %+v", loaded)
	}

	lastAttemptAt := now.Add(30 * time.Second)
	nextAttemptAt := now.Add(2 * time.Minute)
	if err := st.UpdateReportDumpFailure(ctx, item.ID, 1, nextAttemptAt, lastAttemptAt, "telegram timeout"); err != nil {
		t.Fatalf("UpdateReportDumpFailure() error = %v", err)
	}

	beforeRetry, err := st.NextReportDump(ctx, now.Add(90*time.Second))
	if err != nil {
		t.Fatalf("NextReportDump(before retry) error = %v", err)
	}
	if beforeRetry != nil {
		t.Fatalf("NextReportDump(before retry) = %+v, want nil", beforeRetry)
	}

	retryItem, err := st.NextReportDump(ctx, nextAttemptAt)
	if err != nil {
		t.Fatalf("NextReportDump(retry) error = %v", err)
	}
	if retryItem == nil {
		t.Fatalf("NextReportDump(retry) = nil")
	}
	if retryItem.Attempts != 1 || retryItem.LastError != "telegram timeout" {
		t.Fatalf("retryItem = %+v", retryItem)
	}
	if !retryItem.LastAttemptAt.Equal(lastAttemptAt) || !retryItem.NextAttemptAt.Equal(nextAttemptAt) {
		t.Fatalf("retry timing = %+v", retryItem)
	}

	if err := st.DeleteReportDump(ctx, item.ID); err != nil {
		t.Fatalf("DeleteReportDump() error = %v", err)
	}
	pending, err = st.PendingReportDumpCount(ctx)
	if err != nil {
		t.Fatalf("PendingReportDumpCount(after delete) error = %v", err)
	}
	if pending != 0 {
		t.Fatalf("pending after delete = %d, want 0", pending)
	}
}
