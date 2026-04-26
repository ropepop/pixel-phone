package web

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	trainapp "telegramtrainapp/internal/app"
	"telegramtrainapp/internal/config"
	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
	"telegramtrainapp/internal/reports"
	"telegramtrainapp/internal/ride"
	"telegramtrainapp/internal/schedule"
	"telegramtrainapp/internal/store"
)

type captureRideNotifier struct {
	stationEvents []domain.StationSighting
}

func (n *captureRideNotifier) NotifyRideUsers(_ context.Context, _ int64, _ string, _ domain.SignalType, _ time.Time) error {
	return nil
}

func (n *captureRideNotifier) NotifyStationSighting(_ context.Context, event domain.StationSighting, _ time.Time) error {
	n.stationEvents = append(n.stationEvents, event)
	return nil
}

func testSessionCookie(t *testing.T, server *Server, userID int64, language string, now time.Time) *http.Cookie {
	t.Helper()

	cookie, err := issueSessionCookie(server.sessionSecret, telegramAuth{
		AuthDate: now,
		User: telegramUser{
			ID:           userID,
			LanguageCode: language,
		},
	}, now)
	if err != nil {
		t.Fatalf("issue session cookie: %v", err)
	}
	return cookie
}

func newAuthenticatedDataServerWithTrains(t *testing.T, publicBaseURL string, now time.Time, trains []publicSnapshotTrain) (*Server, *store.SQLiteStore) {
	t.Helper()

	ctx := context.Background()
	loc, err := time.LoadLocation("Europe/Riga")
	if err != nil {
		t.Fatalf("load location: %v", err)
	}

	dir := t.TempDir()
	secretPath := filepath.Join(dir, "train-session-secret")
	if err := os.WriteFile(secretPath, []byte("0123456789abcdef0123456789abcdef"), 0o600); err != nil {
		t.Fatalf("write secret: %v", err)
	}
	dbPath := filepath.Join(dir, "train-bot.db")
	st, err := store.NewSQLiteStore(dbPath)
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	t.Cleanup(func() {
		_ = st.Close()
	})
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	serviceDate := now.In(loc).Format("2006-01-02")
	snapshotPath := filepath.Join(dir, serviceDate+".json")
	payload, err := json.Marshal(publicSnapshot{
		SourceVersion: "server-auth-test",
		Trains:        trains,
	})
	if err != nil {
		t.Fatalf("marshal snapshot: %v", err)
	}
	if err := os.WriteFile(snapshotPath, payload, 0o644); err != nil {
		t.Fatalf("write snapshot: %v", err)
	}

	manager := schedule.NewManager(st, dir, loc, 3)
	if err := manager.LoadToday(ctx, now.In(loc)); err != nil {
		t.Fatalf("load today: %v", err)
	}

	appSvc := trainapp.NewService(
		st,
		manager,
		ride.NewService(st),
		reports.NewService(st, 3*time.Minute, 90*time.Second),
		loc,
		true,
	)
	server, err := NewServer(config.Config{
		BotToken:                      "bot-token",
		TrainWebEnabled:               true,
		TrainWebBindAddr:              "127.0.0.1",
		TrainWebPort:                  9317,
		TrainWebPublicBaseURL:         publicBaseURL,
		TrainWebSessionSecretFile:     secretPath,
		TrainWebTelegramAuthMaxAgeSec: 300,
	}, appSvc, i18n.NewCatalog(), loc)
	if err != nil {
		t.Fatalf("new server: %v", err)
	}
	return server, st
}

func TestServeHTTPStationSightingSubmissionUsesSelectedTrain(t *testing.T) {
	t.Parallel()

	server, _, now := newPublicDataServerWithStore(t, "https://example.test/pixel-stack/train")
	notifier := &captureRideNotifier{}
	server.SetNotifier(notifier)

	req := httptest.NewRequest(http.MethodPost, "/pixel-stack/train/api/v1/stations/riga/sightings", bytes.NewReader([]byte(`{"destinationStationId":"jelgava","trainId":"train-next-0"}`)))
	req.AddCookie(testSessionCookie(t, server, 77, "en", now))
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("unexpected station sighting status: got %d body=%s", res.Code, res.Body.String())
	}

	var payload struct {
		Accepted bool `json:"accepted"`
		Event    *struct {
			MatchedTrainInstanceID *string `json:"matchedTrainInstanceId"`
			DestinationStationID   *string `json:"destinationStationId"`
			StationName            string  `json:"stationName"`
		} `json:"event"`
	}
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode station sighting response: %v", err)
	}
	if !payload.Accepted || payload.Event == nil {
		t.Fatalf("expected accepted station sighting event, got %+v", payload)
	}
	if payload.Event.MatchedTrainInstanceID == nil || *payload.Event.MatchedTrainInstanceID != "train-next-0" {
		t.Fatalf("expected selected train-next-0 to be recorded, got %+v", payload.Event)
	}
	if payload.Event.DestinationStationID == nil || *payload.Event.DestinationStationID != "stop_0" {
		t.Fatalf("expected selected train terminal destination stop_0 to win over request body, got %+v", payload.Event)
	}
	if payload.Event.StationName != "Riga" {
		t.Fatalf("expected station name to be populated, got %+v", payload.Event)
	}
	if len(notifier.stationEvents) != 1 {
		t.Fatalf("expected exactly one notifier event, got %d", len(notifier.stationEvents))
	}
	if notifier.stationEvents[0].MatchedTrainInstanceID == nil || *notifier.stationEvents[0].MatchedTrainInstanceID != "train-next-0" {
		t.Fatalf("expected notifier to receive matched train-next-0, got %+v", notifier.stationEvents[0])
	}
}

func TestServeHTTPCheckInStoresBoardingStationAndReturnsCurrentRide(t *testing.T) {
	t.Parallel()

	server, _, now := newPublicDataServerWithStore(t, "https://example.test/pixel-stack/train")
	cookie := testSessionCookie(t, server, 77, "lv", now)

	req := httptest.NewRequest(http.MethodPut, "/pixel-stack/train/api/v1/checkins/current", bytes.NewReader([]byte(`{"trainId":"train-next-0","boardingStationId":"riga"}`)))
	req.AddCookie(cookie)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("unexpected check-in status: got %d body=%s", res.Code, res.Body.String())
	}

	var payload struct {
		CurrentRide *struct {
			CheckIn *struct {
				TrainInstanceID   string  `json:"trainInstanceId"`
				BoardingStationID *string `json:"boardingStationId"`
			} `json:"checkIn"`
			BoardingStationID   string `json:"boardingStationId"`
			BoardingStationName string `json:"boardingStationName"`
			Train               *struct {
				TrainCard struct {
					Train struct {
						ID string `json:"id"`
					} `json:"train"`
				} `json:"trainCard"`
			} `json:"train"`
		} `json:"currentRide"`
	}
	if err := json.Unmarshal(res.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode check-in response: %v", err)
	}
	if payload.CurrentRide == nil || payload.CurrentRide.CheckIn == nil || payload.CurrentRide.Train == nil {
		t.Fatalf("expected currentRide payload after check-in, got %+v", payload)
	}
	if payload.CurrentRide.CheckIn.TrainInstanceID != "train-next-0" {
		t.Fatalf("expected train-next-0 check-in, got %+v", payload.CurrentRide.CheckIn)
	}
	if payload.CurrentRide.BoardingStationID != "riga" {
		t.Fatalf("expected boardingStationId riga, got %+v", payload.CurrentRide)
	}
	if payload.CurrentRide.BoardingStationName != "Riga" {
		t.Fatalf("expected boardingStationName Riga, got %+v", payload.CurrentRide)
	}
	if payload.CurrentRide.Train.TrainCard.Train.ID != "train-next-0" {
		t.Fatalf("expected current ride train card for train-next-0, got %+v", payload.CurrentRide.Train)
	}
}

func TestServeHTTPCheckInRejectsExpiredDepartureAndLeavesCurrentRideEmpty(t *testing.T) {
	t.Parallel()

	loc, err := time.LoadLocation("Europe/Riga")
	if err != nil {
		t.Fatalf("load location: %v", err)
	}
	now := time.Now().In(loc).Truncate(time.Minute)
	serviceDate := now.Format("2006-01-02")
	server, _ := newAuthenticatedDataServerWithTrains(t, "https://example.test/pixel-stack/train", now, []publicSnapshotTrain{
		buildPublicSnapshotTrain("train-expired", serviceDate, "Riga", "Jelgava", now.Add(-90*time.Minute)),
	})
	cookie := testSessionCookie(t, server, 77, "lv", now)

	req := httptest.NewRequest(http.MethodPut, "/pixel-stack/train/api/v1/checkins/current", bytes.NewReader([]byte(`{"trainId":"train-expired"}`)))
	req.AddCookie(cookie)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusConflict {
		t.Fatalf("unexpected expired check-in status: got %d body=%s", res.Code, res.Body.String())
	}

	var errPayload struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(res.Body.Bytes(), &errPayload); err != nil {
		t.Fatalf("decode expired check-in response: %v", err)
	}
	if errPayload.Error == "" {
		t.Fatalf("expected expired check-in error message, got %+v", errPayload)
	}

	currentReq := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/api/v1/checkins/current", nil)
	currentReq.AddCookie(cookie)
	currentRes := httptest.NewRecorder()

	server.ServeHTTP(currentRes, currentReq)

	if currentRes.Code != http.StatusOK {
		t.Fatalf("unexpected current ride status after expired check-in: got %d body=%s", currentRes.Code, currentRes.Body.String())
	}

	var currentPayload struct {
		CurrentRide any `json:"currentRide"`
	}
	if err := json.Unmarshal(currentRes.Body.Bytes(), &currentPayload); err != nil {
		t.Fatalf("decode current ride response after expired check-in: %v", err)
	}
	if currentPayload.CurrentRide != nil {
		t.Fatalf("expected no active current ride after expired check-in, got %+v", currentPayload.CurrentRide)
	}
}

func TestServeHTTPCheckInRejectsExpiredBoardingStationDepartureAndLeavesCurrentRideEmpty(t *testing.T) {
	t.Parallel()

	loc, err := time.LoadLocation("Europe/Riga")
	if err != nil {
		t.Fatalf("load location: %v", err)
	}
	now := time.Now().In(loc).Truncate(time.Minute)
	serviceDate := now.Format("2006-01-02")
	server, _ := newAuthenticatedDataServerWithTrains(t, "https://example.test/pixel-stack/train", now, []publicSnapshotTrain{
		{
			ID:          "train-station-window-expired",
			ServiceDate: serviceDate,
			FromStation: "Riga",
			ToStation:   "Tukums",
			DepartureAt: now.Add(-20 * time.Minute).Format(time.RFC3339),
			ArrivalAt:   now.Add(25 * time.Minute).Format(time.RFC3339),
			Stops: []publicSnapshotStop{
				{StationName: "Riga", Seq: 1, DepartureAt: now.Add(-20 * time.Minute).Format(time.RFC3339)},
				{StationName: "Tukums", Seq: 2, ArrivalAt: now.Add(25 * time.Minute).Format(time.RFC3339)},
			},
		},
	})
	cookie := testSessionCookie(t, server, 77, "lv", now)

	req := httptest.NewRequest(http.MethodPut, "/pixel-stack/train/api/v1/checkins/current", bytes.NewReader([]byte(`{"trainId":"train-station-window-expired","boardingStationId":"riga"}`)))
	req.AddCookie(cookie)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusConflict {
		t.Fatalf("unexpected expired station check-in status: got %d body=%s", res.Code, res.Body.String())
	}

	var errPayload struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(res.Body.Bytes(), &errPayload); err != nil {
		t.Fatalf("decode expired station check-in response: %v", err)
	}
	if errPayload.Error == "" {
		t.Fatalf("expected expired station check-in error message, got %+v", errPayload)
	}

	currentReq := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/api/v1/checkins/current", nil)
	currentReq.AddCookie(cookie)
	currentRes := httptest.NewRecorder()

	server.ServeHTTP(currentRes, currentReq)

	if currentRes.Code != http.StatusOK {
		t.Fatalf("unexpected current ride status after expired station check-in: got %d body=%s", currentRes.Code, currentRes.Body.String())
	}

	var currentPayload struct {
		CurrentRide any `json:"currentRide"`
	}
	if err := json.Unmarshal(currentRes.Body.Bytes(), &currentPayload); err != nil {
		t.Fatalf("decode current ride response after expired station check-in: %v", err)
	}
	if currentPayload.CurrentRide != nil {
		t.Fatalf("expected no active current ride after expired station check-in, got %+v", currentPayload.CurrentRide)
	}
}

func TestServeHTTPSubscriptionRouteIsNotExposed(t *testing.T) {
	t.Parallel()

	server, _, now := newPublicDataServerWithStore(t, "https://example.test/pixel-stack/train")
	cookie := testSessionCookie(t, server, 77, "lv", now)

	req := httptest.NewRequest(http.MethodPut, "/pixel-stack/train/api/v1/trains/train-next-0/subscription", bytes.NewReader([]byte(`{"enabled":true}`)))
	req.AddCookie(cookie)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusNotFound {
		t.Fatalf("expected removed subscription route to return 404, got %d body=%s", res.Code, res.Body.String())
	}
}

func TestServeHTTPSettingsAndMeOmitLegacyGlobalStationSightings(t *testing.T) {
	t.Parallel()

	server, _, now := newPublicDataServerWithStore(t, "https://example.test/pixel-stack/train")
	cookie := testSessionCookie(t, server, 88, "en", now)

	patchReq := httptest.NewRequest(http.MethodPatch, "/pixel-stack/train/api/v1/settings", bytes.NewReader([]byte(`{"alertsEnabled":true,"globalStationSightingsEnabled":true,"alertStyle":"DETAILED","language":"lv"}`)))
	patchReq.AddCookie(cookie)
	patchRes := httptest.NewRecorder()

	server.ServeHTTP(patchRes, patchReq)

	if patchRes.Code != http.StatusOK {
		t.Fatalf("unexpected settings patch status: got %d body=%s", patchRes.Code, patchRes.Body.String())
	}

	var settingsPayload map[string]any
	if err := json.Unmarshal(patchRes.Body.Bytes(), &settingsPayload); err != nil {
		t.Fatalf("decode settings patch response: %v", err)
	}
	if settingsPayload["alertsEnabled"] != true {
		t.Fatalf("expected alertsEnabled true in response, got %+v", settingsPayload)
	}
	if settingsPayload["alertStyle"] != "DETAILED" || settingsPayload["language"] != "LV" {
		t.Fatalf("expected settings normalization in response, got %+v", settingsPayload)
	}
	if _, exists := settingsPayload["globalStationSightingsEnabled"]; exists {
		t.Fatalf("expected legacy globalStationSightingsEnabled to be omitted, got %+v", settingsPayload)
	}

	meReq := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/api/v1/me", nil)
	meReq.AddCookie(cookie)
	meRes := httptest.NewRecorder()

	server.ServeHTTP(meRes, meReq)

	if meRes.Code != http.StatusOK {
		t.Fatalf("unexpected /me status: got %d body=%s", meRes.Code, meRes.Body.String())
	}

	var mePayload map[string]any
	if err := json.Unmarshal(meRes.Body.Bytes(), &mePayload); err != nil {
		t.Fatalf("decode /me response: %v", err)
	}
	settings, ok := mePayload["settings"].(map[string]any)
	if !ok {
		t.Fatalf("expected settings map in /me response, got %+v", mePayload)
	}
	if settings["language"] != "LV" {
		t.Fatalf("expected /me settings language LV, got %+v", settings)
	}
	if _, exists := settings["globalStationSightingsEnabled"]; exists {
		t.Fatalf("expected /me settings to omit legacy globalStationSightingsEnabled, got %+v", settings)
	}
}
