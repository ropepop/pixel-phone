package scrape

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type fakeProvider struct {
	name string
	raw  RawSchedule
	err  error
	fn   func(ctx context.Context, serviceDate time.Time) (RawSchedule, error)
}

func (f fakeProvider) Name() string {
	return f.name
}

func (f fakeProvider) Fetch(ctx context.Context, serviceDate time.Time) (RawSchedule, error) {
	if f.fn != nil {
		return f.fn(ctx, serviceDate)
	}
	if f.err != nil {
		return RawSchedule{}, f.err
	}
	return f.raw, nil
}

func TestOrchestratorRunSuccess(t *testing.T) {
	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.UTC)
	raw := RawSchedule{
		SourceName: "source_a",
		Trains: []RawTrain{{
			ID:          "t1",
			ServiceDate: "2026-02-26",
			FromStation: "Riga",
			ToStation:   "Jelgava",
			DepartureAt: time.Date(2026, 2, 26, 8, 0, 0, 0, time.UTC),
			ArrivalAt:   time.Date(2026, 2, 26, 9, 0, 0, 0, time.UTC),
		}},
	}
	outDir := t.TempDir()
	orch := NewOrchestrator([]Provider{fakeProvider{name: "a", raw: raw}}, outDir, 1)
	result, err := orch.Run(context.Background(), serviceDate)
	if err != nil {
		t.Fatalf("run orchestrator: %v", err)
	}
	if result.Stats.TrainsMerged != 1 {
		t.Fatalf("expected 1 merged train, got %d", result.Stats.TrainsMerged)
	}
	if _, err := os.Stat(result.OutputPath); err != nil {
		t.Fatalf("expected output file at %s: %v", result.OutputPath, err)
	}
	if filepath.Base(result.OutputPath) != "2026-02-26.json" {
		t.Fatalf("unexpected output path: %s", result.OutputPath)
	}
}

func TestOrchestratorRunMinTrainsFailure(t *testing.T) {
	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.UTC)
	raw := RawSchedule{
		SourceName: "source_a",
		Trains: []RawTrain{{
			ID:          "t1",
			ServiceDate: "2026-02-26",
			FromStation: "Riga",
			ToStation:   "Jelgava",
			DepartureAt: time.Date(2026, 2, 26, 8, 0, 0, 0, time.UTC),
			ArrivalAt:   time.Date(2026, 2, 26, 9, 0, 0, 0, time.UTC),
		}},
	}
	orch := NewOrchestrator([]Provider{fakeProvider{name: "a", raw: raw}}, t.TempDir(), 2)
	if _, err := orch.Run(context.Background(), serviceDate); err == nil {
		t.Fatalf("expected min trains failure")
	}
}

func TestOrchestratorAllProvidersFail(t *testing.T) {
	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.UTC)
	orch := NewOrchestrator([]Provider{
		fakeProvider{name: "a", err: errors.New("primary down")},
		fakeProvider{name: "b", err: errors.New("secondary down")},
	}, t.TempDir(), 1)
	if _, err := orch.Run(context.Background(), serviceDate); err == nil {
		t.Fatalf("expected all providers failure")
	}
}

func TestOrchestratorRespectsCanceledContextBeforeProviderCall(t *testing.T) {
	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.UTC)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	called := false
	orch := NewOrchestrator([]Provider{
		fakeProvider{
			name: "never-called",
			fn: func(ctx context.Context, serviceDate time.Time) (RawSchedule, error) {
				called = true
				return RawSchedule{}, nil
			},
		},
	}, t.TempDir(), 1)

	_, err := orch.Run(ctx, serviceDate)
	if err == nil {
		t.Fatalf("expected context cancellation error")
	}
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context canceled error, got %v", err)
	}
	if called {
		t.Fatalf("provider should not be called when context is already canceled")
	}
}
