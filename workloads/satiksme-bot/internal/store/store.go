package store

import (
	"context"
	"time"

	"satiksmebot/internal/domain"
)

type CleanupResult struct {
	StopSightingsDeleted    int64
	VehicleSightingsDeleted int64
}

type ReportDumpItem struct {
	ID            string
	Payload       string
	Attempts      int
	CreatedAt     time.Time
	NextAttemptAt time.Time
	LastAttemptAt time.Time
	LastError     string
}

type Store interface {
	Migrate(ctx context.Context) error
	HealthCheck(ctx context.Context) error
	InsertStopSighting(ctx context.Context, sighting domain.StopSighting) error
	GetLastStopSightingByUserScope(ctx context.Context, userID int64, stopID string) (*domain.StopSighting, error)
	ListStopSightingsSince(ctx context.Context, since time.Time, stopID string, limit int) ([]domain.StopSighting, error)
	InsertVehicleSighting(ctx context.Context, sighting domain.VehicleSighting) error
	GetLastVehicleSightingByUserScope(ctx context.Context, userID int64, scopeKey string) (*domain.VehicleSighting, error)
	ListVehicleSightingsSince(ctx context.Context, since time.Time, stopID string, limit int) ([]domain.VehicleSighting, error)
	EnqueueReportDump(ctx context.Context, item ReportDumpItem) error
	NextReportDump(ctx context.Context, now time.Time) (*ReportDumpItem, error)
	DeleteReportDump(ctx context.Context, id string) error
	UpdateReportDumpFailure(ctx context.Context, id string, attempts int, nextAttemptAt, lastAttemptAt time.Time, lastError string) error
	PendingReportDumpCount(ctx context.Context) (int, error)
	CleanupExpired(ctx context.Context, cutoff time.Time) (CleanupResult, error)
}
