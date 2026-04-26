package web

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"satiksmebot/internal/config"
	"satiksmebot/internal/domain"
	"satiksmebot/internal/reports"
	"satiksmebot/internal/runtime"
	"satiksmebot/internal/store"
)

type staticCatalog struct {
	catalog     *domain.Catalog
	status      runtime.CatalogStatus
	catalogJSON []byte
	etag        string
}

func (s staticCatalog) Current() *domain.Catalog { return s.catalog }
func (s staticCatalog) Status() runtime.CatalogStatus {
	return s.status
}
func (s staticCatalog) FindStop(stopID string) (domain.Stop, bool) {
	for _, stop := range s.catalog.Stops {
		if stop.ID == stopID {
			return stop, true
		}
	}
	return domain.Stop{}, false
}
func (s staticCatalog) CatalogJSON() []byte { return s.catalogJSON }
func (s staticCatalog) CatalogETag() string { return s.etag }

func TestPublicCannotSubmitAndAuthenticatedSessionCan(t *testing.T) {
	ctx := context.Background()
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	secretPath := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(secretPath, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	now := time.Date(2026, 3, 10, 18, 55, 0, 0, time.UTC)
	testCatalog := &domain.Catalog{
		GeneratedAt: now.Add(-10 * time.Minute),
		Stops:       []domain.Stop{{ID: "3012", Name: "Centrāltirgus", Latitude: 56.94, Longitude: 24.12}},
		Routes:      []domain.Route{{Label: "1", Mode: "tram", Name: "Imanta"}},
	}
	catalogJSON, err := json.Marshal(testCatalog)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	sum := sha256.Sum256(catalogJSON)
	catalogReader := staticCatalog{
		catalog: testCatalog,
		status: runtime.CatalogStatus{
			Loaded:             true,
			GeneratedAt:        testCatalog.GeneratedAt,
			LastRefreshAttempt: now.Add(-10 * time.Minute),
			LastRefreshSuccess: now.Add(-10 * time.Minute),
			StopCount:          len(testCatalog.Stops),
			RouteCount:         len(testCatalog.Routes),
		},
		catalogJSON: catalogJSON,
		etag:        `"` + hex.EncodeToString(sum[:]) + `"`,
	}
	runtimeState := runtime.New(now.Add(-time.Hour), true, "127.0.0.1:9318")
	runtimeState.UpdateCatalog(catalogReader.status)
	runtimeState.RecordTelegramSuccess(now.Add(-2*time.Minute), 101)
	runtimeState.RecordDumpSuccess(now.Add(-time.Minute), 0)
	runtimeState.SetWebListening(true)

	cfg := config.Config{
		BotToken:                         "bot-token",
		SatiksmeWebEnabled:               true,
		SatiksmeWebBindAddr:              "127.0.0.1",
		SatiksmeWebPort:                  9318,
		SatiksmeWebPublicBaseURL:         "https://satiksme-bot.jolkins.id.lv",
		SatiksmeWebSessionSecretFile:     secretPath,
		SatiksmeWebTelegramAuthMaxAgeSec: 300,
		LiveDeparturesURL:                "https://saraksti.rigassatiksme.lv/departures2.php",
	}
	server, err := NewServer(cfg, catalogReader, reports.NewService(st, 3*time.Minute, 90*time.Second, 30*time.Minute), nil, st, runtimeState, time.UTC)
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}

	ts := httptest.NewServer(server)
	defer ts.Close()

	reportBody := bytes.NewBufferString(`{"stopId":"3012"}`)
	resp, err := http.Post(ts.URL+"/api/v1/reports/stop", "application/json", reportBody)
	if err != nil {
		t.Fatalf("public report POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("public report status = %d, want 401", resp.StatusCode)
	}

	initData := signedInitData(t, "bot-token", 99, time.Now().UTC())
	authResp, err := http.Post(ts.URL+"/api/v1/auth/telegram", "application/json", bytes.NewBufferString(`{"initData":"`+initData+`"}`))
	if err != nil {
		t.Fatalf("auth POST error = %v", err)
	}
	defer authResp.Body.Close()
	if authResp.StatusCode != http.StatusOK {
		t.Fatalf("auth status = %d, want 200", authResp.StatusCode)
	}
	var sessionCookie *http.Cookie
	for _, cookie := range authResp.Cookies() {
		if cookie.Name == sessionCookieName {
			sessionCookie = cookie
			break
		}
	}
	if sessionCookie == nil {
		t.Fatalf("expected session cookie")
	}

	req, err := http.NewRequest(http.MethodPost, ts.URL+"/api/v1/reports/vehicle", bytes.NewBufferString(`{"stopId":"3012","mode":"tram","routeLabel":"1","direction":"b-a","destination":"Imanta","departureSeconds":68420}`))
	if err != nil {
		t.Fatalf("NewRequest() error = %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(sessionCookie)
	httpClient := &http.Client{}
	vehicleResp, err := httpClient.Do(req)
	if err != nil {
		t.Fatalf("vehicle POST error = %v", err)
	}
	defer vehicleResp.Body.Close()
	if vehicleResp.StatusCode != http.StatusOK {
		t.Fatalf("vehicle status = %d, want 200", vehicleResp.StatusCode)
	}

	liveVehicleReq, err := http.NewRequest(http.MethodPost, ts.URL+"/api/v1/reports/vehicle", bytes.NewBufferString(`{"stopId":"754","mode":"bus","routeLabel":"10","direction":"b-a","destination":"Abrenes iela","departureSeconds":46406}`))
	if err != nil {
		t.Fatalf("NewRequest(live vehicle) error = %v", err)
	}
	liveVehicleReq.Header.Set("Content-Type", "application/json")
	liveVehicleReq.AddCookie(sessionCookie)
	liveVehicleResp, err := httpClient.Do(liveVehicleReq)
	if err != nil {
		t.Fatalf("live vehicle POST error = %v", err)
	}
	defer liveVehicleResp.Body.Close()
	if liveVehicleResp.StatusCode != http.StatusOK {
		t.Fatalf("live vehicle status = %d, want 200", liveVehicleResp.StatusCode)
	}

	sightingsResp, err := http.Get(ts.URL + "/api/v1/public/sightings")
	if err != nil {
		t.Fatalf("GET sightings error = %v", err)
	}
	defer sightingsResp.Body.Close()
	var payload domain.VisibleSightings
	if err := json.NewDecoder(sightingsResp.Body).Decode(&payload); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if len(payload.VehicleSightings) != 2 {
		t.Fatalf("len(payload.VehicleSightings) = %d, want 2", len(payload.VehicleSightings))
	}
	var sawLiveStop bool
	for _, item := range payload.VehicleSightings {
		if item.StopID == "754" && item.RouteLabel == "10" {
			sawLiveStop = true
			break
		}
	}
	if !sawLiveStop {
		t.Fatalf("expected live stop id 754 in payload.VehicleSightings, got %#v", payload.VehicleSightings)
	}
}

func TestHealthAndCatalogExposeReleaseMetadata(t *testing.T) {
	ctx := context.Background()
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	secretPath := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(secretPath, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	now := time.Date(2026, 3, 10, 18, 55, 0, 0, time.UTC)
	testCatalog := &domain.Catalog{
		GeneratedAt: now.Add(-45 * time.Minute),
		Stops:       []domain.Stop{{ID: "3012", Name: "Centrāltirgus", Latitude: 56.94, Longitude: 24.12}},
		Routes:      []domain.Route{{Label: "22", Mode: "bus", Name: "Lidosta"}},
	}
	catalogJSON, err := json.Marshal(testCatalog)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	sum := sha256.Sum256(catalogJSON)
	catalogReader := staticCatalog{
		catalog: testCatalog,
		status: runtime.CatalogStatus{
			Loaded:             true,
			LoadedFromFallback: true,
			GeneratedAt:        testCatalog.GeneratedAt,
			LastRefreshAttempt: now.Add(-5 * time.Minute),
			LastRefreshError:   "upstream timeout",
			StopCount:          len(testCatalog.Stops),
			RouteCount:         len(testCatalog.Routes),
		},
		catalogJSON: catalogJSON,
		etag:        `"` + hex.EncodeToString(sum[:]) + `"`,
	}
	runtimeState := runtime.New(now.Add(-2*time.Hour), true, "127.0.0.1:9318")
	runtimeState.UpdateCatalog(catalogReader.status)
	runtimeState.RecordTelegramError(now.Add(-30*time.Second), "telegram timeout")
	runtimeState.RecordDumpError(now.Add(-20*time.Second), "send failed", 3)
	runtimeState.SetWebListening(true)

	cfg := config.Config{
		BotToken:                         "bot-token",
		SatiksmeWebEnabled:               true,
		SatiksmeWebBindAddr:              "127.0.0.1",
		SatiksmeWebPort:                  9318,
		SatiksmeWebPublicBaseURL:         "https://satiksme-bot.jolkins.id.lv",
		SatiksmeWebSessionSecretFile:     secretPath,
		SatiksmeWebTelegramAuthMaxAgeSec: 300,
		LiveDeparturesURL:                "https://saraksti.rigassatiksme.lv/departures2.php",
		CatalogRefreshHours:              24,
	}
	server, err := NewServer(cfg, catalogReader, reports.NewService(st, 3*time.Minute, 90*time.Second, 30*time.Minute), nil, st, runtimeState, time.UTC)
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}

	healthReq := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	healthRec := httptest.NewRecorder()
	server.ServeHTTP(healthRec, healthReq)
	if healthRec.Code != http.StatusOK {
		t.Fatalf("health status = %d, want 200", healthRec.Code)
	}
	if healthRec.Header().Get("X-Satiksme-Bot-Instance") == "" {
		t.Fatalf("missing X-Satiksme-Bot-Instance header")
	}
	if healthRec.Header().Get("X-Satiksme-Bot-App-Js") == "" || healthRec.Header().Get("X-Satiksme-Bot-App-Css") == "" {
		t.Fatalf("missing asset hash headers")
	}

	var health map[string]any
	if err := json.Unmarshal(healthRec.Body.Bytes(), &health); err != nil {
		t.Fatalf("Unmarshal(health) error = %v", err)
	}
	if health["ok"] != true {
		t.Fatalf("health ok = %#v, want true", health["ok"])
	}
	if health["degraded"] != true {
		t.Fatalf("health degraded = %#v, want true", health["degraded"])
	}
	runtimePayload := health["runtime"].(map[string]any)
	if runtimePayload["instanceId"] != healthRec.Header().Get("X-Satiksme-Bot-Instance") {
		t.Fatalf("runtime.instanceId = %#v, want %q", runtimePayload["instanceId"], healthRec.Header().Get("X-Satiksme-Bot-Instance"))
	}
	catalogPayload := health["catalog"].(map[string]any)
	if catalogPayload["loadedFromFallback"] != true {
		t.Fatalf("catalog.loadedFromFallback = %#v, want true", catalogPayload["loadedFromFallback"])
	}
	if catalogPayload["lastRefreshError"] != "upstream timeout" {
		t.Fatalf("catalog.lastRefreshError = %#v", catalogPayload["lastRefreshError"])
	}
	telegramPayload := health["telegram"].(map[string]any)
	if telegramPayload["consecutiveErrors"] != float64(1) {
		t.Fatalf("telegram.consecutiveErrors = %#v, want 1", telegramPayload["consecutiveErrors"])
	}
	dumpPayload := health["reportDump"].(map[string]any)
	if dumpPayload["pending"] != float64(3) {
		t.Fatalf("reportDump.pending = %#v, want 3", dumpPayload["pending"])
	}
	liveDepartures := health["liveDepartures"].(map[string]any)
	if liveDepartures["mode"] != "browser_direct" {
		t.Fatalf("liveDepartures.mode = %#v, want browser_direct", liveDepartures["mode"])
	}

	catalogReq := httptest.NewRequest(http.MethodGet, "/api/v1/public/catalog", nil)
	catalogRec := httptest.NewRecorder()
	server.ServeHTTP(catalogRec, catalogReq)
	if catalogRec.Code != http.StatusOK {
		t.Fatalf("catalog status = %d, want 200", catalogRec.Code)
	}
	if catalogRec.Header().Get("ETag") != catalogReader.etag {
		t.Fatalf("catalog ETag = %q, want %q", catalogRec.Header().Get("ETag"), catalogReader.etag)
	}
	if !bytes.Equal(catalogRec.Body.Bytes(), catalogJSON) {
		t.Fatalf("catalog body mismatch")
	}

	notModifiedReq := httptest.NewRequest(http.MethodGet, "/api/v1/public/catalog", nil)
	notModifiedReq.Header.Set("If-None-Match", catalogReader.etag)
	notModifiedRec := httptest.NewRecorder()
	server.ServeHTTP(notModifiedRec, notModifiedReq)
	if notModifiedRec.Code != http.StatusNotModified {
		t.Fatalf("conditional catalog status = %d, want 304", notModifiedRec.Code)
	}
}

func TestLiveDeparturesProxyEndpoint(t *testing.T) {
	ctx := context.Background()
	loc, err := time.LoadLocation("Europe/Riga")
	if err != nil {
		t.Fatalf("LoadLocation() error = %v", err)
	}
	st, err := store.NewSQLiteStore(filepath.Join(t.TempDir(), "satiksme.db"))
	if err != nil {
		t.Fatalf("NewSQLiteStore() error = %v", err)
	}
	defer st.Close()
	if err := st.Migrate(ctx); err != nil {
		t.Fatalf("Migrate() error = %v", err)
	}

	secretPath := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(secretPath, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	now := time.Now().In(loc).Truncate(time.Second)
	departureAt := now.Add(5*time.Minute + 20*time.Second)
	departureSeconds := departureAt.Hour()*3600 + departureAt.Minute()*60 + departureAt.Second()
	testCatalog := &domain.Catalog{
		GeneratedAt: now.Add(-5 * time.Minute),
		Stops:       []domain.Stop{{ID: "3012", Name: "Centrāltirgus", Latitude: 56.94, Longitude: 24.12}},
		Routes:      []domain.Route{{Label: "1", Mode: "tram", Name: "Imanta"}},
	}
	catalogJSON, err := json.Marshal(testCatalog)
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	sum := sha256.Sum256(catalogJSON)
	catalogReader := staticCatalog{
		catalog: testCatalog,
		status: runtime.CatalogStatus{
			Loaded:             true,
			GeneratedAt:        testCatalog.GeneratedAt,
			LastRefreshAttempt: now.Add(-5 * time.Minute),
			LastRefreshSuccess: now.Add(-5 * time.Minute),
			StopCount:          len(testCatalog.Stops),
			RouteCount:         len(testCatalog.Routes),
		},
		catalogJSON: catalogJSON,
		etag:        `"` + hex.EncodeToString(sum[:]) + `"`,
	}
	runtimeState := runtime.New(now.Add(-time.Hour), true, "127.0.0.1:9318")
	runtimeState.UpdateCatalog(catalogReader.status)
	runtimeState.SetWebListening(true)

	liveServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if r.URL.Query().Get("stopid") != "3012" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte(fmt.Sprintf("stop,3012\ntram,1,b-a,%d,35119,Imanta\n", departureSeconds)))
	}))
	defer liveServer.Close()

	cfg := config.Config{
		BotToken:                         "bot-token",
		SatiksmeWebEnabled:               true,
		SatiksmeWebDirectProxyEnabled:    true,
		SatiksmeWebBindAddr:              "127.0.0.1",
		SatiksmeWebPort:                  9318,
		SatiksmeWebPublicBaseURL:         "https://satiksme-bot.jolkins.id.lv",
		SatiksmeWebSessionSecretFile:     secretPath,
		SatiksmeWebTelegramAuthMaxAgeSec: 300,
		LiveDeparturesURL:                liveServer.URL,
		CatalogRefreshHours:              24,
		HTTPTimeoutSec:                   20,
	}
	server, err := NewServer(cfg, catalogReader, reports.NewService(st, 3*time.Minute, 90*time.Second, 30*time.Minute), nil, st, runtimeState, loc)
	if err != nil {
		t.Fatalf("NewServer() error = %v", err)
	}

	ts := httptest.NewServer(server)
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/api/v1/live/departures?stopId=3012")
	if err != nil {
		t.Fatalf("GET /api/v1/live/departures error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("live departures status = %d, want 200", resp.StatusCode)
	}
	var payload struct {
		StopID     string `json:"stopId"`
		Departures []struct {
			Mode             string `json:"mode"`
			RouteLabel       string `json:"routeLabel"`
			Direction        string `json:"direction"`
			DepartureClock   string `json:"departureClock"`
			DepartureSeconds int    `json:"departureSeconds"`
			LiveRowID        string `json:"liveRowId"`
			Destination      string `json:"destination"`
			MinutesAway      int    `json:"minutesAway"`
		} `json:"departures"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		t.Fatalf("decode payload error = %v", err)
	}
	if payload.StopID != "3012" {
		t.Fatalf("payload.stopId = %q, want 3012", payload.StopID)
	}
	if len(payload.Departures) != 1 {
		t.Fatalf("payload.departures length = %d, want 1", len(payload.Departures))
	}
	if payload.Departures[0].Mode != "tram" {
		t.Fatalf("departures[0].mode = %q, want tram", payload.Departures[0].Mode)
	}
	if payload.Departures[0].RouteLabel != "1" {
		t.Fatalf("departures[0].routeLabel = %q, want 1", payload.Departures[0].RouteLabel)
	}
	if got, want := payload.Departures[0].DepartureClock, departureAt.Format("15:04"); got != want {
		t.Fatalf("departures[0].departureClock = %q, want %q", got, want)
	}
	if got, want := payload.Departures[0].MinutesAway, 5; got != want {
		t.Fatalf("departures[0].minutesAway = %d, want %d", got, want)
	}

	healthReq := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	healthRec := httptest.NewRecorder()
	server.ServeHTTP(healthRec, healthReq)
	var health map[string]any
	if err := json.Unmarshal(healthRec.Body.Bytes(), &health); err != nil {
		t.Fatalf("decode health error = %v", err)
	}
	liveDepartures := health["liveDepartures"].(map[string]any)
	if liveDepartures["mode"] != "proxy" {
		t.Fatalf("liveDepartures.mode = %#v, want proxy", liveDepartures["mode"])
	}
}

func signedInitData(t *testing.T, botToken string, userID int64, now time.Time) string {
	t.Helper()
	userRaw, _ := json.Marshal(map[string]any{
		"id":         userID,
		"first_name": "Tester",
	})
	values := url.Values{}
	values.Set("auth_date", strconv.FormatInt(now.Unix(), 10))
	values.Set("query_id", "query-id")
	values.Set("user", string(userRaw))
	keys := []string{"auth_date", "query_id", "user"}
	dataCheck := ""
	for i, key := range keys {
		if i > 0 {
			dataCheck += "\n"
		}
		dataCheck += key + "=" + values.Get(key)
	}
	secret := hmacSHA256([]byte("WebAppData"), []byte(botToken))
	hash := hmac.New(sha256.New, secret)
	_, _ = hash.Write([]byte(dataCheck))
	values.Set("hash", hex.EncodeToString(hash.Sum(nil)))
	return values.Encode()
}
