package reports

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"satiksmebot/internal/domain"
	"satiksmebot/internal/store"
)

func TestSubmitVehicleSightingUsesFallbackScopeWithoutLiveID(t *testing.T) {
	ctx := context.Background()
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	svc := NewService(st, 3*time.Minute, 90*time.Second, 30*time.Minute)
	now := time.Date(2026, 3, 10, 14, 0, 0, 0, time.UTC)
	input := domain.VehicleReportInput{
		StopID:           "3012",
		Mode:             "tram",
		RouteLabel:       "1",
		Direction:        "b-a",
		Destination:      "Imanta",
		DepartureSeconds: 68420,
	}

	result, item, err := svc.SubmitVehicleSighting(ctx, 5, input, now)
	if err != nil {
		t.Fatalf("SubmitVehicleSighting() error = %v", err)
	}
	if !result.Accepted || item == nil {
		t.Fatalf("expected accepted report, got %+v item=%v", result, item)
	}
	if want := "fallback:3012:tram:1:b-a:imanta"; item.ScopeKey != want {
		t.Fatalf("ScopeKey = %q, want %q", item.ScopeKey, want)
	}
}

func TestSubmitStopSightingAppliesDedupeAndCooldown(t *testing.T) {
	ctx := context.Background()
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	svc := NewService(st, 3*time.Minute, 90*time.Second, 30*time.Minute)
	now := time.Date(2026, 3, 10, 14, 0, 0, 0, time.UTC)

	result, _, err := svc.SubmitStopSighting(ctx, 7, "3012", now)
	if err != nil || !result.Accepted {
		t.Fatalf("first SubmitStopSighting() = %+v, err=%v", result, err)
	}
	result, _, err = svc.SubmitStopSighting(ctx, 7, "3012", now.Add(30*time.Second))
	if err != nil || !result.Deduped {
		t.Fatalf("dedupe SubmitStopSighting() = %+v, err=%v", result, err)
	}
	result, _, err = svc.SubmitStopSighting(ctx, 7, "3012", now.Add(2*time.Minute))
	if err != nil || result.CooldownSeconds == 0 || result.Accepted {
		t.Fatalf("cooldown SubmitStopSighting() = %+v, err=%v", result, err)
	}
}

func TestVisibleSightingsResolvesVehicleStopNameThroughAlias(t *testing.T) {
	ctx := context.Background()
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	svc := NewService(st, 3*time.Minute, 90*time.Second, 30*time.Minute)
	now := time.Date(2026, 3, 10, 14, 0, 0, 0, time.UTC)
	_, _, err = svc.SubmitVehicleSighting(ctx, 11, domain.VehicleReportInput{
		StopID:           "432",
		Mode:             "bus",
		RouteLabel:       "15",
		Direction:        "a-b",
		Destination:      "Purvciems",
		DepartureSeconds: 46800,
	}, now)
	if err != nil {
		t.Fatalf("SubmitVehicleSighting() error = %v", err)
	}

	visible, err := svc.VisibleSightings(ctx, &domain.Catalog{
		Stops: []domain.Stop{{ID: "0432", Name: "Slavu iela"}},
	}, "", now.Add(time.Minute), 20)
	if err != nil {
		t.Fatalf("VisibleSightings() error = %v", err)
	}
	if len(visible.VehicleSightings) != 1 {
		t.Fatalf("len(visible.VehicleSightings) = %d, want 1", len(visible.VehicleSightings))
	}
	if visible.VehicleSightings[0].StopName != "Slavu iela" {
		t.Fatalf("visible.VehicleSightings[0].StopName = %q, want Slavu iela", visible.VehicleSightings[0].StopName)
	}
}
