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

	trainapp "telegramtrainapp/internal/app"
	"telegramtrainapp/internal/config"
	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
	"telegramtrainapp/internal/reports"
	"telegramtrainapp/internal/schedule"
)

//go:embed static/*
var staticFS embed.FS

type RideNotifier interface {
	NotifyRideUsers(ctx context.Context, reporterID int64, trainID string, signal domain.SignalType, now time.Time) error
	NotifyStationSighting(ctx context.Context, event domain.StationSighting, now time.Time) error
}

type Server struct {
	cfg           config.Config
	app           *trainapp.Service
	catalog       *i18n.Catalog
	loc           *time.Location
	pathPrefix    string
	sessionSecret []byte
	notifier      RideNotifier
	static        fs.FS
	release       releaseInfo
	pageTemplate  *template.Template
}

type pageData struct {
	BasePath                string
	PublicBaseURL           string
	Mode                    string
	TrainID                 string
	StationCheckin          bool
	MiniAppRefreshMs        int
	PublicRefreshMs         int
	ExternalTrainMapEnabled bool
	ExternalTrainMapBaseURL string
	ExternalTrainMapWsURL   string
	ExternalTrainGraphURL   string
	AppCSSURL               string
	LeafletCSSURL           string
	LeafletJSURL            string
	ExternalFeedJSURL       string
	AppJSURL                string
}

func NewServer(cfg config.Config, appSvc *trainapp.Service, catalog *i18n.Catalog, loc *time.Location) (*Server, error) {
	pathPrefix := "/pixel-stack/train"
	if strings.TrimSpace(cfg.TrainWebPublicBaseURL) != "" {
		parsed, err := url.Parse(cfg.TrainWebPublicBaseURL)
		if err != nil {
			return nil, fmt.Errorf("parse TRAIN_WEB_PUBLIC_BASE_URL: %w", err)
		}
		if strings.TrimSpace(parsed.Path) == "" || parsed.Path == "/" {
			pathPrefix = ""
		}
		if strings.TrimSpace(parsed.Path) != "" && parsed.Path != "/" {
			pathPrefix = strings.TrimRight(parsed.Path, "/")
		}
	}

	staticFiles := mustStaticSubFS()
	release, err := newReleaseInfo(staticFiles)
	if err != nil {
		return nil, err
	}

	server := &Server{
		cfg:        cfg,
		app:        appSvc,
		catalog:    catalog,
		loc:        loc,
		pathPrefix: pathPrefix,
		static:     staticFiles,
		release:    release,
		pageTemplate: template.Must(template.New("shell").Parse(`<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>vivi kontrole bot</title>
  <link rel="stylesheet" href="{{.AppCSSURL}}">
  <link rel="stylesheet" href="{{.LeafletCSSURL}}">
  <script>
    window.TRAIN_APP_CONFIG = {
      basePath: {{.BasePath}},
      publicBaseURL: {{.PublicBaseURL}},
      mode: {{.Mode}},
      trainId: {{.TrainID}},
      stationCheckinEnabled: {{if .StationCheckin}}true{{else}}false{{end}},
      miniAppRefreshMs: {{.MiniAppRefreshMs}},
      publicRefreshMs: {{.PublicRefreshMs}},
      externalTrainMapEnabled: {{if .ExternalTrainMapEnabled}}true{{else}}false{{end}},
      externalTrainMapBaseURL: {{.ExternalTrainMapBaseURL}},
      externalTrainMapWsURL: {{.ExternalTrainMapWsURL}},
      externalTrainGraphURL: {{.ExternalTrainGraphURL}}
    };
  </script>
  <script src="https://telegram.org/js/telegram-web-app.js"></script>
  <script defer src="{{.LeafletJSURL}}"></script>
  <script defer src="{{.ExternalFeedJSURL}}"></script>
  <script defer src="{{.AppJSURL}}"></script>
</head>
<body>
  <div id="app"></div>
</body>
</html>`)),
	}
	if cfg.TrainWebEnabled {
		secret, err := loadSessionSecret(cfg.TrainWebSessionSecretFile)
		if err != nil {
			return nil, err
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

func (s *Server) SetNotifier(notifier RideNotifier) {
	s.notifier = notifier
}

func (s *Server) AppURL() string {
	if !s.cfg.TrainWebEnabled {
		return ""
	}
	return strings.TrimRight(s.cfg.TrainWebPublicBaseURL, "/") + "/app"
}

func (s *Server) Enabled() bool {
	return s.cfg.TrainWebEnabled
}

func (s *Server) Run(ctx context.Context) error {
	if !s.cfg.TrainWebEnabled {
		return nil
	}

	httpServer := &http.Server{
		Addr:              net.JoinHostPort(s.cfg.TrainWebBindAddr, strconv.Itoa(s.cfg.TrainWebPort)),
		Handler:           s,
		ReadHeaderTimeout: 10 * time.Second,
	}

	listener, err := net.Listen("tcp", httpServer.Addr)
	if err != nil {
		return fmt.Errorf("listen train web server: %w", err)
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
	path := strings.TrimRight(r.URL.Path, "/")
	basePath := strings.TrimRight(s.pathPrefix, "/")
	s.setReleaseHeaders(w)
	switch {
	case path == basePath || path == "":
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "public-stations", ""))
	case path == basePath+"/app":
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "mini-app", ""))
	case path == basePath+"/stations":
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "public-stations", ""))
	case path == basePath+"/map":
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "public-network-map", ""))
	case path == basePath+"/departures":
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "public-dashboard", ""))
	case strings.HasPrefix(path, basePath+"/t/") && strings.HasSuffix(path, "/map"):
		trainID := strings.TrimSuffix(strings.TrimPrefix(path, basePath+"/t/"), "/map")
		trainID = strings.Trim(trainID, "/")
		if trainID == "" || strings.Contains(trainID, "/") {
			http.NotFound(w, r)
			return
		}
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "public-map", trainID))
	case strings.HasPrefix(path, basePath+"/t/"):
		trainID := strings.TrimPrefix(path, basePath+"/t/")
		if trainID == "" || strings.Contains(trainID, "/") {
			http.NotFound(w, r)
			return
		}
		s.serveShell(w, http.StatusOK, s.newPageData(basePath, "public-train", trainID))
	case strings.HasPrefix(path, basePath+"/assets/"):
		s.serveAsset(w, r, basePath)
	case strings.HasPrefix(path, basePath+"/api/v1/"):
		s.handleAPI(w, r, strings.TrimPrefix(path, basePath+"/api/v1"))
	default:
		http.NotFound(w, r)
	}
}

func (s *Server) handleAPI(w http.ResponseWriter, r *http.Request, route string) {
	now := time.Now().In(s.loc)
	switch {
	case route == "/health":
		s.handleHealth(w, r, now)
	case route == "/messages":
		s.handleMessages(w, r)
	case route == "/public/dashboard":
		s.handlePublicDashboard(w, r, now)
	case route == "/public/map":
		s.handlePublicMap(w, r, now)
	case route == "/external/train-graph":
		s.handleExternalTrainGraph(w, r)
	case route == "/public/stations":
		s.handlePublicStations(w, r, now)
	case strings.HasPrefix(route, "/public/stations/") && strings.HasSuffix(route, "/departures"):
		trimmed := strings.TrimPrefix(route, "/public/stations/")
		stationID := strings.TrimSuffix(trimmed, "/departures")
		stationID = strings.Trim(stationID, "/")
		s.handlePublicStationDepartures(w, r, stationID, now)
	case strings.HasPrefix(route, "/public/trains/") && strings.HasSuffix(route, "/stops"):
		trainID := strings.TrimSuffix(strings.TrimPrefix(route, "/public/trains/"), "/stops")
		trainID = strings.Trim(trainID, "/")
		s.handlePublicTrainStops(w, r, trainID, now)
	case strings.HasPrefix(route, "/public/trains/"):
		s.handlePublicTrain(w, r, strings.TrimPrefix(route, "/public/trains/"), now)
	case route == "/auth/telegram":
		s.handleAuthTelegram(w, r, now)
	case route == "/me":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleMe(w, r, claims, now)
	case strings.HasPrefix(route, "/windows/"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleWindowTrains(w, r, claims, strings.TrimPrefix(route, "/windows/"), now)
	case route == "/stations":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleStations(w, r, claims, now)
	case strings.HasPrefix(route, "/stations/") && strings.HasSuffix(route, "/sighting-destinations"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trimmed := strings.TrimPrefix(route, "/stations/")
		stationID := strings.TrimSuffix(trimmed, "/sighting-destinations")
		stationID = strings.Trim(stationID, "/")
		s.handleStationSightingDestinations(w, r, claims, stationID, now)
	case strings.HasPrefix(route, "/stations/") && strings.HasSuffix(route, "/departures"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trimmed := strings.TrimPrefix(route, "/stations/")
		stationID := strings.TrimSuffix(trimmed, "/departures")
		stationID = strings.Trim(stationID, "/")
		s.handleStationDepartures(w, r, claims, stationID, now)
	case strings.HasPrefix(route, "/stations/") && strings.HasSuffix(route, "/sightings"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trimmed := strings.TrimPrefix(route, "/stations/")
		stationID := strings.TrimSuffix(trimmed, "/sightings")
		stationID = strings.Trim(stationID, "/")
		s.handleStationSighting(w, r, claims, stationID, now)
	case route == "/routes/destinations":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleRouteDestinations(w, r, claims, now)
	case route == "/routes/trains":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleRouteTrains(w, r, claims, now)
	case route == "/favorites":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleFavorites(w, r, claims)
	case route == "/checkins/current":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleCurrentCheckIn(w, r, claims, now)
	case route == "/checkins/current/undo":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleUndoCheckOut(w, r, claims, now)
	case strings.HasPrefix(route, "/trains/") && strings.HasSuffix(route, "/status"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trainID := strings.TrimSuffix(strings.TrimPrefix(route, "/trains/"), "/status")
		trainID = strings.Trim(trainID, "/")
		s.handleTrainStatus(w, r, claims, trainID, now)
	case strings.HasPrefix(route, "/trains/") && strings.HasSuffix(route, "/stops"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trainID := strings.TrimSuffix(strings.TrimPrefix(route, "/trains/"), "/stops")
		trainID = strings.Trim(trainID, "/")
		s.handleTrainStops(w, r, claims, trainID, now)
	case strings.HasPrefix(route, "/trains/") && strings.HasSuffix(route, "/timeline"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trainID := strings.TrimSuffix(strings.TrimPrefix(route, "/trains/"), "/timeline")
		trainID = strings.Trim(trainID, "/")
		s.handleTrainTimeline(w, r, claims, trainID, now)
	case strings.HasPrefix(route, "/trains/") && strings.HasSuffix(route, "/reports"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trainID := strings.TrimSuffix(strings.TrimPrefix(route, "/trains/"), "/reports")
		trainID = strings.Trim(trainID, "/")
		s.handleTrainReport(w, r, claims, trainID, now)
	case strings.HasPrefix(route, "/trains/") && strings.HasSuffix(route, "/mute"):
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		trainID := strings.TrimSuffix(strings.TrimPrefix(route, "/trains/"), "/mute")
		trainID = strings.Trim(trainID, "/")
		s.handleTrainMute(w, r, claims, trainID, now)
	case route == "/settings":
		claims, ok := s.requireSession(w, r, now)
		if !ok {
			return
		}
		s.handleSettings(w, r, claims)
	default:
		s.writeError(w, http.StatusNotFound, "not found")
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	available, scheduleErr := s.appScheduleAvailability()
	scheduleCtx := s.appScheduleContext(now)
	payload := map[string]any{
		"ok":                     true,
		"now":                    now.UTC().Format(time.RFC3339),
		"scheduleAvailable":      available,
		"loadedServiceDate":      s.appLoadedServiceDate(),
		"schedule":               scheduleCtx,
		"scheduleFallbackActive": scheduleCtx.FallbackActive,
		"scheduleSameDayFresh":   scheduleCtx.SameDayFresh,
		"version": map[string]any{
			"commit":    s.release.Commit,
			"buildTime": s.release.BuildTime,
			"dirty":     s.release.Dirty,
		},
		"runtime": map[string]any{
			"instanceId": s.release.Instance,
		},
		"assets": map[string]any{
			"appJsSha256":  s.release.AppJSHash,
			"appCssSha256": s.release.AppCSSHash,
		},
	}
	if scheduleErr != nil {
		payload["scheduleError"] = scheduleErr.Error()
	}
	s.writeJSON(w, http.StatusOK, payload)
}

func (s *Server) handleMessages(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	lang := trainapp.ParseLanguage(r.URL.Query().Get("lang"))
	s.writeJSON(w, http.StatusOK, map[string]any{
		"lang":     lang,
		"messages": s.catalog.Messages(lang),
	})
}

func (s *Server) handlePublicDashboard(w http.ResponseWriter, r *http.Request, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	limit := 60
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 0 {
			s.writeError(w, http.StatusBadRequest, "invalid limit")
			return
		}
		limit = parsed
	}
	items, err := s.app.PublicDashboard(r.Context(), now, limit)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"generatedAt": now.UTC(),
		"trains":      items,
		"schedule":    s.appScheduleContext(now),
	})
}

func (s *Server) handlePublicMap(w http.ResponseWriter, r *http.Request, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.NetworkMap(r.Context(), now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(item, now))
}

func (s *Server) handleExternalTrainGraph(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !s.cfg.ExternalTrainMapEnabled {
		s.writeError(w, http.StatusNotFound, "external train graph disabled")
		return
	}

	endpoint := strings.TrimRight(strings.TrimSpace(s.cfg.ExternalTrainMapBaseURL), "/") + "/api/trainGraph"
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, endpoint, nil)
	if err != nil {
		s.writeError(w, http.StatusBadGateway, "build external train graph request failed")
		return
	}
	resp, err := (&http.Client{Timeout: 12 * time.Second}).Do(req)
	if err != nil {
		s.writeError(w, http.StatusBadGateway, "fetch external train graph failed")
		return
	}
	defer resp.Body.Close()

	contentType := strings.TrimSpace(resp.Header.Get("Content-Type"))
	if contentType == "" {
		contentType = "application/json; charset=utf-8"
	}
	w.Header().Set("Content-Type", contentType)
	s.setNoStoreHeaders(w)
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func (s *Server) handlePublicStations(w http.ResponseWriter, r *http.Request, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	items, err := s.app.Stations(r.Context(), now, r.URL.Query().Get("q"))
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"stations": items,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handlePublicStationDepartures(w http.ResponseWriter, r *http.Request, stationID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.PublicStationDepartures(r.Context(), now, stationID, 8)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(item, now))
}

func (s *Server) handlePublicTrain(w http.ResponseWriter, r *http.Request, trainID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.PublicTrain(r.Context(), trainID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(item, now))
}

func (s *Server) handlePublicTrainStops(w http.ResponseWriter, r *http.Request, trainID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.TrainStops(r.Context(), 0, now, trainID)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(item, now))
}

func (s *Server) handleAuthTelegram(w http.ResponseWriter, r *http.Request, now time.Time) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body struct {
		InitData string `json:"initData"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	auth, err := validateTelegramInitData(body.InitData, s.cfg.BotToken, time.Duration(s.cfg.TrainWebTelegramAuthMaxAgeSec)*time.Second, now)
	if err != nil {
		s.writeError(w, http.StatusUnauthorized, err.Error())
		return
	}
	cookie, err := issueSessionCookie(s.sessionSecret, auth, now)
	if err != nil {
		s.writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if s.pathPrefix != "" {
		cookie.Path = s.pathPrefix
	}
	http.SetCookie(w, cookie)
	s.writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"userId":  auth.User.ID,
		"lang":    auth.User.LanguageCode,
		"baseUrl": s.cfg.TrainWebPublicBaseURL,
	})
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request, claims sessionClaims, now time.Time) {
	settings, err := s.app.UserSettings(r.Context(), claims.UserID)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	currentRide, err := s.app.CurrentRide(r.Context(), claims.UserID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"userId":      claims.UserID,
		"settings":    settings,
		"currentRide": currentRide,
	})
}

func (s *Server) handleWindowTrains(w http.ResponseWriter, r *http.Request, claims sessionClaims, windowID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	items, err := s.app.WindowTrains(r.Context(), claims.UserID, now, strings.TrimSpace(windowID))
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"trains":   items,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handleStations(w http.ResponseWriter, r *http.Request, _ sessionClaims, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	items, err := s.app.Stations(r.Context(), now, r.URL.Query().Get("q"))
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"stations": items,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handleStationSightingDestinations(w http.ResponseWriter, r *http.Request, _ sessionClaims, stationID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	items, err := s.app.StationSightingDestinations(r.Context(), now, stationID)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"stations": items,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handleStationDepartures(w http.ResponseWriter, r *http.Request, claims sessionClaims, stationID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	items, err := s.app.StationDepartures(r.Context(), claims.UserID, now, stationID, 2*time.Hour, 2*time.Hour)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(items, now))
}

func (s *Server) handleStationSighting(w http.ResponseWriter, r *http.Request, claims sessionClaims, stationID string, now time.Time) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body struct {
		DestinationStationID string `json:"destinationStationId"`
		TrainID              string `json:"trainId"`
	}
	if !s.decodeJSON(w, r, &body) {
		return
	}
	var destinationStationID *string
	if trimmed := strings.TrimSpace(body.DestinationStationID); trimmed != "" {
		destinationStationID = &trimmed
	}
	var trainID *string
	if trimmed := strings.TrimSpace(body.TrainID); trimmed != "" {
		trainID = &trimmed
	}
	result, err := s.app.SubmitStationSighting(r.Context(), claims.UserID, stationID, destinationStationID, trainID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	if result.Accepted && result.Event != nil && s.notifier != nil {
		if err := s.notifier.NotifyStationSighting(r.Context(), *result.Event, now); err != nil {
			s.writeAppError(w, err)
			return
		}
	}
	s.writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleRouteDestinations(w http.ResponseWriter, r *http.Request, _ sessionClaims, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	originID := strings.TrimSpace(r.URL.Query().Get("originStationId"))
	if originID == "" {
		s.writeError(w, http.StatusBadRequest, "originStationId is required")
		return
	}
	items, err := s.app.ReachableDestinations(r.Context(), now, originID, r.URL.Query().Get("q"))
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"stations": items,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handleRouteTrains(w http.ResponseWriter, r *http.Request, claims sessionClaims, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	originID := strings.TrimSpace(r.URL.Query().Get("originStationId"))
	destinationID := strings.TrimSpace(r.URL.Query().Get("destinationStationId"))
	if originID == "" || destinationID == "" {
		s.writeError(w, http.StatusBadRequest, "originStationId and destinationStationId are required")
		return
	}
	items, err := s.app.RouteTrains(r.Context(), claims.UserID, now, originID, destinationID, 18*time.Hour)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"trains":   items,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handleFavorites(w http.ResponseWriter, r *http.Request, claims sessionClaims) {
	switch r.Method {
	case http.MethodGet:
		items, err := s.app.FavoriteRoutes(r.Context(), claims.UserID)
		if err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"favorites": items})
	case http.MethodPut:
		var body struct {
			FromStationID string `json:"fromStationId"`
			ToStationID   string `json:"toStationId"`
		}
		if !s.decodeJSON(w, r, &body) {
			return
		}
		if err := s.app.SaveFavoriteRoute(r.Context(), claims.UserID, strings.TrimSpace(body.FromStationID), strings.TrimSpace(body.ToStationID)); err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	case http.MethodDelete:
		var body struct {
			FromStationID string `json:"fromStationId"`
			ToStationID   string `json:"toStationId"`
		}
		if !s.decodeJSON(w, r, &body) {
			return
		}
		if err := s.app.DeleteFavoriteRoute(r.Context(), claims.UserID, strings.TrimSpace(body.FromStationID), strings.TrimSpace(body.ToStationID)); err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleCurrentCheckIn(w http.ResponseWriter, r *http.Request, claims sessionClaims, now time.Time) {
	switch r.Method {
	case http.MethodGet:
		item, err := s.app.CurrentRide(r.Context(), claims.UserID, now)
		if err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"currentRide": item})
	case http.MethodPut:
		var body struct {
			TrainID           string `json:"trainId"`
			BoardingStationID string `json:"boardingStationId"`
		}
		if !s.decodeJSON(w, r, &body) {
			return
		}
		var stationID *string
		if trimmed := strings.TrimSpace(body.BoardingStationID); trimmed != "" {
			stationID = &trimmed
		}
		if err := s.app.CheckIn(r.Context(), claims.UserID, strings.TrimSpace(body.TrainID), stationID, now); err != nil {
			s.writeAppError(w, err)
			return
		}
		item, err := s.app.CurrentRide(r.Context(), claims.UserID, now)
		if err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"currentRide": item})
	case http.MethodDelete:
		if err := s.app.Checkout(r.Context(), claims.UserID, now); err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) handleUndoCheckOut(w http.ResponseWriter, r *http.Request, claims sessionClaims, now time.Time) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	ok, err := s.app.UndoCheckout(r.Context(), claims.UserID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"restored": ok})
}

func (s *Server) handleTrainStatus(w http.ResponseWriter, r *http.Request, claims sessionClaims, trainID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.TrainStatus(r.Context(), claims.UserID, trainID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(item, now))
}

func (s *Server) handleTrainStops(w http.ResponseWriter, r *http.Request, claims sessionClaims, trainID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.TrainStops(r.Context(), claims.UserID, now, trainID)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, s.withSchedulePayload(item, now))
}

func (s *Server) handleTrainTimeline(w http.ResponseWriter, r *http.Request, claims sessionClaims, trainID string, now time.Time) {
	if r.Method != http.MethodGet {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	item, err := s.app.TrainStatus(r.Context(), claims.UserID, trainID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{
		"timeline": item.Timeline,
		"schedule": s.appScheduleContext(now),
	})
}

func (s *Server) handleTrainReport(w http.ResponseWriter, r *http.Request, claims sessionClaims, trainID string, now time.Time) {
	if r.Method != http.MethodPost {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body struct {
		Signal string `json:"signal"`
	}
	if !s.decodeJSON(w, r, &body) {
		return
	}
	currentRide, err := s.app.CurrentRide(r.Context(), claims.UserID, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	if currentRide == nil || currentRide.CheckIn == nil || currentRide.CheckIn.TrainInstanceID != trainID {
		s.writeError(w, http.StatusConflict, "active ride required for this departure")
		return
	}
	signal, ok := reports.ParseSignal(body.Signal)
	if !ok {
		s.writeError(w, http.StatusBadRequest, "unsupported report signal")
		return
	}
	result, err := s.app.SubmitReport(r.Context(), claims.UserID, trainID, signal, now)
	if err != nil {
		s.writeAppError(w, err)
		return
	}
	if result.Accepted && s.notifier != nil {
		if err := s.notifier.NotifyRideUsers(r.Context(), claims.UserID, trainID, signal, now); err != nil {
			s.writeAppError(w, err)
			return
		}
	}
	s.writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleTrainMute(w http.ResponseWriter, r *http.Request, claims sessionClaims, trainID string, now time.Time) {
	if r.Method != http.MethodPut {
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body struct {
		DurationMinutes int `json:"durationMinutes"`
	}
	if !s.decodeJSON(w, r, &body) {
		return
	}
	if body.DurationMinutes <= 0 {
		body.DurationMinutes = 30
	}
	if err := s.app.MuteTrain(r.Context(), claims.UserID, trainID, now, time.Duration(body.DurationMinutes)*time.Minute); err != nil {
		s.writeAppError(w, err)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleSettings(w http.ResponseWriter, r *http.Request, claims sessionClaims) {
	switch r.Method {
	case http.MethodGet:
		settings, err := s.app.UserSettings(r.Context(), claims.UserID)
		if err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, settings)
	case http.MethodPatch:
		var body struct {
			AlertsEnabled *bool  `json:"alertsEnabled"`
			AlertStyle    string `json:"alertStyle"`
			Language      string `json:"language"`
		}
		if !s.decodeJSON(w, r, &body) {
			return
		}
		if body.AlertsEnabled != nil {
			if err := s.app.SetAlertsEnabled(r.Context(), claims.UserID, *body.AlertsEnabled); err != nil {
				s.writeAppError(w, err)
				return
			}
		}
		if strings.TrimSpace(body.AlertStyle) != "" {
			style, err := trainapp.ParseAlertStyle(body.AlertStyle)
			if err != nil {
				s.writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			if err := s.app.SetAlertStyle(r.Context(), claims.UserID, style); err != nil {
				s.writeAppError(w, err)
				return
			}
		}
		if strings.TrimSpace(body.Language) != "" {
			if err := s.app.SetLanguage(r.Context(), claims.UserID, trainapp.ParseLanguage(body.Language)); err != nil {
				s.writeAppError(w, err)
				return
			}
		}
		settings, err := s.app.UserSettings(r.Context(), claims.UserID)
		if err != nil {
			s.writeAppError(w, err)
			return
		}
		s.writeJSON(w, http.StatusOK, settings)
	default:
		s.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (s *Server) requireSession(w http.ResponseWriter, r *http.Request, now time.Time) (sessionClaims, bool) {
	cookie, err := r.Cookie(sessionCookieName)
	if err != nil {
		s.writeError(w, http.StatusUnauthorized, "missing session")
		return sessionClaims{}, false
	}
	claims, err := parseSession(s.sessionSecret, cookie.Value, now)
	if err != nil {
		s.writeError(w, http.StatusUnauthorized, err.Error())
		return sessionClaims{}, false
	}
	return claims, true
}

func (s *Server) serveShell(w http.ResponseWriter, status int, data pageData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	s.setNoStoreHeaders(w)
	w.WriteHeader(status)
	_ = s.pageTemplate.Execute(w, data)
}

func (s *Server) decodeJSON(w http.ResponseWriter, r *http.Request, dest any) bool {
	defer r.Body.Close()
	if err := json.NewDecoder(r.Body).Decode(dest); err != nil {
		s.writeError(w, http.StatusBadRequest, "invalid JSON body")
		return false
	}
	return true
}

func (s *Server) writeAppError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, trainapp.ErrNotFound):
		s.writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, trainapp.ErrCheckInUnavailable):
		s.writeError(w, http.StatusConflict, err.Error())
	case errors.Is(err, schedule.ErrUnavailable):
		s.writeError(w, http.StatusServiceUnavailable, err.Error())
	default:
		s.writeError(w, http.StatusInternalServerError, err.Error())
	}
}

func (s *Server) writeError(w http.ResponseWriter, status int, message string) {
	s.writeJSON(w, status, map[string]any{"error": strings.TrimSpace(message)})
}

func (s *Server) writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	s.setNoStoreHeaders(w)
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func (s *Server) newPageData(basePath string, mode string, trainID string) pageData {
	return pageData{
		BasePath:                basePath,
		PublicBaseURL:           s.cfg.TrainWebPublicBaseURL,
		Mode:                    mode,
		TrainID:                 trainID,
		StationCheckin:          s.app.StationCheckinEnabled(),
		MiniAppRefreshMs:        15_000,
		PublicRefreshMs:         30_000,
		ExternalTrainMapEnabled: s.cfg.ExternalTrainMapEnabled,
		ExternalTrainMapBaseURL: s.cfg.ExternalTrainMapBaseURL,
		ExternalTrainMapWsURL:   s.cfg.ExternalTrainMapWsURL,
		ExternalTrainGraphURL:   strings.TrimRight(basePath, "/") + "/api/v1/external/train-graph",
		AppCSSURL:               s.release.AssetURL(basePath, "app.css"),
		LeafletCSSURL:           s.release.AssetURL(basePath, "vendor/leaflet.css"),
		LeafletJSURL:            s.release.AssetURL(basePath, "vendor/leaflet.js"),
		ExternalFeedJSURL:       s.release.AssetURL(basePath, "external-feed.js"),
		AppJSURL:                s.release.AssetURL(basePath, "app.js"),
	}
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

func (s *Server) setReleaseHeaders(w http.ResponseWriter) {
	w.Header().Set("X-Train-Bot-Commit", s.release.Commit)
	w.Header().Set("X-Train-Bot-Build-Time", s.release.BuildTime)
	w.Header().Set("X-Train-Bot-Instance", s.release.Instance)
	w.Header().Set("X-Train-Bot-App-Js", s.release.AppJSHash)
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

func (s *Server) appScheduleAvailability() (bool, error) {
	return s.app.ScheduleAvailability()
}

func (s *Server) appScheduleContext(now time.Time) schedule.AccessContext {
	return s.app.ScheduleContext(now)
}

func (s *Server) appLoadedServiceDate() string {
	return s.app.LoadedServiceDate()
}

func (s *Server) withSchedulePayload(item any, now time.Time) map[string]any {
	payload := map[string]any{}
	raw, err := json.Marshal(item)
	if err == nil {
		_ = json.Unmarshal(raw, &payload)
	}
	if payload == nil {
		payload = map[string]any{}
	}
	payload["schedule"] = s.appScheduleContext(now)
	return payload
}
