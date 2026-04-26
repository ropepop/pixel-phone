package web

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"satiksmebot/internal/bot"
	"satiksmebot/internal/config"
	"satiksmebot/internal/domain"
	"satiksmebot/internal/live"
	"satiksmebot/internal/reports"
	"satiksmebot/internal/runtime"
	"satiksmebot/internal/store"
	"satiksmebot/internal/version"
)

//go:embed static/*
var staticFS embed.FS

type CatalogReader interface {
	Current() *domain.Catalog
	Status() runtime.CatalogStatus
}

type catalogStopFinder interface {
	FindStop(stopID string) (domain.Stop, bool)
}

type catalogPayloadReader interface {
	CatalogJSON() []byte
	CatalogETag() string
}

type Server struct {
	cfg            config.Config
	reports        *reports.Service
	catalog        CatalogReader
	store          store.Store
	dump           *bot.DumpDispatcher
	runtimeState   *runtime.State
	release        releaseInfo
	loc            *time.Location
	liveHTTPClient *http.Client
	pathPrefix     string
	sessionSecret  []byte
	static         fs.FS
	pageTemplate   *template.Template
}

type pageData struct {
	AppCSSURL string
	AppJSURL  string
	ConfigJS  template.JS
}

func NewServer(
	cfg config.Config,
	catalog CatalogReader,
	reportsSvc *reports.Service,
	dump *bot.DumpDispatcher,
	st store.Store,
	runtimeState *runtime.State,
	loc *time.Location,
) (*Server, error) {
	pathPrefix := ""
	if strings.TrimSpace(cfg.SatiksmeWebPublicBaseURL) != "" {
		parsed, err := url.Parse(cfg.SatiksmeWebPublicBaseURL)
		if err != nil {
			return nil, fmt.Errorf("parse SATIKSME_WEB_PUBLIC_BASE_URL: %w", err)
		}
		if strings.TrimSpace(parsed.Path) != "" && parsed.Path != "/" {
			pathPrefix = strings.TrimRight(parsed.Path, "/")
		}
	}

	static := mustStaticSubFS()
	release, err := newReleaseInfo(static)
	if err != nil {
		return nil, err
	}

	server := &Server{
		cfg:            cfg,
		reports:        reportsSvc,
		catalog:        catalog,
		store:          st,
		dump:           dump,
		runtimeState:   runtimeState,
		release:        release,
		loc:            loc,
		liveHTTPClient: &http.Client{Timeout: time.Duration(cfg.HTTPTimeoutSec) * time.Second},
		pathPrefix:     pathPrefix,
		static:         static,
		pageTemplate: template.Must(template.New("shell").Parse(`<!doctype html>
<html lang="lv">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>satiksmes bots</title>
  <link rel="stylesheet" href="{{.AppCSSURL}}">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin="">
  <script>window.SATIKSME_APP_CONFIG = {{.ConfigJS}};</script>
  <script src="https://telegram.org/js/telegram-web-app.js"></script>
  <script defer src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
  <script defer src="{{.AppJSURL}}"></script>
</head>
<body>
  <div id="app"></div>
</body>
</html>`)),
	}
	if cfg.SatiksmeWebEnabled {
		secret, secretErr := loadSessionSecret(cfg.SatiksmeWebSessionSecretFile)
		if secretErr != nil {
			return nil, secretErr
		}
		server.sessionSecret = secret
	}
	return server, nil
}

func mustStaticSubFS() fs.FS {
	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		panic(err)
	}
	return sub
}

func (s *Server) AppURL() string {
	if !s.cfg.SatiksmeWebEnabled {
		return ""
	}
	return strings.TrimRight(s.cfg.SatiksmeWebPublicBaseURL, "/") + "/app"
}

func (s *Server) PublicURL() string {
	return strings.TrimRight(s.cfg.SatiksmeWebPublicBaseURL, "/")
}

func (s *Server) Run(ctx context.Context) error {
	if !s.cfg.SatiksmeWebEnabled {
		return nil
	}
	httpServer := &http.Server{
		Addr:              net.JoinHostPort(s.cfg.SatiksmeWebBindAddr, strconv.Itoa(s.cfg.SatiksmeWebPort)),
		Handler:           s,
		ReadHeaderTimeout: 10 * time.Second,
	}
	listener, err := net.Listen("tcp", httpServer.Addr)
	if err != nil {
		return fmt.Errorf("listen satiksme web server: %w", err)
	}
	if s.runtimeState != nil {
		s.runtimeState.SetWebListening(true)
		defer s.runtimeState.SetWebListening(false)
	}
	errCh := make(chan error, 1)
	go func() {
		errCh <- httpServer.Serve(listener)
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdownCtx)
		err := <-errCh
		if errors.Is(err, http.ErrServerClosed) || err == nil {
			return nil
		}
		return err
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.setReleaseHeaders(w)
	path := strings.TrimRight(r.URL.Path, "/")
	basePath := strings.TrimRight(s.pathPrefix, "/")
	switch {
	case path == basePath || path == "":
		s.serveShell(w, "public")
	case path == basePath+"/app":
		s.serveShell(w, "mini-app")
	case strings.HasPrefix(path, basePath+"/assets/"):
		s.serveAsset(w, r, basePath)
	case strings.HasPrefix(path, basePath+"/api/v1/"):
		s.handleAPI(w, r, strings.TrimPrefix(path, basePath+"/api/v1"))
	default:
		s.setNoStoreHeaders(w)
		http.NotFound(w, r)
	}
}

func (s *Server) serveShell(w http.ResponseWriter, mode string) {
	s.setNoStoreHeaders(w)
	basePath := strings.TrimRight(s.pathPrefix, "/")
	cfg := map[string]any{
		"basePath":                    basePath,
		"publicBaseURL":               s.cfg.SatiksmeWebPublicBaseURL,
		"language":                    defaultAppLanguage,
		"mode":                        mode,
		"reportsChannelURL":           s.cfg.ReportsChannelURL,
		"liveDeparturesURL":           s.cfg.LiveDeparturesURL,
		"liveDeparturesMode":          s.liveDeparturesMode(),
		"liveDeparturesProxyEndpoint": "/api/v1/live/departures",
		"publicSightingsURL":          basePath + "/api/v1/public/sightings",
	}
	raw, _ := json.Marshal(cfg)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = s.pageTemplate.Execute(w, pageData{
		AppCSSURL: s.release.AssetURL(basePath, "app.css"),
		AppJSURL:  s.release.AssetURL(basePath, "app.js"),
		ConfigJS:  template.JS(raw),
	})
}

func (s *Server) serveAsset(w http.ResponseWriter, r *http.Request, basePath string) {
	assetPath := strings.TrimPrefix(strings.TrimPrefix(r.URL.Path, basePath), "/assets/")
	version := strings.TrimSpace(r.URL.Query().Get("v"))
	if version != "" && version == s.release.AssetHash(assetPath) {
		s.setImmutableHeaders(w)
	} else {
		s.setNoStoreHeaders(w)
	}
	http.StripPrefix(basePath+"/assets/", http.FileServer(http.FS(s.static))).ServeHTTP(w, r)
}

func (s *Server) handleAPI(w http.ResponseWriter, r *http.Request, route string) {
	now := time.Now().In(s.loc)
	switch route {
	case "/health":
		s.handleHealth(w, r)
	case "/live/departures":
		s.handleLiveDepartures(w, r, now)
	case "/public/catalog":
		s.handlePublicCatalog(w, r)
	case "/public/sightings":
		s.handlePublicSightings(w, r, now)
	case "/public/map":
		s.handlePublicMap(w, r, now)
	case "/public/live-vehicles":
		s.handlePublicLiveVehicles(w, r, now)
	case "/auth/telegram":
		s.handleAuthTelegram(w, r, now)
	case "/me":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.setNoStoreHeaders(w)
		writeJSON(w, http.StatusOK, map[string]any{"userId": claims.UserID, "language": claims.Language})
	case "/reports/stop":
		s.handleStopReport(w, r, now)
	case "/reports/vehicle":
		s.handleVehicleReport(w, r, now)
	default:
		s.setNoStoreHeaders(w)
		http.NotFound(w, r)
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.setNoStoreHeaders(w)
	now := time.Now().UTC()
	catalogStatus := runtime.CatalogStatus{}
	if s.catalog != nil {
		catalogStatus = s.catalog.Status()
	}
	stale := s.catalogStale(catalogStatus, now)
	dbWritable, dbError := s.dbStatus(r.Context())
	var telegramStatus runtime.TelegramStatus
	var dumpStatus runtime.DumpStatus
	startedAt := time.Time{}
	lastFatalError := ""
	webEnabled := s.cfg.SatiksmeWebEnabled
	webListening := false
	webBindAddr := net.JoinHostPort(s.cfg.SatiksmeWebBindAddr, strconv.Itoa(s.cfg.SatiksmeWebPort))
	if s.runtimeState != nil {
		telegramStatus = s.runtimeState.TelegramStatus()
		dumpStatus = s.runtimeState.DumpStatus()
		startedAt = s.runtimeState.StartedAt()
		lastFatalError = s.runtimeState.LastFatalError()
		webEnabled, webListening, webBindAddr = s.runtimeState.WebStatus()
	}

	reasons := make([]string, 0, 6)
	ok := true
	if !catalogStatus.Loaded {
		ok = false
		reasons = append(reasons, "catalog_unavailable")
	}
	if !dbWritable {
		ok = false
		reasons = append(reasons, "db_unwritable")
	}
	if webEnabled && !webListening {
		ok = false
		reasons = append(reasons, "web_not_listening")
	}
	if strings.TrimSpace(lastFatalError) != "" {
		ok = false
		reasons = append(reasons, "fatal_error")
	}
	if strings.TrimSpace(catalogStatus.LastRefreshError) != "" {
		reasons = append(reasons, "catalog_refresh_error")
	}
	if catalogStatus.LoadedFromFallback {
		reasons = append(reasons, "catalog_fallback")
	}
	if stale {
		reasons = append(reasons, "catalog_stale")
	}
	if telegramStatus.ConsecutiveErrors > 0 {
		reasons = append(reasons, "telegram_errors")
	}
	if dumpStatus.LastError != "" {
		reasons = append(reasons, "report_dump_error")
	}
	if dumpStatus.Pending > 0 {
		reasons = append(reasons, "report_dump_pending")
	}

	uptimeSeconds := int64(0)
	if !startedAt.IsZero() {
		uptimeSeconds = int64(now.Sub(startedAt).Seconds())
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       ok,
		"degraded": len(reasons) > 0,
		"reasons":  reasons,
		"version": map[string]any{
			"display":   version.Display(),
			"commit":    s.release.Commit,
			"buildTime": s.release.BuildTime,
			"dirty":     s.release.Dirty,
		},
		"runtime": map[string]any{
			"instanceId":     s.release.Instance,
			"startedAt":      optionalTimeValue(startedAt),
			"uptimeSeconds":  uptimeSeconds,
			"lastFatalError": emptyToNil(lastFatalError),
		},
		"assets": map[string]any{
			"appJsSha256":  s.release.AppJSHash,
			"appCssSha256": s.release.AppCSSHash,
		},
		"catalog": map[string]any{
			"loaded":             catalogStatus.Loaded,
			"loadedFromFallback": catalogStatus.LoadedFromFallback,
			"generatedAt":        optionalTimeValue(catalogStatus.GeneratedAt),
			"lastRefreshAttempt": optionalTimeValue(catalogStatus.LastRefreshAttempt),
			"lastRefreshSuccess": optionalTimeValue(catalogStatus.LastRefreshSuccess),
			"lastRefreshError":   emptyToNil(catalogStatus.LastRefreshError),
			"stopCount":          catalogStatus.StopCount,
			"routeCount":         catalogStatus.RouteCount,
			"stale":              stale,
			"ageSeconds":         optionalAgeSeconds(catalogStatus.GeneratedAt, now),
		},
		"telegram": map[string]any{
			"lastSuccessAt":     optionalTimeValue(telegramStatus.LastSuccessAt),
			"lastErrorAt":       optionalTimeValue(telegramStatus.LastErrorAt),
			"consecutiveErrors": telegramStatus.ConsecutiveErrors,
			"lastError":         emptyToNil(telegramStatus.LastError),
			"lastUpdateId":      telegramStatus.LastUpdateID,
		},
		"reportDump": map[string]any{
			"pending":       dumpStatus.Pending,
			"lastSuccessAt": optionalTimeValue(dumpStatus.LastSuccessAt),
			"lastAttemptAt": optionalTimeValue(dumpStatus.LastAttemptAt),
			"lastError":     emptyToNil(dumpStatus.LastError),
		},
		"db": map[string]any{
			"writable": dbWritable,
			"error":    emptyToNil(dbError),
		},
		"web": map[string]any{
			"enabled":       webEnabled,
			"listening":     webListening,
			"bindAddr":      webBindAddr,
			"publicBaseUrl": s.PublicURL(),
		},
		"liveDepartures": map[string]any{
			"mode":    s.liveDeparturesMode(),
			"baseUrl": s.cfg.LiveDeparturesURL,
		},
		"catalogStops": catalogStatus.StopCount,
	})
}

func (s *Server) liveDeparturesMode() string {
	if s.cfg.SatiksmeWebDirectProxyEnabled {
		return "proxy"
	}
	return "browser_direct"
}

func (s *Server) handleLiveDepartures(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	if !s.cfg.SatiksmeWebDirectProxyEnabled {
		writeError(w, http.StatusServiceUnavailable, "proxy mode disabled")
		return
	}
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	stopID := strings.TrimSpace(r.URL.Query().Get("stopId"))
	if stopID == "" {
		writeError(w, http.StatusBadRequest, "missing stopId")
		return
	}
	parsed, err := url.Parse(s.cfg.LiveDeparturesURL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "invalid live departures url")
		return
	}
	query := parsed.Query()
	query.Set("stopid", stopID)
	parsed.RawQuery = query.Encode()

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, parsed.String(), nil)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "prepare live departures request")
		return
	}
	response, err := s.liveHTTPClient.Do(req)
	if err != nil {
		writeError(w, http.StatusBadGateway, "upstream live departures request failed")
		return
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		errorBody, _ := io.ReadAll(response.Body)
		msg := "upstream live departures failed"
		if len(errorBody) > 0 {
			msg = string(errorBody)
		}
		writeError(w, http.StatusBadGateway, strings.TrimSpace(msg))
		return
	}
	upstreamStopID, rows, err := live.Parse(response.Body, now, s.loc)
	if err != nil {
		writeError(w, http.StatusBadGateway, "live departures parse failed")
		return
	}
	departures := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		departures = append(departures, map[string]any{
			"mode":             row.Mode,
			"routeLabel":       row.RouteLabel,
			"direction":        row.Direction,
			"departureSeconds": row.DepartureSeconds,
			"liveRowId":        row.LiveRowID,
			"destination":      row.Destination,
			"departureClock":   row.ArrivalAt.In(s.loc).Format("15:04"),
			"minutesAway":      row.CountdownMins,
		})
	}
	responsePayload := map[string]any{
		"stopId":     upstreamStopID,
		"departures": departures,
	}
	writeJSON(w, http.StatusOK, responsePayload)
}

func (s *Server) handlePublicCatalog(w http.ResponseWriter, r *http.Request) {
	s.setNoStoreHeaders(w)
	catalog := s.catalog.Current()
	if catalog == nil {
		writeError(w, http.StatusServiceUnavailable, "catalog unavailable")
		return
	}
	if payloadReader, ok := s.catalog.(catalogPayloadReader); ok {
		if etag := payloadReader.CatalogETag(); etag != "" {
			if strings.TrimSpace(r.Header.Get("If-None-Match")) == etag {
				w.WriteHeader(http.StatusNotModified)
				return
			}
			w.Header().Set("ETag", etag)
		}
		if payload := payloadReader.CatalogJSON(); len(payload) > 0 {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(payload)
			return
		}
	}
	writeJSON(w, http.StatusOK, catalog)
}

func (s *Server) handlePublicSightings(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	catalog := s.catalog.Current()
	if catalog == nil {
		writeError(w, http.StatusServiceUnavailable, "catalog unavailable")
		return
	}
	stopID := strings.TrimSpace(r.URL.Query().Get("stopId"))
	limit := parseSightingsLimit(r, 100)
	visible, err := s.reports.VisibleSightings(r.Context(), catalog, stopID, now, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, visible)
}

func (s *Server) handlePublicMap(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	catalog := s.catalog.Current()
	if catalog == nil {
		writeError(w, http.StatusServiceUnavailable, "catalog unavailable")
		return
	}
	visible, err := s.reports.VisibleSightings(r.Context(), catalog, "", now, parseSightingsLimit(r, 300))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	liveVehicles, _ := s.publicLiveVehicles(r.Context(), catalog, visible, now)
	writeJSON(w, http.StatusOK, domain.PublicMapPayload{
		GeneratedAt:  catalog.GeneratedAt,
		Stops:        catalog.Stops,
		Sightings:    visible,
		LiveVehicles: liveVehicles,
	})
}

func (s *Server) handlePublicLiveVehicles(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	catalog := s.catalog.Current()
	if catalog == nil {
		writeError(w, http.StatusServiceUnavailable, "catalog unavailable")
		return
	}
	visible, err := s.reports.VisibleSightings(r.Context(), catalog, "", now, parseSightingsLimit(r, 300))
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	liveVehicles, err := s.publicLiveVehicles(r.Context(), catalog, visible, now)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"liveVehicles": liveVehicles,
	})
}

func (s *Server) publicLiveVehicles(ctx context.Context, catalog *domain.Catalog, visible domain.VisibleSightings, now time.Time) ([]domain.LiveVehicle, error) {
	liveVehicles, err := live.FetchVehicles(ctx, s.liveHTTPClient, "", catalog, now)
	if err != nil {
		return nil, err
	}
	live.ApplyVehicleSightingCounts(liveVehicles, visible.VehicleSightings)
	return liveVehicles, nil
}

func (s *Server) handleAuthTelegram(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var payload struct {
		InitData string `json:"initData"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	auth, err := validateTelegramInitData(payload.InitData, s.cfg.BotToken, time.Duration(s.cfg.SatiksmeWebTelegramAuthMaxAgeSec)*time.Second, now)
	if err != nil {
		writeError(w, http.StatusUnauthorized, err.Error())
		return
	}
	cookie, err := issueSessionCookie(s.sessionSecret, auth, now)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	http.SetCookie(w, cookie)
	writeJSON(w, http.StatusOK, map[string]any{
		"userId":    auth.User.ID,
		"firstName": auth.User.FirstName,
		"language":  sessionLanguageCode(auth.User.LanguageCode),
	})
}

func (s *Server) handleStopReport(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	claims, ok := s.requireSession(w, r, now)
	if !ok {
		return
	}
	var payload struct {
		StopID string `json:"stopId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	catalog := s.catalog.Current()
	stop, ok := s.findStop(catalog, payload.StopID)
	if !ok {
		writeError(w, http.StatusBadRequest, "unknown stop")
		return
	}
	result, item, err := s.reports.SubmitStopSighting(r.Context(), claims.UserID, payload.StopID, now)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if result.Accepted && s.dump != nil {
		s.dump.EnqueueStop(stop, item)
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleVehicleReport(w http.ResponseWriter, r *http.Request, now time.Time) {
	s.setNoStoreHeaders(w)
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	claims, ok := s.requireSession(w, r, now)
	if !ok {
		return
	}
	var payload domain.VehicleReportInput
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	payload.StopID = strings.TrimSpace(payload.StopID)
	if payload.StopID == "" {
		writeError(w, http.StatusBadRequest, "stopId is required")
		return
	}
	catalog := s.catalog.Current()
	stop, ok := s.findStop(catalog, payload.StopID)
	if !ok {
		stop = domain.Stop{ID: payload.StopID}
	}
	if strings.TrimSpace(payload.Mode) == "" || strings.TrimSpace(payload.RouteLabel) == "" || strings.TrimSpace(payload.Destination) == "" {
		writeError(w, http.StatusBadRequest, "mode, routeLabel, and destination are required")
		return
	}
	result, item, err := s.reports.SubmitVehicleSighting(r.Context(), claims.UserID, payload, now)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if result.Accepted && s.dump != nil {
		s.dump.EnqueueVehicle(stop, item)
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) requireSession(w http.ResponseWriter, r *http.Request, now time.Time) (sessionClaims, bool) {
	cookie, err := r.Cookie(sessionCookieName)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "missing session")
		return sessionClaims{}, false
	}
	claims, err := parseSession(s.sessionSecret, cookie.Value, now)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid session")
		return sessionClaims{}, false
	}
	return claims, true
}

func (s *Server) findStop(catalog *domain.Catalog, stopID string) (domain.Stop, bool) {
	if finder, ok := s.catalog.(catalogStopFinder); ok {
		if stop, found := finder.FindStop(stopID); found {
			return stop, true
		}
	}
	return findStop(catalog, stopID)
}

func findStop(catalog *domain.Catalog, stopID string) (domain.Stop, bool) {
	return domain.FindStopByAnyID(catalog, stopID)
}

func parseSightingsLimit(r *http.Request, fallback int) int {
	limit := fallback
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 && parsed <= 500 {
			limit = parsed
		}
	}
	return limit
}

func (s *Server) dbStatus(ctx context.Context) (bool, string) {
	if s.store == nil {
		return false, "store unavailable"
	}
	checkCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := s.store.HealthCheck(checkCtx); err != nil {
		return false, err.Error()
	}
	return true, ""
}

func (s *Server) catalogStale(status runtime.CatalogStatus, now time.Time) bool {
	if !status.Loaded || status.GeneratedAt.IsZero() {
		return false
	}
	refreshAfter := time.Duration(s.cfg.CatalogRefreshHours) * time.Hour
	if refreshAfter <= 0 {
		refreshAfter = 24 * time.Hour
	}
	return now.After(status.GeneratedAt.Add(refreshAfter * 2))
}

func (s *Server) setReleaseHeaders(w http.ResponseWriter) {
	w.Header().Set("X-Satiksme-Bot-Commit", s.release.Commit)
	w.Header().Set("X-Satiksme-Bot-Build-Time", s.release.BuildTime)
	w.Header().Set("X-Satiksme-Bot-Instance", s.release.Instance)
	w.Header().Set("X-Satiksme-Bot-App-Js", s.release.AppJSHash)
	w.Header().Set("X-Satiksme-Bot-App-Css", s.release.AppCSSHash)
}

func (s *Server) setNoStoreHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
	w.Header().Set("CDN-Cache-Control", "no-store")
	w.Header().Set("Cloudflare-CDN-Cache-Control", "no-store")
}

func (s *Server) setImmutableHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("CDN-Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("Cloudflare-CDN-Cache-Control", "public, max-age=31536000, immutable")
}

func optionalTimeValue(at time.Time) any {
	if at.IsZero() {
		return nil
	}
	return at.UTC()
}

func optionalAgeSeconds(at, now time.Time) any {
	if at.IsZero() {
		return nil
	}
	seconds := int(now.Sub(at.UTC()).Seconds())
	if seconds < 0 {
		return 0
	}
	return seconds
}

func emptyToNil(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return value
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
