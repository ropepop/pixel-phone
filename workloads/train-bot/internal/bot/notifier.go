package bot

import (
	"context"
	"log"
	"sort"
	"strings"
	"time"

	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
	"telegramtrainapp/internal/store"
)

type Notifier struct {
	client             *Client
	store              store.Store
	catalog            *i18n.Catalog
	loc                *time.Location
	webAppURL          string
	reportDumpChatID   int64
	reportDumpQueue    chan reportDumpItem
	reportDumpInterval time.Duration
	reportDumpMaxChars int
}

func NewNotifier(client *Client, st store.Store, catalog *i18n.Catalog, loc *time.Location, webAppURL string, reportDumpChatID int64) *Notifier {
	return &Notifier{
		client:             client,
		store:              st,
		catalog:            catalog,
		loc:                loc,
		webAppURL:          strings.TrimRight(strings.TrimSpace(webAppURL), "/"),
		reportDumpChatID:   reportDumpChatID,
		reportDumpQueue:    make(chan reportDumpItem, 256),
		reportDumpInterval: time.Second,
		reportDumpMaxChars: 3500,
	}
}

type RideAlertPayload struct {
	TrainID     string
	FromStation string
	ToStation   string
	DepartureAt time.Time
	ArrivalAt   time.Time
	Signal      domain.SignalType
	ReportedAt  time.Time
	ReporterID  int64
}

type StationSightingAudience string

const (
	StationSightingAudienceExactTrain        StationSightingAudience = "EXACT_TRAIN"
	StationSightingAudienceExactSubscription StationSightingAudience = "EXACT_SUBSCRIPTION"
	StationSightingAudienceCorridorTrain     StationSightingAudience = "CORRIDOR_TRAIN"
	StationSightingAudienceCorridorSub       StationSightingAudience = "CORRIDOR_SUBSCRIPTION"
	StationSightingAudienceSavedRoute        StationSightingAudience = "SAVED_ROUTE"
	StationSightingAudienceNearbyTrain       StationSightingAudience = "NEARBY_TRAIN"
)

type StationSightingAlertPayload struct {
	StationID              string
	StationName            string
	DestinationStationName string
	MatchedTrainID         string
	MatchedFromStation     string
	MatchedToStation       string
	MatchedDepartureAt     time.Time
	MatchedArrivalAt       time.Time
	ReportedAt             time.Time
	ReporterID             int64
}

type StationSightingRecipient struct {
	UserID              int64
	Audience            StationSightingAudience
	ContextTrainID      string
	ContextFromStation  string
	ContextToStation    string
	ContextDepartureAt  time.Time
	FavoriteFromStation string
	FavoriteToStation   string
}

type RideAlertAudience string

const (
	RideAlertAudienceExactTrain        RideAlertAudience = "EXACT_TRAIN"
	RideAlertAudienceExactSubscription RideAlertAudience = "EXACT_SUBSCRIPTION"
	RideAlertAudienceCorridorTrain     RideAlertAudience = "CORRIDOR_TRAIN"
	RideAlertAudienceCorridorSub       RideAlertAudience = "CORRIDOR_SUBSCRIPTION"
	RideAlertAudienceSavedRoute        RideAlertAudience = "SAVED_ROUTE"
)

type RideAlertRecipient struct {
	UserID              int64
	Audience            RideAlertAudience
	ContextTrainID      string
	ContextFromStation  string
	ContextToStation    string
	ContextDepartureAt  time.Time
	FavoriteFromStation string
	FavoriteToStation   string
}

func (n *Notifier) NotifyRideUsers(ctx context.Context, payload RideAlertPayload, checkInUsers []int64, subscriptionUsers []int64, now time.Time) error {
	recipientsByUser := make(map[int64]RideAlertRecipient, len(checkInUsers)+len(subscriptionUsers))
	for _, id := range checkInUsers {
		recipientsByUser[id] = RideAlertRecipient{
			UserID:             id,
			Audience:           RideAlertAudienceExactTrain,
			ContextTrainID:     payload.TrainID,
			ContextFromStation: payload.FromStation,
			ContextToStation:   payload.ToStation,
			ContextDepartureAt: payload.DepartureAt,
		}
	}
	for _, id := range subscriptionUsers {
		if _, exists := recipientsByUser[id]; exists {
			continue
		}
		recipientsByUser[id] = RideAlertRecipient{
			UserID:             id,
			Audience:           RideAlertAudienceExactSubscription,
			ContextTrainID:     payload.TrainID,
			ContextFromStation: payload.FromStation,
			ContextToStation:   payload.ToStation,
			ContextDepartureAt: payload.DepartureAt,
		}
	}
	recipients := make([]RideAlertRecipient, 0, len(recipientsByUser))
	for _, recipient := range recipientsByUser {
		recipients = append(recipients, recipient)
	}
	sort.Slice(recipients, func(i, j int) bool { return recipients[i].UserID < recipients[j].UserID })
	return n.sendRideAlertRecipients(ctx, payload, recipients, now)
}

func (n *Notifier) NotifyStationSightingUsers(ctx context.Context, payload StationSightingAlertPayload, recipients []StationSightingRecipient, now time.Time) error {
	if n == nil || n.client == nil {
		return nil
	}
	for _, recipient := range recipients {
		if recipient.UserID == payload.ReporterID {
			continue
		}
		if recipient.ContextTrainID != "" {
			muted, err := n.store.IsTrainMuted(ctx, recipient.UserID, recipient.ContextTrainID, now)
			if err != nil {
				log.Printf("get train mute user %d: %v", recipient.UserID, err)
				continue
			}
			if muted {
				continue
			}
		}
		settings, err := n.store.GetUserSettings(ctx, recipient.UserID)
		if err != nil {
			log.Printf("get settings user %d: %v", recipient.UserID, err)
			continue
		}
		if !settings.AlertsEnabled {
			continue
		}
		text := n.catalog.T(settings.Language, "station_sighting_alert_discreet")
		if settings.AlertStyle == domain.AlertStyleDetailed {
			text = n.stationSightingDetailedText(settings.Language, payload, recipient, now)
		}
		if err := n.client.SendMessage(ctx, recipient.UserID, text, MessageOptions{ReplyMarkup: n.stationSightingKeyboard(settings.Language, recipient.ContextTrainID)}); err != nil {
			log.Printf("notify station sighting user %d failed: %v", recipient.UserID, err)
		}
	}
	return nil
}

func (n *Notifier) alertKeyboard(lang domain.Language, trainID string) map[string]any {
	if n.webAppURL == "" {
		return InlineKeyboard(
			[]map[string]string{InlineButton(n.catalog.T(lang, "btn_view_status"), BuildCallback("status", "view", trainID))},
			[]map[string]string{InlineButton(n.catalog.T(lang, "btn_mute_30m"), BuildCallback("ride", "mute", "30", trainID))},
		)
	}
	return InlineKeyboardAny(
		[]map[string]any{InlineButtonAny(n.catalog.T(lang, "btn_view_status"), BuildCallback("status", "view", trainID))},
		[]map[string]any{InlineButtonAny(n.catalog.T(lang, "btn_mute_30m"), BuildCallback("ride", "mute", "30", trainID))},
		[]map[string]any{WebAppInlineButton(n.catalog.T(lang, "btn_open_app"), n.webAppURL+"/app")},
	)
}

func (n *Notifier) stationSightingKeyboard(lang domain.Language, trainID string) map[string]any {
	if strings.TrimSpace(trainID) == "" {
		if n.webAppURL == "" {
			return nil
		}
		return InlineKeyboardAny(
			[]map[string]any{WebAppInlineButton(n.catalog.T(lang, "btn_open_app"), n.webAppURL+"/app")},
		)
	}
	return n.alertKeyboard(lang, trainID)
}

func (n *Notifier) signalLabel(lang domain.Language, signal domain.SignalType) string {
	switch signal {
	case domain.SignalInspectionStarted:
		return n.catalog.T(lang, "event_inspection_started")
	case domain.SignalInspectionInCar:
		return n.catalog.T(lang, "event_inspection_in_car")
	case domain.SignalInspectionEnded:
		return n.catalog.T(lang, "event_inspection_ended")
	default:
		return n.catalog.T(lang, "event_unknown")
	}
}

func (n *Notifier) stationSightingDetailedText(lang domain.Language, payload StationSightingAlertPayload, recipient StationSightingRecipient, now time.Time) string {
	switch recipient.Audience {
	case StationSightingAudienceExactTrain, StationSightingAudienceExactSubscription:
		return n.catalog.T(
			lang,
			"station_sighting_alert_exact",
			payload.StationName,
			payload.MatchedFromStation,
			payload.MatchedToStation,
			payload.MatchedDepartureAt.In(n.loc).Format("15:04"),
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	case StationSightingAudienceCorridorTrain, StationSightingAudienceCorridorSub:
		return n.catalog.T(
			lang,
			"station_sighting_alert_same_corridor",
			payload.StationName,
			payload.MatchedFromStation,
			payload.MatchedToStation,
			payload.MatchedDepartureAt.In(n.loc).Format("15:04"),
			recipient.ContextFromStation,
			recipient.ContextToStation,
			recipient.ContextDepartureAt.In(n.loc).Format("15:04"),
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	case StationSightingAudienceSavedRoute:
		return n.catalog.T(
			lang,
			"station_sighting_alert_saved_route",
			payload.StationName,
			payload.MatchedFromStation,
			payload.MatchedToStation,
			payload.MatchedDepartureAt.In(n.loc).Format("15:04"),
			recipient.FavoriteFromStation,
			recipient.FavoriteToStation,
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	case StationSightingAudienceNearbyTrain:
		return n.catalog.T(
			lang,
			"station_sighting_alert_nearby",
			payload.StationName,
			recipient.ContextFromStation,
			recipient.ContextToStation,
			recipient.ContextDepartureAt.In(n.loc).Format("15:04"),
			n.relativeAgo(lang, now, payload.ReportedAt),
		)
	default:
		return n.catalog.T(lang, "station_sighting_alert_discreet")
	}
}

func (n *Notifier) relativeAgo(lang domain.Language, now time.Time, t time.Time) string {
	mins := int(now.Sub(t).Minutes())
	if mins <= 0 {
		return n.catalog.T(lang, "relative_now")
	}
	if mins == 1 {
		return n.catalog.T(lang, "relative_one_min")
	}
	return n.catalog.T(lang, "relative_many_mins", mins)
}
