package bot

import (
	"context"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
	"telegramtrainapp/internal/store"
)

type dispatchFixture struct {
	ctx         context.Context
	store       *store.SQLiteStore
	notifier    *Notifier
	loc         *time.Location
	now         time.Time
	serviceDate string
}

func newDispatchFixture(t *testing.T) *dispatchFixture {
	t.Helper()

	ctx := context.Background()
	loc, err := time.LoadLocation("Europe/Riga")
	if err != nil {
		t.Fatalf("load location: %v", err)
	}
	now := time.Date(2026, time.March, 8, 12, 0, 0, 0, loc)

	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "dispatch.db"))
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	t.Cleanup(func() { _ = st.Close() })
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	fixture := &dispatchFixture{
		ctx:         ctx,
		store:       st,
		notifier:    &Notifier{store: st, loc: loc},
		loc:         loc,
		now:         now,
		serviceDate: now.Format("2006-01-02"),
	}
	fixture.seedCorridorNetwork(t)
	return fixture
}

func (f *dispatchFixture) seedCorridorNetwork(t *testing.T) {
	t.Helper()

	trains := []domain.TrainInstance{
		{
			ID:          "train-source",
			ServiceDate: f.serviceDate,
			FromStation: "Riga",
			ToStation:   "Tukums I",
			DepartureAt: f.now.Add(10 * time.Minute),
			ArrivalAt:   f.now.Add(70 * time.Minute),
		},
		{
			ID:          "train-reverse",
			ServiceDate: f.serviceDate,
			FromStation: "Tukums I",
			ToStation:   "Riga",
			DepartureAt: f.now.Add(15 * time.Minute),
			ArrivalAt:   f.now.Add(75 * time.Minute),
		},
		{
			ID:          "train-branch",
			ServiceDate: f.serviceDate,
			FromStation: "Riga",
			ToStation:   "Tukums II",
			DepartureAt: f.now.Add(20 * time.Minute),
			ArrivalAt:   f.now.Add(78 * time.Minute),
		},
		{
			ID:          "train-unrelated",
			ServiceDate: f.serviceDate,
			FromStation: "Riga",
			ToStation:   "Jelgava",
			DepartureAt: f.now.Add(25 * time.Minute),
			ArrivalAt:   f.now.Add(60 * time.Minute),
		},
	}
	if err := f.store.UpsertTrainInstances(f.ctx, f.serviceDate, "dispatch-test", trains); err != nil {
		t.Fatalf("upsert trains: %v", err)
	}

	stopsByTrain := map[string][]domain.TrainStop{
		"train-source":    buildTrainStops("train-source", f.now.Add(10*time.Minute), []stopSeed{{"riga", "Riga"}, {"zolitude", "Zolitude"}, {"babite", "Babite"}, {"sloka", "Sloka"}, {"melluzi", "Melluži"}, {"tukums1", "Tukums I"}}),
		"train-reverse":   buildTrainStops("train-reverse", f.now.Add(15*time.Minute), []stopSeed{{"tukums1", "Tukums I"}, {"melluzi", "Melluži"}, {"sloka", "Sloka"}, {"babite", "Babite"}, {"zolitude", "Zolitude"}, {"riga", "Riga"}}),
		"train-branch":    buildTrainStops("train-branch", f.now.Add(20*time.Minute), []stopSeed{{"riga", "Riga"}, {"zolitude", "Zolitude"}, {"babite", "Babite"}, {"sloka", "Sloka"}, {"melluzi", "Melluži"}, {"tukums2", "Tukums II"}}),
		"train-unrelated": buildTrainStops("train-unrelated", f.now.Add(25*time.Minute), []stopSeed{{"riga", "Riga"}, {"olaine", "Olaine"}, {"jelgava", "Jelgava"}}),
	}
	if err := f.store.UpsertTrainStops(f.ctx, f.serviceDate, stopsByTrain); err != nil {
		t.Fatalf("upsert stops: %v", err)
	}
}

type stopSeed struct {
	id   string
	name string
}

func buildTrainStops(trainID string, departure time.Time, stops []stopSeed) []domain.TrainStop {
	out := make([]domain.TrainStop, 0, len(stops))
	for idx, stop := range stops {
		arrival := departure.Add(time.Duration(idx*10) * time.Minute)
		depart := arrival.Add(2 * time.Minute)
		item := domain.TrainStop{
			TrainInstanceID: trainID,
			StationID:       stop.id,
			StationName:     stop.name,
			Seq:             idx + 1,
		}
		if idx == 0 {
			item.DepartureAt = &departure
		} else {
			item.ArrivalAt = &arrival
			item.DepartureAt = &depart
		}
		if idx == len(stops)-1 {
			item.DepartureAt = nil
		}
		out = append(out, item)
	}
	return out
}

func TestResolveRideAlertRecipientsIncludesExactCorridorAndSavedRoutes(t *testing.T) {
	t.Parallel()

	f := newDispatchFixture(t)

	for _, tc := range []struct {
		userID  int64
		trainID string
	}{
		{101, "train-source"},
		{103, "train-reverse"},
	} {
		if err := f.store.CheckInUser(f.ctx, tc.userID, tc.trainID, f.now.Add(-5*time.Minute), f.now.Add(2*time.Hour)); err != nil {
			t.Fatalf("check in user %d: %v", tc.userID, err)
		}
	}
	for _, tc := range []struct {
		userID int64
		fromID string
		toID   string
	}{
		{101, "riga", "tukums_i"},
		{105, "riga", "tukums_ii"},
		{106, "riga", "tukums_ii"},
		{107, "riga", "jelgava"},
	} {
		if err := f.store.UpsertFavoriteRoute(f.ctx, tc.userID, tc.fromID, tc.toID); err != nil {
			t.Fatalf("save favorite for user %d: %v", tc.userID, err)
		}
	}

	payload, recipients, err := f.notifier.resolveRideAlertRecipients(f.ctx, RideAlertPayload{
		TrainID:    "train-source",
		Signal:     domain.SignalInspectionStarted,
		ReportedAt: f.now,
		ReporterID: 999,
	}, f.now)
	if err != nil {
		t.Fatalf("resolve ride recipients: %v", err)
	}

	if payload.FromStation != "Riga" || payload.ToStation != "Tukums I" || payload.ArrivalAt.IsZero() {
		t.Fatalf("expected payload to be hydrated from store, got %+v", payload)
	}

	got := rideRecipientsByUser(recipients)
	if _, ok := got[107]; ok {
		t.Fatalf("unrelated saved route should not match corridor: %+v", got[107])
	}
	assertRideRecipient(t, got, 101, RideAlertAudienceExactTrain, "train-source")
	assertRideRecipient(t, got, 103, RideAlertAudienceCorridorTrain, "train-reverse")
	assertRideRecipient(t, got, 105, RideAlertAudienceSavedRoute, "")
	assertRideRecipient(t, got, 106, RideAlertAudienceSavedRoute, "")
	if len(got) != 4 {
		t.Fatalf("expected only exact, corridor, and saved-route recipients, got %+v", got)
	}
}

func TestResolveStationSightingRecipientsIncludesCorridorAndSavedRouteRecipients(t *testing.T) {
	t.Parallel()

	f := newDispatchFixture(t)

	for _, tc := range []struct {
		userID  int64
		trainID string
	}{
		{201, "train-source"},
		{203, "train-reverse"},
	} {
		if err := f.store.CheckInUser(f.ctx, tc.userID, tc.trainID, f.now.Add(-5*time.Minute), f.now.Add(2*time.Hour)); err != nil {
			t.Fatalf("check in user %d: %v", tc.userID, err)
		}
	}
	if err := f.store.UpsertFavoriteRoute(f.ctx, 205, "riga", "tukums_ii"); err != nil {
		t.Fatalf("save favorite route: %v", err)
	}

	matchedTrainID := "train-source"
	payload, recipients, err := f.notifier.resolveStationSightingRecipients(f.ctx, domain.StationSighting{
		ID:                     "station-sighting-1",
		StationID:              "melluži",
		StationName:            "Melluži",
		DestinationStationName: "Tukums I",
		MatchedTrainInstanceID: &matchedTrainID,
		UserID:                 999,
		CreatedAt:              f.now,
	}, f.now)
	if err != nil {
		t.Fatalf("resolve station recipients: %v", err)
	}

	if payload.MatchedTrainID != "train-source" || payload.MatchedFromStation != "Riga" || payload.MatchedToStation != "Tukums I" {
		t.Fatalf("expected matched train details in payload, got %+v", payload)
	}

	got := stationRecipientsByUser(recipients)
	assertStationRecipient(t, got, 201, StationSightingAudienceExactTrain, "train-source")
	assertStationRecipient(t, got, 203, StationSightingAudienceCorridorTrain, "train-reverse")
	assertStationRecipient(t, got, 205, StationSightingAudienceSavedRoute, "")
	if len(got) != 3 {
		t.Fatalf("expected only exact/corridor/saved-route station recipients, got %+v", got)
	}
}

func TestDispatchRideAlertSkipsMutedAndDisabledUsers(t *testing.T) {
	t.Parallel()

	f := newDispatchFixture(t)
	recorder, client, closeFunc := newTelegramRecorder(t)
	defer closeFunc()

	f.notifier = NewNotifier(client, f.store, i18n.NewCatalog(), f.loc, "https://example.test/pixel-stack/train", 0)

	if err := f.store.CheckInUser(f.ctx, 301, "train-source", f.now.Add(-5*time.Minute), f.now.Add(2*time.Hour)); err != nil {
		t.Fatalf("check in exact user: %v", err)
	}
	if err := f.store.CheckInUser(f.ctx, 302, "train-reverse", f.now.Add(-5*time.Minute), f.now.Add(2*time.Hour)); err != nil {
		t.Fatalf("check in corridor user: %v", err)
	}
	if err := f.store.CheckInUser(f.ctx, 303, "train-branch", f.now.Add(-5*time.Minute), f.now.Add(2*time.Hour)); err != nil {
		t.Fatalf("check in muted user: %v", err)
	}
	if err := f.store.SetAlertsEnabled(f.ctx, 302, false); err != nil {
		t.Fatalf("disable alerts: %v", err)
	}
	if err := f.store.SetTrainMute(f.ctx, 303, "train-branch", f.now.Add(30*time.Minute)); err != nil {
		t.Fatalf("mute train: %v", err)
	}

	if err := f.notifier.DispatchRideAlert(f.ctx, RideAlertPayload{
		TrainID:    "train-source",
		Signal:     domain.SignalInspectionEnded,
		ReportedAt: f.now,
		ReporterID: 999,
	}, f.now); err != nil {
		t.Fatalf("dispatch ride alert: %v", err)
	}

	chatIDs := recordedChatIDs(recorder.requestsByPath("/sendMessage"))
	if len(chatIDs) != 1 || chatIDs[0] != 301 {
		t.Fatalf("expected only unmuted enabled recipient to be notified, got %v", chatIDs)
	}
}

func TestNotifierRunBatchesDumpMessages(t *testing.T) {
	t.Parallel()

	f := newDispatchFixture(t)
	recorder, client, closeFunc := newTelegramRecorder(t)
	defer closeFunc()

	notifier := NewNotifier(client, f.store, i18n.NewCatalog(), f.loc, "", -1003867662138)
	notifier.reportDumpInterval = 20 * time.Millisecond
	notifier.reportDumpMaxChars = 3500

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = notifier.Run(ctx)
	}()

	notifier.enqueueReportDump(notifier.newRideReportDumpItem(RideAlertPayload{
		TrainID:     "train-source",
		FromStation: "Riga",
		ToStation:   "Tukums I",
		DepartureAt: f.now.Add(10 * time.Minute),
		ArrivalAt:   f.now.Add(70 * time.Minute),
		Signal:      domain.SignalInspectionStarted,
		ReportedAt:  f.now,
		ReporterID:  777,
	}))
	matchedTrainID := "train-source"
	notifier.enqueueReportDump(notifier.newStationSightingDumpItem(StationSightingAlertPayload{
		StationID:              "melluzi",
		StationName:            "Melluži",
		DestinationStationName: "Tukums I",
		MatchedTrainID:         matchedTrainID,
		MatchedFromStation:     "Riga",
		MatchedToStation:       "Tukums I",
		MatchedDepartureAt:     f.now.Add(10 * time.Minute),
		MatchedArrivalAt:       f.now.Add(70 * time.Minute),
		ReportedAt:             f.now,
	}, domain.StationSighting{
		ID:                     "station-sighting-1",
		StationID:              "melluzi",
		StationName:            "Melluži",
		DestinationStationName: "Tukums I",
		MatchedTrainInstanceID: &matchedTrainID,
		UserID:                 777,
		CreatedAt:              f.now,
	}))

	waitForRequests(t, recorder, "/sendMessage", 1, 2*time.Second)

	requests := recorder.requestsByPath("/sendMessage")
	if len(requests) != 1 {
		t.Fatalf("expected one batched dump message, got %d", len(requests))
	}
	text, _ := requests[0].payload["text"].(string)
	if !strings.Contains(text, "Pārbaudes ziņojums") || !strings.Contains(text, "Perona novērojums") {
		t.Fatalf("expected dump message to contain both entries, got %q", text)
	}
	if strings.Contains(text, "777") {
		t.Fatalf("dump message should not contain reporter user id, got %q", text)
	}

	cancel()
	<-done
}

func TestSplitReportDumpItemsSplitsOversizedBatch(t *testing.T) {
	t.Parallel()

	chunks := splitReportDumpItems([]reportDumpItem{
		{entry: "Pārbaudes ziņojums\n" + strings.Repeat("a", 3300)},
		{entry: "Perona novērojums\n" + strings.Repeat("b", 3300)},
	}, 3500)

	if len(chunks) < 2 {
		t.Fatalf("expected oversized batch to split, got %d chunks", len(chunks))
	}
	for _, chunk := range chunks {
		if len(chunk) > 3500 {
			t.Fatalf("chunk exceeds size limit: %d", len(chunk))
		}
	}
}

func rideRecipientsByUser(recipients []RideAlertRecipient) map[int64]RideAlertRecipient {
	out := make(map[int64]RideAlertRecipient, len(recipients))
	for _, recipient := range recipients {
		out[recipient.UserID] = recipient
	}
	return out
}

func stationRecipientsByUser(recipients []StationSightingRecipient) map[int64]StationSightingRecipient {
	out := make(map[int64]StationSightingRecipient, len(recipients))
	for _, recipient := range recipients {
		out[recipient.UserID] = recipient
	}
	return out
}

func assertRideRecipient(t *testing.T, recipients map[int64]RideAlertRecipient, userID int64, audience RideAlertAudience, trainID string) {
	t.Helper()

	recipient, ok := recipients[userID]
	if !ok {
		t.Fatalf("missing recipient %d", userID)
	}
	if recipient.Audience != audience || recipient.ContextTrainID != trainID {
		t.Fatalf("unexpected recipient %d: %+v", userID, recipient)
	}
}

func assertStationRecipient(t *testing.T, recipients map[int64]StationSightingRecipient, userID int64, audience StationSightingAudience, trainID string) {
	t.Helper()

	recipient, ok := recipients[userID]
	if !ok {
		t.Fatalf("missing recipient %d", userID)
	}
	if recipient.Audience != audience || recipient.ContextTrainID != trainID {
		t.Fatalf("unexpected recipient %d: %+v", userID, recipient)
	}
}

func recordedChatIDs(requests []recordedRequest) []int64 {
	ids := make([]int64, 0, len(requests))
	for _, request := range requests {
		if raw, ok := request.payload["chat_id"].(float64); ok {
			ids = append(ids, int64(raw))
		}
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	return ids
}

func (r *telegramRecorder) requestsByPath(path string) []recordedRequest {
	r.mu.Lock()
	defer r.mu.Unlock()

	out := make([]recordedRequest, 0, len(r.requests))
	for _, request := range r.requests {
		if request.path == path {
			out = append(out, request)
		}
	}
	return out
}

func waitForRequests(t *testing.T, recorder *telegramRecorder, path string, want int, timeout time.Duration) {
	t.Helper()

	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if len(recorder.requestsByPath(path)) >= want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d requests to %s", want, path)
}
