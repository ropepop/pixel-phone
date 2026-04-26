package store

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"path"
	"sort"
	"strings"
	"time"

	"satiksmebot/internal/domain"

	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrationFS embed.FS

type SQLiteStore struct {
	db *sql.DB
}

func NewSQLiteStore(path string) (*SQLiteStore, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	db.SetMaxOpenConns(1)
	return &SQLiteStore{db: db}, nil
}

func (s *SQLiteStore) Close() error {
	return s.db.Close()
}

func (s *SQLiteStore) HealthCheck(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	return tx.Rollback()
}

func (s *SQLiteStore) Migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version TEXT PRIMARY KEY,
			applied_at TEXT NOT NULL
		)
	`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	entries, err := migrationFS.ReadDir("migrations")
	if err != nil {
		return fmt.Errorf("list migrations: %w", err)
	}
	versions := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		versions = append(versions, entry.Name())
	}
	sort.Strings(versions)

	for _, version := range versions {
		var exists int
		if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM schema_migrations WHERE version = ?`, version).Scan(&exists); err != nil {
			return fmt.Errorf("check migration %s: %w", version, err)
		}
		if exists > 0 {
			continue
		}
		sqlBytes, err := migrationFS.ReadFile(path.Join("migrations", version))
		if err != nil {
			return fmt.Errorf("read migration %s: %w", version, err)
		}
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return fmt.Errorf("begin migration tx %s: %w", version, err)
		}
		if _, err := tx.ExecContext(ctx, string(sqlBytes)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("exec migration %s: %w", version, err)
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)`, version, time.Now().UTC().Format(time.RFC3339)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("mark migration %s: %w", version, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %s: %w", version, err)
		}
	}
	return nil
}

func (s *SQLiteStore) InsertStopSighting(ctx context.Context, sighting domain.StopSighting) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO stop_sightings(id, stop_id, user_id, created_at)
		VALUES (?, ?, ?, ?)
	`, sighting.ID, sighting.StopID, sighting.UserID, sighting.CreatedAt.UTC().Format(time.RFC3339))
	return err
}

func (s *SQLiteStore) GetLastStopSightingByUserScope(ctx context.Context, userID int64, stopID string) (*domain.StopSighting, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, stop_id, user_id, created_at
		FROM stop_sightings
		WHERE user_id = ? AND stop_id = ?
		ORDER BY created_at DESC
		LIMIT 1
	`, userID, stopID)
	var (
		item domain.StopSighting
		at   string
	)
	if err := row.Scan(&item.ID, &item.StopID, &item.UserID, &at); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	parsedAt, err := time.Parse(time.RFC3339, at)
	if err != nil {
		return nil, err
	}
	item.CreatedAt = parsedAt
	return &item, nil
}

func (s *SQLiteStore) ListStopSightingsSince(ctx context.Context, since time.Time, stopID string, limit int) ([]domain.StopSighting, error) {
	if limit <= 0 {
		limit = 200
	}
	query := `
		SELECT id, stop_id, user_id, created_at
		FROM stop_sightings
		WHERE created_at >= ?
	`
	args := []any{since.UTC().Format(time.RFC3339)}
	if strings.TrimSpace(stopID) != "" {
		query += ` AND stop_id = ?`
		args = append(args, stopID)
	}
	query += ` ORDER BY created_at DESC LIMIT ?`
	args = append(args, limit)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]domain.StopSighting, 0)
	for rows.Next() {
		var (
			item domain.StopSighting
			at   string
		)
		if err := rows.Scan(&item.ID, &item.StopID, &item.UserID, &at); err != nil {
			return nil, err
		}
		parsedAt, err := time.Parse(time.RFC3339, at)
		if err != nil {
			return nil, err
		}
		item.CreatedAt = parsedAt
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *SQLiteStore) InsertVehicleSighting(ctx context.Context, sighting domain.VehicleSighting) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO vehicle_sightings(
			id, stop_id, user_id, mode, route_label, direction, destination,
			departure_seconds, live_row_id, scope_key, created_at
		)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, sighting.ID, sighting.StopID, sighting.UserID, sighting.Mode, sighting.RouteLabel, sighting.Direction, sighting.Destination, sighting.DepartureSeconds, sighting.LiveRowID, sighting.ScopeKey, sighting.CreatedAt.UTC().Format(time.RFC3339))
	return err
}

func (s *SQLiteStore) GetLastVehicleSightingByUserScope(ctx context.Context, userID int64, scopeKey string) (*domain.VehicleSighting, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, stop_id, user_id, mode, route_label, direction, destination,
		       departure_seconds, live_row_id, scope_key, created_at
		FROM vehicle_sightings
		WHERE user_id = ? AND scope_key = ?
		ORDER BY created_at DESC
		LIMIT 1
	`, userID, scopeKey)
	var (
		item domain.VehicleSighting
		at   string
	)
	if err := row.Scan(&item.ID, &item.StopID, &item.UserID, &item.Mode, &item.RouteLabel, &item.Direction, &item.Destination, &item.DepartureSeconds, &item.LiveRowID, &item.ScopeKey, &at); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	parsedAt, err := time.Parse(time.RFC3339, at)
	if err != nil {
		return nil, err
	}
	item.CreatedAt = parsedAt
	return &item, nil
}

func (s *SQLiteStore) ListVehicleSightingsSince(ctx context.Context, since time.Time, stopID string, limit int) ([]domain.VehicleSighting, error) {
	if limit <= 0 {
		limit = 200
	}
	query := `
		SELECT id, stop_id, user_id, mode, route_label, direction, destination,
		       departure_seconds, live_row_id, scope_key, created_at
		FROM vehicle_sightings
		WHERE created_at >= ?
	`
	args := []any{since.UTC().Format(time.RFC3339)}
	if strings.TrimSpace(stopID) != "" {
		query += ` AND stop_id = ?`
		args = append(args, stopID)
	}
	query += ` ORDER BY created_at DESC LIMIT ?`
	args = append(args, limit)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]domain.VehicleSighting, 0)
	for rows.Next() {
		var (
			item domain.VehicleSighting
			at   string
		)
		if err := rows.Scan(&item.ID, &item.StopID, &item.UserID, &item.Mode, &item.RouteLabel, &item.Direction, &item.Destination, &item.DepartureSeconds, &item.LiveRowID, &item.ScopeKey, &at); err != nil {
			return nil, err
		}
		parsedAt, err := time.Parse(time.RFC3339, at)
		if err != nil {
			return nil, err
		}
		item.CreatedAt = parsedAt
		out = append(out, item)
	}
	return out, rows.Err()
}

func (s *SQLiteStore) CleanupExpired(ctx context.Context, cutoff time.Time) (CleanupResult, error) {
	result := CleanupResult{}
	stopRes, err := s.db.ExecContext(ctx, `DELETE FROM stop_sightings WHERE created_at < ?`, cutoff.UTC().Format(time.RFC3339))
	if err != nil {
		return result, err
	}
	vehicleRes, err := s.db.ExecContext(ctx, `DELETE FROM vehicle_sightings WHERE created_at < ?`, cutoff.UTC().Format(time.RFC3339))
	if err != nil {
		return result, err
	}
	result.StopSightingsDeleted, _ = stopRes.RowsAffected()
	result.VehicleSightingsDeleted, _ = vehicleRes.RowsAffected()
	return result, nil
}

func (s *SQLiteStore) EnqueueReportDump(ctx context.Context, item ReportDumpItem) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO report_dump_queue(id, payload, attempts, created_at, next_attempt_at, last_attempt_at, last_error)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, item.ID, item.Payload, item.Attempts, item.CreatedAt.UTC().Format(time.RFC3339), item.NextAttemptAt.UTC().Format(time.RFC3339), formatOptionalTime(item.LastAttemptAt), item.LastError)
	return err
}

func (s *SQLiteStore) NextReportDump(ctx context.Context, now time.Time) (*ReportDumpItem, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, payload, attempts, created_at, next_attempt_at, last_attempt_at, last_error
		FROM report_dump_queue
		WHERE next_attempt_at <= ?
		ORDER BY next_attempt_at ASC, created_at ASC
		LIMIT 1
	`, now.UTC().Format(time.RFC3339))
	var (
		item           ReportDumpItem
		createdAtRaw   string
		nextAttemptRaw string
		lastAttemptRaw string
	)
	if err := row.Scan(&item.ID, &item.Payload, &item.Attempts, &createdAtRaw, &nextAttemptRaw, &lastAttemptRaw, &item.LastError); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	var err error
	item.CreatedAt, err = time.Parse(time.RFC3339, createdAtRaw)
	if err != nil {
		return nil, err
	}
	item.NextAttemptAt, err = time.Parse(time.RFC3339, nextAttemptRaw)
	if err != nil {
		return nil, err
	}
	item.LastAttemptAt, err = parseOptionalTime(lastAttemptRaw)
	if err != nil {
		return nil, err
	}
	return &item, nil
}

func (s *SQLiteStore) DeleteReportDump(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM report_dump_queue WHERE id = ?`, id)
	return err
}

func (s *SQLiteStore) UpdateReportDumpFailure(ctx context.Context, id string, attempts int, nextAttemptAt, lastAttemptAt time.Time, lastError string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE report_dump_queue
		SET attempts = ?, next_attempt_at = ?, last_attempt_at = ?, last_error = ?
		WHERE id = ?
	`, attempts, nextAttemptAt.UTC().Format(time.RFC3339), lastAttemptAt.UTC().Format(time.RFC3339), lastError, id)
	return err
}

func (s *SQLiteStore) PendingReportDumpCount(ctx context.Context) (int, error) {
	var count int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM report_dump_queue`).Scan(&count); err != nil {
		return 0, err
	}
	return count, nil
}

func formatOptionalTime(at time.Time) string {
	if at.IsZero() {
		return ""
	}
	return at.UTC().Format(time.RFC3339)
}

func parseOptionalTime(raw string) (time.Time, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return time.Time{}, nil
	}
	return time.Parse(time.RFC3339, raw)
}
