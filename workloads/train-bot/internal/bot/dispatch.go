package bot

import (
	"context"
	"sort"
	"strings"
	"time"

	"telegramtrainapp/internal/domain"
)

const corridorMatchThreshold = 0.8

type corridorTrain struct {
	Train    domain.TrainInstance
	StopSet  map[string]struct{}
	StopName map[string]string
}

func (n *Notifier) DispatchRideAlert(ctx context.Context, payload RideAlertPayload, now time.Time) error {
	payload, recipients, err := n.resolveRideAlertRecipients(ctx, payload, now)
	if err != nil {
		return err
	}
	if err := n.sendRideAlertRecipients(ctx, payload, recipients, now); err != nil {
		return err
	}
	n.enqueueReportDump(n.newRideReportDumpItem(payload))
	return nil
}

func (n *Notifier) DispatchStationSighting(ctx context.Context, event domain.StationSighting, now time.Time) error {
	payload, recipients, err := n.resolveStationSightingRecipients(ctx, event, now)
	if err != nil {
		return err
	}
	if err := n.NotifyStationSightingUsers(ctx, payload, recipients, now); err != nil {
		return err
	}
	n.enqueueReportDump(n.newStationSightingDumpItem(payload, event))
	return nil
}

func (n *Notifier) resolveRideAlertRecipients(ctx context.Context, payload RideAlertPayload, now time.Time) (RideAlertPayload, []RideAlertRecipient, error) {
	if n == nil || n.store == nil {
		return payload, nil, nil
	}

	sourceTrain, _ := n.store.GetTrainInstanceByID(ctx, payload.TrainID)
	if sourceTrain != nil {
		if strings.TrimSpace(payload.FromStation) == "" {
			payload.FromStation = sourceTrain.FromStation
		}
		if strings.TrimSpace(payload.ToStation) == "" {
			payload.ToStation = sourceTrain.ToStation
		}
		if payload.DepartureAt.IsZero() {
			payload.DepartureAt = sourceTrain.DepartureAt
		}
		if payload.ArrivalAt.IsZero() {
			payload.ArrivalAt = sourceTrain.ArrivalAt
		}
	}

	corridorTrains, corridorEnabled, err := n.loadCorridorTrains(ctx, payload.TrainID, payload.DepartureAt)
	if err != nil {
		return payload, nil, err
	}

	type recipientState struct {
		priority  int
		recipient RideAlertRecipient
	}
	recipients := map[int64]recipientState{}
	addRecipient := func(priority int, recipient RideAlertRecipient) {
		if recipient.UserID == payload.ReporterID {
			return
		}
		existing, ok := recipients[recipient.UserID]
		if ok && existing.priority <= priority {
			return
		}
		recipients[recipient.UserID] = recipientState{priority: priority, recipient: recipient}
	}

	checkInUsers, err := n.store.ListActiveCheckinUsers(ctx, payload.TrainID, now)
	if err != nil {
		return payload, nil, err
	}
	for _, userID := range checkInUsers {
		addRecipient(0, RideAlertRecipient{
			UserID:             userID,
			Audience:           RideAlertAudienceExactTrain,
			ContextTrainID:     payload.TrainID,
			ContextFromStation: payload.FromStation,
			ContextToStation:   payload.ToStation,
			ContextDepartureAt: payload.DepartureAt,
		})
	}

	if corridorEnabled {
		for _, item := range corridorTrains {
			if item.Train.ID == payload.TrainID {
				continue
			}
			candidateCheckIns, err := n.store.ListActiveCheckinUsers(ctx, item.Train.ID, now)
			if err != nil {
				return payload, nil, err
			}
			for _, userID := range candidateCheckIns {
				addRecipient(2, RideAlertRecipient{
					UserID:             userID,
					Audience:           RideAlertAudienceCorridorTrain,
					ContextTrainID:     item.Train.ID,
					ContextFromStation: item.Train.FromStation,
					ContextToStation:   item.Train.ToStation,
					ContextDepartureAt: item.Train.DepartureAt,
				})
			}
		}

		favorites, err := n.store.ListAllFavoriteRoutes(ctx)
		if err != nil {
			return payload, nil, err
		}
		for _, favorite := range favorites {
			if !favoriteMatchesCorridor(favorite, corridorTrains) {
				continue
			}
			addRecipient(4, RideAlertRecipient{
				UserID:              favorite.UserID,
				Audience:            RideAlertAudienceSavedRoute,
				FavoriteFromStation: fallbackStationName(favorite.FromStationName, favorite.FromStationID),
				FavoriteToStation:   fallbackStationName(favorite.ToStationName, favorite.ToStationID),
			})
		}
	}

	out := make([]RideAlertRecipient, 0, len(recipients))
	for _, recipient := range recipients {
		out = append(out, recipient.recipient)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UserID < out[j].UserID })
	return payload, out, nil
}

func (n *Notifier) resolveStationSightingRecipients(ctx context.Context, event domain.StationSighting, now time.Time) (StationSightingAlertPayload, []StationSightingRecipient, error) {
	payload := StationSightingAlertPayload{
		StationID:              event.StationID,
		StationName:            fallbackStationName(event.StationName, event.StationID),
		DestinationStationName: event.DestinationStationName,
		ReportedAt:             event.CreatedAt,
		ReporterID:             event.UserID,
	}
	if event.MatchedTrainInstanceID != nil {
		payload.MatchedTrainID = *event.MatchedTrainInstanceID
	}

	type recipientState struct {
		priority  int
		recipient StationSightingRecipient
	}
	recipients := map[int64]recipientState{}
	addRecipient := func(priority int, recipient StationSightingRecipient) {
		if recipient.UserID == event.UserID {
			return
		}
		existing, ok := recipients[recipient.UserID]
		if ok && existing.priority <= priority {
			return
		}
		recipients[recipient.UserID] = recipientState{priority: priority, recipient: recipient}
	}

	if payload.MatchedTrainID != "" {
		matchedTrain, _ := n.store.GetTrainInstanceByID(ctx, payload.MatchedTrainID)
		if matchedTrain != nil {
			payload.MatchedFromStation = matchedTrain.FromStation
			payload.MatchedToStation = matchedTrain.ToStation
			payload.MatchedDepartureAt = matchedTrain.DepartureAt
			payload.MatchedArrivalAt = matchedTrain.ArrivalAt
			if strings.TrimSpace(payload.DestinationStationName) == "" {
				payload.DestinationStationName = matchedTrain.ToStation
			}

			checkInUsers, err := n.store.ListActiveCheckinUsers(ctx, matchedTrain.ID, now)
			if err != nil {
				return payload, nil, err
			}
			for _, userID := range checkInUsers {
				addRecipient(0, StationSightingRecipient{
					UserID:             userID,
					Audience:           StationSightingAudienceExactTrain,
					ContextTrainID:     matchedTrain.ID,
					ContextFromStation: matchedTrain.FromStation,
					ContextToStation:   matchedTrain.ToStation,
					ContextDepartureAt: matchedTrain.DepartureAt,
				})
			}
			corridorTrains, corridorEnabled, err := n.loadCorridorTrains(ctx, matchedTrain.ID, matchedTrain.DepartureAt)
			if err != nil {
				return payload, nil, err
			}
			if corridorEnabled {
				filtered := filterCorridorTrainsByStation(corridorTrains, event.StationID)
				for _, item := range filtered {
					if item.Train.ID == matchedTrain.ID {
						continue
					}
					candidateCheckIns, err := n.store.ListActiveCheckinUsers(ctx, item.Train.ID, now)
					if err != nil {
						return payload, nil, err
					}
					for _, userID := range candidateCheckIns {
						addRecipient(2, StationSightingRecipient{
							UserID:             userID,
							Audience:           StationSightingAudienceCorridorTrain,
							ContextTrainID:     item.Train.ID,
							ContextFromStation: item.Train.FromStation,
							ContextToStation:   item.Train.ToStation,
							ContextDepartureAt: item.Train.DepartureAt,
						})
					}
				}

				favorites, err := n.store.ListAllFavoriteRoutes(ctx)
				if err != nil {
					return payload, nil, err
				}
				for _, favorite := range favorites {
					if !favoriteMatchesCorridor(favorite, filtered) {
						continue
					}
					addRecipient(4, StationSightingRecipient{
						UserID:              favorite.UserID,
						Audience:            StationSightingAudienceSavedRoute,
						FavoriteFromStation: fallbackStationName(favorite.FromStationName, favorite.FromStationID),
						FavoriteToStation:   fallbackStationName(favorite.ToStationName, favorite.ToStationID),
					})
				}
			}
		}
	} else {
		nearby, err := n.loadNearbyStationTrains(ctx, event.StationID, now)
		if err != nil {
			return payload, nil, err
		}
		for _, item := range nearby {
			checkInUsers, err := n.store.ListActiveCheckinUsers(ctx, item.Train.ID, now)
			if err != nil {
				return payload, nil, err
			}
			for _, userID := range checkInUsers {
				addRecipient(0, StationSightingRecipient{
					UserID:             userID,
					Audience:           StationSightingAudienceNearbyTrain,
					ContextTrainID:     item.Train.ID,
					ContextFromStation: item.Train.FromStation,
					ContextToStation:   item.Train.ToStation,
					ContextDepartureAt: item.Train.DepartureAt,
				})
			}
		}
	}

	out := make([]StationSightingRecipient, 0, len(recipients))
	for _, recipient := range recipients {
		out = append(out, recipient.recipient)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UserID < out[j].UserID })
	return payload, out, nil
}

func (n *Notifier) sendRideAlertRecipients(ctx context.Context, payload RideAlertPayload, recipients []RideAlertRecipient, now time.Time) error {
	if n == nil || n.client == nil {
		return nil
	}
	for _, recipient := range recipients {
		if recipient.ContextTrainID != "" {
			muted, err := n.store.IsTrainMuted(ctx, recipient.UserID, recipient.ContextTrainID, now)
			if err != nil {
				continue
			}
			if muted {
				continue
			}
		}
		settings, err := n.store.GetUserSettings(ctx, recipient.UserID)
		if err != nil {
			continue
		}
		if !settings.AlertsEnabled {
			continue
		}
		text := n.catalog.T(settings.Language, "alert_discreet")
		if settings.AlertStyle == domain.AlertStyleDetailed {
			text = n.rideAlertDetailedText(settings.Language, payload, recipient, now)
		}
		if err := n.client.SendMessage(ctx, recipient.UserID, text, MessageOptions{ReplyMarkup: n.rideAlertKeyboard(settings.Language, payload.TrainID, recipient.Audience)}); err != nil {
			continue
		}
	}
	return nil
}

func (n *Notifier) rideAlertDetailedText(lang domain.Language, payload RideAlertPayload, recipient RideAlertRecipient, now time.Time) string {
	switch recipient.Audience {
	case RideAlertAudienceCorridorTrain, RideAlertAudienceCorridorSub:
		return n.catalog.T(
			lang,
			"alert_same_corridor",
			n.signalLabel(lang, payload.Signal),
			payload.FromStation,
			payload.ToStation,
			payload.DepartureAt.In(n.loc).Format("15:04"),
			recipient.ContextFromStation,
			recipient.ContextToStation,
			recipient.ContextDepartureAt.In(n.loc).Format("15:04"),
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	case RideAlertAudienceSavedRoute:
		return n.catalog.T(
			lang,
			"alert_saved_route",
			n.signalLabel(lang, payload.Signal),
			payload.FromStation,
			payload.ToStation,
			payload.DepartureAt.In(n.loc).Format("15:04"),
			recipient.FavoriteFromStation,
			recipient.FavoriteToStation,
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	default:
		return n.catalog.T(
			lang,
			"alert_detailed",
			n.signalLabel(lang, payload.Signal),
			payload.FromStation,
			payload.ToStation,
			payload.DepartureAt.In(n.loc).Format("15:04"),
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	}
}

func (n *Notifier) rideAlertKeyboard(lang domain.Language, sourceTrainID string, audience RideAlertAudience) map[string]any {
	switch audience {
	case RideAlertAudienceExactTrain, RideAlertAudienceExactSubscription:
		return n.alertKeyboard(lang, sourceTrainID)
	default:
		if n.webAppURL == "" {
			return nil
		}
		return InlineKeyboardAny(
			[]map[string]any{WebAppInlineButton(n.catalog.T(lang, "btn_open_app"), n.webAppURL+"/app")},
		)
	}
}

func (n *Notifier) loadCorridorTrains(ctx context.Context, sourceTrainID string, departureAt time.Time) ([]corridorTrain, bool, error) {
	serviceDate := departureAt.In(n.loc).Format("2006-01-02")
	if serviceDate == "0001-01-01" {
		serviceDate = ""
	}

	sourceTrain, err := n.store.GetTrainInstanceByID(ctx, sourceTrainID)
	if err != nil {
		return nil, false, err
	}
	if sourceTrain != nil && strings.TrimSpace(sourceTrain.ServiceDate) != "" {
		serviceDate = sourceTrain.ServiceDate
	}
	if strings.TrimSpace(serviceDate) == "" {
		return nil, false, nil
	}

	sourceStops, sourceNames, err := n.loadStopSet(ctx, sourceTrainID)
	if err != nil {
		return nil, false, err
	}
	if len(sourceStops) == 0 {
		return nil, false, nil
	}

	trains, err := n.store.ListTrainInstancesByDate(ctx, serviceDate)
	if err != nil {
		return nil, false, err
	}
	out := make([]corridorTrain, 0, len(trains))
	for _, item := range trains {
		stopSet, stopNames, err := n.loadStopSet(ctx, item.ID)
		if err != nil {
			return nil, false, err
		}
		if len(stopSet) == 0 {
			if item.ID == sourceTrainID {
				out = append(out, corridorTrain{Train: item, StopSet: sourceStops, StopName: sourceNames})
			}
			continue
		}
		if overlapRatio(sourceStops, stopSet) < corridorMatchThreshold {
			continue
		}
		out = append(out, corridorTrain{Train: item, StopSet: stopSet, StopName: stopNames})
	}
	return out, len(out) > 0, nil
}

func (n *Notifier) loadNearbyStationTrains(ctx context.Context, stationID string, now time.Time) ([]domain.StationWindowTrain, error) {
	serviceDate := now.In(n.loc).Format("2006-01-02")
	return n.store.ListStationWindowTrains(ctx, serviceDate, stationID, now.Add(-30*time.Minute), now.Add(30*time.Minute))
}

func (n *Notifier) loadStopSet(ctx context.Context, trainID string) (map[string]struct{}, map[string]string, error) {
	stops, err := n.store.ListTrainStops(ctx, trainID)
	if err != nil {
		return nil, nil, err
	}
	stopSet := make(map[string]struct{}, len(stops))
	stopNames := make(map[string]string, len(stops))
	for _, stop := range stops {
		stationID := strings.TrimSpace(stop.StationID)
		if stationID == "" {
			continue
		}
		stopSet[stationID] = struct{}{}
		if strings.TrimSpace(stop.StationName) != "" {
			stopNames[stationID] = stop.StationName
		}
	}
	return stopSet, stopNames, nil
}

func filterCorridorTrainsByStation(items []corridorTrain, stationID string) []corridorTrain {
	if strings.TrimSpace(stationID) == "" {
		return items
	}
	filtered := make([]corridorTrain, 0, len(items))
	for _, item := range items {
		if _, ok := item.StopSet[stationID]; !ok {
			continue
		}
		filtered = append(filtered, item)
	}
	return filtered
}

func favoriteMatchesCorridor(favorite domain.FavoriteRoute, corridorTrains []corridorTrain) bool {
	fromStationID := strings.TrimSpace(favorite.FromStationID)
	toStationID := strings.TrimSpace(favorite.ToStationID)
	if fromStationID == "" || toStationID == "" {
		return false
	}
	for _, item := range corridorTrains {
		if _, ok := item.StopSet[fromStationID]; !ok {
			continue
		}
		if _, ok := item.StopSet[toStationID]; !ok {
			continue
		}
		return true
	}
	return false
}

func overlapRatio(a map[string]struct{}, b map[string]struct{}) float64 {
	if len(a) == 0 || len(b) == 0 {
		return 0
	}
	smaller := a
	larger := b
	if len(a) > len(b) {
		smaller = b
		larger = a
	}
	common := 0
	for key := range smaller {
		if _, ok := larger[key]; ok {
			common++
		}
	}
	return float64(common) / float64(len(smaller))
}

func fallbackStationName(name string, fallback string) string {
	if strings.TrimSpace(name) != "" {
		return name
	}
	return fallback
}
