package web

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	trainapp "telegramtrainapp/internal/app"
	"telegramtrainapp/internal/config"
	"telegramtrainapp/internal/i18n"
)

func TestValidateTelegramInitDataAcceptsValidPayload(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.March, 6, 10, 30, 0, 0, time.UTC)
	auth := telegramAuth{
		QueryID:  "AAEAAAE",
		AuthDate: now.Add(-2 * time.Minute),
		User: telegramUser{
			ID:           123456789,
			FirstName:    "Alex",
			LanguageCode: "lv",
		},
	}

	initData := signedInitData(t, "bot-token", auth)
	got, err := validateTelegramInitData(initData, "bot-token", 5*time.Minute, now)
	if err != nil {
		t.Fatalf("validateTelegramInitData: %v", err)
	}
	if got.User.ID != auth.User.ID {
		t.Fatalf("unexpected user id: got %d want %d", got.User.ID, auth.User.ID)
	}
	if got.User.LanguageCode != auth.User.LanguageCode {
		t.Fatalf("unexpected language: got %q want %q", got.User.LanguageCode, auth.User.LanguageCode)
	}
}

func TestValidateTelegramInitDataRejectsInvalidSignature(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.March, 6, 10, 30, 0, 0, time.UTC)
	auth := telegramAuth{
		QueryID:  "AAEAAAE",
		AuthDate: now.Add(-1 * time.Minute),
		User:     telegramUser{ID: 42, FirstName: "Alex", LanguageCode: "en"},
	}

	initData := signedInitData(t, "bot-token", auth)
	if _, err := validateTelegramInitData(initData, "different-token", 5*time.Minute, now); err == nil {
		t.Fatalf("expected signature validation error")
	}
}

func TestValidateTelegramInitDataRejectsExpiredPayload(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.March, 6, 10, 30, 0, 0, time.UTC)
	auth := telegramAuth{
		QueryID:  "AAEAAAE",
		AuthDate: now.Add(-10 * time.Minute),
		User:     telegramUser{ID: 42, FirstName: "Alex", LanguageCode: "en"},
	}

	initData := signedInitData(t, "bot-token", auth)
	if _, err := validateTelegramInitData(initData, "bot-token", 5*time.Minute, now); err == nil {
		t.Fatalf("expected expiry error")
	}
}

func TestIssueSessionCookieRoundTrip(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.March, 6, 10, 30, 0, 0, time.UTC)
	cookie, err := issueSessionCookie([]byte("0123456789abcdef0123456789abcdef"), telegramAuth{
		AuthDate: now,
		User: telegramUser{
			ID:           77,
			LanguageCode: "lv",
		},
	}, now)
	if err != nil {
		t.Fatalf("issueSessionCookie: %v", err)
	}

	claims, err := parseSession([]byte("0123456789abcdef0123456789abcdef"), cookie.Value, now.Add(30*time.Minute))
	if err != nil {
		t.Fatalf("parseSession: %v", err)
	}
	if claims.UserID != 77 {
		t.Fatalf("unexpected user id: got %d", claims.UserID)
	}
	if claims.Language != "lv" {
		t.Fatalf("unexpected language: got %q", claims.Language)
	}
}

func TestAuthTelegramSetsScopedSessionCookie(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.March, 6, 10, 30, 0, 0, time.UTC)
	server := newTestServerWithBaseURL(t, "https://example.test/pixel-stack/train")
	auth := telegramAuth{
		QueryID:  "AAEAAAE",
		AuthDate: now.Add(-30 * time.Second),
		User: telegramUser{
			ID:           77,
			FirstName:    "Alex",
			LanguageCode: "en",
		},
	}
	body, err := json.Marshal(map[string]string{"initData": signedInitData(t, server.cfg.BotToken, auth)})
	if err != nil {
		t.Fatalf("marshal body: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/pixel-stack/train/api/v1/auth/telegram", bytes.NewReader(body))
	res := httptest.NewRecorder()
	server.handleAuthTelegram(res, req, now)

	if res.Code != http.StatusOK {
		t.Fatalf("unexpected status: got %d body=%s", res.Code, res.Body.String())
	}
	cookies := res.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("expected 1 cookie, got %d", len(cookies))
	}
	if cookies[0].Path != "/pixel-stack/train" {
		t.Fatalf("unexpected cookie path: %q", cookies[0].Path)
	}
	if !cookies[0].Secure {
		t.Fatalf("expected secure cookie")
	}
	if cookies[0].SameSite != http.SameSiteNoneMode {
		t.Fatalf("unexpected SameSite: %v", cookies[0].SameSite)
	}
}

func TestAuthTelegramSetsRootScopedSessionCookieForHostRootDeployment(t *testing.T) {
	t.Parallel()

	now := time.Date(2026, time.March, 6, 10, 30, 0, 0, time.UTC)
	server := newTestServerWithBaseURL(t, "https://train-bot.jolkins.id.lv")
	auth := telegramAuth{
		QueryID:  "AAEAAAE",
		AuthDate: now.Add(-30 * time.Second),
		User: telegramUser{
			ID:           77,
			FirstName:    "Alex",
			LanguageCode: "en",
		},
	}
	body, err := json.Marshal(map[string]string{"initData": signedInitData(t, server.cfg.BotToken, auth)})
	if err != nil {
		t.Fatalf("marshal body: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/telegram", bytes.NewReader(body))
	res := httptest.NewRecorder()
	server.handleAuthTelegram(res, req, now)

	if res.Code != http.StatusOK {
		t.Fatalf("unexpected status: got %d body=%s", res.Code, res.Body.String())
	}
	cookies := res.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("expected 1 cookie, got %d", len(cookies))
	}
	if cookies[0].Path != "/" {
		t.Fatalf("unexpected cookie path: %q", cookies[0].Path)
	}
	if !cookies[0].Secure {
		t.Fatalf("expected secure cookie")
	}
	if cookies[0].SameSite != http.SameSiteNoneMode {
		t.Fatalf("unexpected SameSite: %v", cookies[0].SameSite)
	}
}

func TestServeHTTPRejectsAnonymousUserRoute(t *testing.T) {
	t.Parallel()

	server := newTestServer(t)
	req := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/api/v1/me", nil)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("unexpected status: got %d body=%s", res.Code, res.Body.String())
	}
}

func TestServeHTTPServesRootHostDeploymentRoutes(t *testing.T) {
	t.Parallel()

	server := newTestServerWithBaseURL(t, "https://train-bot.jolkins.id.lv")
	paths := map[string]string{
		"/":                 "public-stations",
		"/app":              "mini-app",
		"/map":              "public-network-map",
		"/stations":         "public-stations",
		"/departures":       "public-dashboard",
		"/t/demo-train":     "public-train",
		"/t/demo-train/map": "public-map",
	}
	for path, mode := range paths {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		res := httptest.NewRecorder()
		server.ServeHTTP(res, req)
		if res.Code != http.StatusOK {
			t.Fatalf("unexpected status for %s: got %d body=%s", path, res.Code, res.Body.String())
		}
		assertShellMode(t, path, res.Body.String(), mode)
	}
}

func TestServeHTTPLegacyPathDeploymentRoutesStillWork(t *testing.T) {
	t.Parallel()

	server := newTestServerWithBaseURL(t, "https://example.test/pixel-stack/train")
	paths := map[string]string{
		"/pixel-stack/train":                  "public-stations",
		"/pixel-stack/train/app":              "mini-app",
		"/pixel-stack/train/map":              "public-network-map",
		"/pixel-stack/train/stations":         "public-stations",
		"/pixel-stack/train/departures":       "public-dashboard",
		"/pixel-stack/train/t/demo-train":     "public-train",
		"/pixel-stack/train/t/demo-train/map": "public-map",
	}
	for path, mode := range paths {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		res := httptest.NewRecorder()
		server.ServeHTTP(res, req)
		if res.Code != http.StatusOK {
			t.Fatalf("unexpected status for %s: got %d body=%s", path, res.Code, res.Body.String())
		}
		assertShellMode(t, path, res.Body.String(), mode)
	}
}

func TestServeHTTPShellAddsReleaseHeadersAndFingerprintedAssets(t *testing.T) {
	t.Parallel()

	server := newTestServerWithBaseURL(t, "https://example.test/pixel-stack/train")
	req := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/app", nil)
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("unexpected status: got %d body=%s", res.Code, res.Body.String())
	}
	if got := res.Header().Get("Cache-Control"); got != "no-store, no-cache, must-revalidate, max-age=0" {
		t.Fatalf("unexpected cache-control: %q", got)
	}
	if got := res.Header().Get("X-Train-Bot-Commit"); got != server.release.Commit {
		t.Fatalf("unexpected commit header: got %q want %q", got, server.release.Commit)
	}
	if got := res.Header().Get("X-Train-Bot-Build-Time"); got != server.release.BuildTime {
		t.Fatalf("unexpected build time header: got %q want %q", got, server.release.BuildTime)
	}
	if got := res.Header().Get("X-Train-Bot-Instance"); got != server.release.Instance {
		t.Fatalf("unexpected instance header: got %q want %q", got, server.release.Instance)
	}
	if got := res.Header().Get("X-Train-Bot-App-Js"); got != server.release.AppJSHash {
		t.Fatalf("unexpected app.js header: got %q want %q", got, server.release.AppJSHash)
	}
	body := res.Body.String()
	if !strings.Contains(body, "/pixel-stack/train/assets/app.css?v="+server.release.AppCSSHash) {
		t.Fatalf("expected fingerprinted app.css URL, body=%s", body)
	}
	if !strings.Contains(body, "/pixel-stack/train/assets/app.js?v="+server.release.AppJSHash) {
		t.Fatalf("expected fingerprinted app.js URL, body=%s", body)
	}
}

func TestServeHTTPAssetCacheHeadersDependOnFingerprint(t *testing.T) {
	t.Parallel()

	server := newTestServerWithBaseURL(t, "https://example.test/pixel-stack/train")

	versionedReq := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/assets/app.js?v="+server.release.AppJSHash, nil)
	versionedRes := httptest.NewRecorder()
	server.ServeHTTP(versionedRes, versionedReq)
	if versionedRes.Code != http.StatusOK {
		t.Fatalf("unexpected versioned asset status: got %d body=%s", versionedRes.Code, versionedRes.Body.String())
	}
	if got := versionedRes.Header().Get("Cache-Control"); got != "public, max-age=31536000, immutable" {
		t.Fatalf("unexpected immutable cache-control: %q", got)
	}
	if got := versionedRes.Header().Get("X-Train-Bot-App-Js"); got != server.release.AppJSHash {
		t.Fatalf("unexpected app.js hash header: got %q want %q", got, server.release.AppJSHash)
	}

	unversionedReq := httptest.NewRequest(http.MethodGet, "/pixel-stack/train/assets/app.js", nil)
	unversionedRes := httptest.NewRecorder()
	server.ServeHTTP(unversionedRes, unversionedReq)
	if unversionedRes.Code != http.StatusOK {
		t.Fatalf("unexpected unversioned asset status: got %d body=%s", unversionedRes.Code, unversionedRes.Body.String())
	}
	if got := unversionedRes.Header().Get("Cache-Control"); got != "no-store, no-cache, must-revalidate, max-age=0" {
		t.Fatalf("unexpected unversioned cache-control: %q", got)
	}
}

func assertShellMode(t *testing.T, path string, body string, want string) {
	t.Helper()

	if !strings.Contains(body, want) {
		t.Fatalf("expected shell for %s to contain mode %q, body=%s", path, want, body)
	}
}

func TestServeHTTPRejectsAnonymousStationSightingSubmission(t *testing.T) {
	t.Parallel()

	server := newTestServer(t)
	req := httptest.NewRequest(http.MethodPost, "/pixel-stack/train/api/v1/stations/riga/sightings", bytes.NewReader([]byte(`{"destinationStationId":"jelgava"}`)))
	res := httptest.NewRecorder()

	server.ServeHTTP(res, req)

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("unexpected status: got %d body=%s", res.Code, res.Body.String())
	}
}

func newTestServer(t *testing.T) *Server {
	return newTestServerWithBaseURL(t, "https://example.test/pixel-stack/train")
}

func newTestServerWithBaseURL(t *testing.T, trainWebPublicBaseURL string) *Server {
	t.Helper()

	dir := t.TempDir()
	secretPath := filepath.Join(dir, "train-session-secret")
	if err := os.WriteFile(secretPath, []byte("0123456789abcdef0123456789abcdef"), 0o600); err != nil {
		t.Fatalf("write secret: %v", err)
	}

	server, err := NewServer(config.Config{
		BotToken:                      "bot-token",
		TrainWebEnabled:               true,
		TrainWebBindAddr:              "127.0.0.1",
		TrainWebPort:                  9317,
		TrainWebPublicBaseURL:         trainWebPublicBaseURL,
		TrainWebSessionSecretFile:     secretPath,
		TrainWebTelegramAuthMaxAgeSec: 300,
	}, trainapp.NewService(nil, nil, nil, nil, time.UTC, false), i18n.NewCatalog(), time.UTC)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	return server
}

func signedInitData(t *testing.T, botToken string, auth telegramAuth) string {
	t.Helper()

	userJSON, err := json.Marshal(auth.User)
	if err != nil {
		t.Fatalf("marshal user: %v", err)
	}

	values := url.Values{}
	values.Set("auth_date", strconv.FormatInt(auth.AuthDate.Unix(), 10))
	values.Set("query_id", auth.QueryID)
	values.Set("user", string(userJSON))

	dataCheckString := "auth_date=" + values.Get("auth_date") + "\n" +
		"query_id=" + values.Get("query_id") + "\n" +
		"user=" + values.Get("user")
	secret := hmacSHA256([]byte("WebAppData"), []byte(botToken))
	values.Set("hash", hex.EncodeToString(hmacSHA256(secret, []byte(dataCheckString))))
	return values.Encode()
}
