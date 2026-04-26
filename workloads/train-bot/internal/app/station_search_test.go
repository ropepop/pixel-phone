package app

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"telegramtrainapp/internal/reports"
	"telegramtrainapp/internal/ride"
	"telegramtrainapp/internal/schedule"
	"telegramtrainapp/internal/store"
)

func TestStationsSearchMatchesPlainLatinForLatvianNames(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	loc, err := time.LoadLocation("Europe/Riga")
	if err != nil {
		t.Fatalf("load location: %v", err)
	}
	now := time.Date(2026, time.March, 6, 12, 0, 0, 0, loc)
	serviceDate := now.Format("2006-01-02")

	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "train-bot.db"))
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	snapshotDir := t.TempDir()
	snapshotPath := filepath.Join(snapshotDir, serviceDate+".json")
	payload, err := json.Marshal(testSnapshot{
		SourceVersion: "station-search-folding",
		Trains: []testSnapshotTrain{
			buildSnapshotTrain("train-riga-kegums", serviceDate, "Rīga", "Ķegums", now.Add(10*time.Minute)),
			buildSnapshotTrain("train-cesis-riga", serviceDate, "Cēsis", "Rīga", now.Add(40*time.Minute)),
		},
	})
	if err != nil {
		t.Fatalf("marshal snapshot: %v", err)
	}
	if err := os.WriteFile(snapshotPath, payload, 0o644); err != nil {
		t.Fatalf("write snapshot: %v", err)
	}

	manager := schedule.NewManager(st, snapshotDir, loc, 3)
	if err := manager.LoadToday(ctx, now); err != nil {
		t.Fatalf("load today: %v", err)
	}

	service := NewService(
		st,
		manager,
		ride.NewService(st),
		reports.NewService(st, 3*time.Minute, 90*time.Second),
		loc,
		true,
	)

	assertStationQueryMatches(t, service, ctx, now, "riga", "Rīga")
	assertStationQueryMatches(t, service, ctx, now, "kegums", "Ķegums")
	assertStationQueryMatches(t, service, ctx, now, "cesis", "Cēsis")
}

func assertStationQueryMatches(t *testing.T, service *Service, ctx context.Context, now time.Time, query string, wantName string) {
	t.Helper()

	stations, err := service.Stations(ctx, now, query)
	if err != nil {
		t.Fatalf("Stations(%q): %v", query, err)
	}
	for _, station := range stations {
		if station.Name == wantName {
			return
		}
	}
	t.Fatalf("Stations(%q) did not include %q: %+v", query, wantName, stations)
}
