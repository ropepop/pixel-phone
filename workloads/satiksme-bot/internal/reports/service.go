package reports

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"satiksmebot/internal/domain"
	"satiksmebot/internal/store"
)

type Service struct {
	store      store.Store
	cooldown   time.Duration
	dedupe     time.Duration
	visibility time.Duration
}

func NewService(st store.Store, cooldown, dedupe, visibility time.Duration) *Service {
	return &Service{
		store:      st,
		cooldown:   cooldown,
		dedupe:     dedupe,
		visibility: visibility,
	}
}

func (s *Service) HealthCheck(ctx context.Context) error {
	if s == nil || s.store == nil {
		return fmt.Errorf("reports store unavailable")
	}
	return s.store.HealthCheck(ctx)
}

func (s *Service) SubmitStopSighting(ctx context.Context, userID int64, stopID string, now time.Time) (domain.ReportResult, *domain.StopSighting, error) {
	stopID = strings.TrimSpace(stopID)
	last, err := s.store.GetLastStopSightingByUserScope(ctx, userID, stopID)
	if err != nil {
		return domain.ReportResult{}, nil, err
	}
	if last != nil {
		delta := now.Sub(last.CreatedAt)
		if delta < s.dedupe {
			return domain.ReportResult{Deduped: true}, nil, nil
		}
		if delta < s.cooldown {
			remaining := s.cooldown - delta
			return domain.ReportResult{CooldownRemaining: remaining, CooldownSeconds: int(remaining.Seconds())}, nil, nil
		}
	}

	item := &domain.StopSighting{
		ID:        generateID(),
		StopID:    stopID,
		UserID:    userID,
		CreatedAt: now.UTC(),
	}
	if err := s.store.InsertStopSighting(ctx, *item); err != nil {
		return domain.ReportResult{}, nil, err
	}
	return domain.ReportResult{Accepted: true}, item, nil
}

func (s *Service) SubmitVehicleSighting(ctx context.Context, userID int64, input domain.VehicleReportInput, now time.Time) (domain.ReportResult, *domain.VehicleSighting, error) {
	scopeKey := VehicleScopeKey(input)
	last, err := s.store.GetLastVehicleSightingByUserScope(ctx, userID, scopeKey)
	if err != nil {
		return domain.ReportResult{}, nil, err
	}
	if last != nil {
		delta := now.Sub(last.CreatedAt)
		if delta < s.dedupe {
			return domain.ReportResult{Deduped: true}, nil, nil
		}
		if delta < s.cooldown {
			remaining := s.cooldown - delta
			return domain.ReportResult{CooldownRemaining: remaining, CooldownSeconds: int(remaining.Seconds())}, nil, nil
		}
	}

	item := &domain.VehicleSighting{
		ID:               generateID(),
		StopID:           strings.TrimSpace(input.StopID),
		UserID:           userID,
		Mode:             strings.TrimSpace(input.Mode),
		RouteLabel:       strings.TrimSpace(input.RouteLabel),
		Direction:        strings.TrimSpace(input.Direction),
		Destination:      strings.TrimSpace(input.Destination),
		DepartureSeconds: input.DepartureSeconds,
		LiveRowID:        strings.TrimSpace(input.LiveRowID),
		ScopeKey:         scopeKey,
		CreatedAt:        now.UTC(),
	}
	if err := s.store.InsertVehicleSighting(ctx, *item); err != nil {
		return domain.ReportResult{}, nil, err
	}
	return domain.ReportResult{Accepted: true}, item, nil
}

func (s *Service) VisibleSightings(ctx context.Context, catalog *domain.Catalog, stopID string, now time.Time, limit int) (domain.VisibleSightings, error) {
	since := now.Add(-s.visibility)
	stops, err := s.store.ListStopSightingsSince(ctx, since, stopID, limit)
	if err != nil {
		return domain.VisibleSightings{}, err
	}
	vehicles, err := s.store.ListVehicleSightingsSince(ctx, since, stopID, limit)
	if err != nil {
		return domain.VisibleSightings{}, err
	}
	stopNames := domain.StopNameLookup(catalog)
	out := domain.VisibleSightings{
		StopSightings:    make([]domain.PublicStopSighting, 0, len(stops)),
		VehicleSightings: make([]domain.PublicVehicleSighting, 0, len(vehicles)),
	}
	for _, item := range stops {
		out.StopSightings = append(out.StopSightings, domain.PublicStopSighting{
			ID:        item.ID,
			StopID:    item.StopID,
			StopName:  stopNames[item.StopID],
			CreatedAt: item.CreatedAt,
		})
	}
	for _, item := range vehicles {
		out.VehicleSightings = append(out.VehicleSightings, domain.PublicVehicleSighting{
			ID:               item.ID,
			StopID:           item.StopID,
			StopName:         stopNames[item.StopID],
			Mode:             item.Mode,
			RouteLabel:       item.RouteLabel,
			Direction:        item.Direction,
			Destination:      item.Destination,
			DepartureSeconds: item.DepartureSeconds,
			LiveRowID:        item.LiveRowID,
			CreatedAt:        item.CreatedAt,
		})
	}
	return out, nil
}

func VehicleScopeKey(input domain.VehicleReportInput) string {
	stopID := strings.TrimSpace(input.StopID)
	mode := strings.ToLower(strings.TrimSpace(input.Mode))
	routeLabel := strings.TrimSpace(input.RouteLabel)
	direction := strings.TrimSpace(input.Direction)
	destination := strings.ToLower(strings.TrimSpace(input.Destination))
	if liveRowID := strings.TrimSpace(input.LiveRowID); liveRowID != "" {
		return fmt.Sprintf("live:%s:%s:%s:%s:%s", stopID, mode, routeLabel, direction, liveRowID)
	}
	return fmt.Sprintf("fallback:%s:%s:%s:%s:%s", stopID, mode, routeLabel, direction, destination)
}

func generateID() string {
	buf := make([]byte, 12)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("evt-%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}
