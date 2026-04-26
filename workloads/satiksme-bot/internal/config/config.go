package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	BotToken                         string
	DBPath                           string
	SingleInstanceLockPath           string
	Timezone                         string
	LongPollTimeout                  int
	HTTPTimeoutSec                   int
	DataRetentionHours               int
	ReportVisibilityMinutes          int
	ReportCooldownMinutes            int
	ReportDedupeSeconds              int
	SatiksmeWebEnabled               bool
	SatiksmeWebBindAddr              string
	SatiksmeWebPort                  int
	SatiksmeWebPublicBaseURL         string
	SatiksmeWebSessionSecretFile     string
	SatiksmeWebDirectProxyEnabled    bool
	SatiksmeWebTelegramAuthMaxAgeSec int
	ReportDumpChat                   string
	ReportsChannelURL                string
	LiveDeparturesURL                string
	CatalogMirrorDir                 string
	CatalogOutputPath                string
	CatalogRefreshHours              int
	CleanupIntervalMinutes           int
	SourceStopsURL                   string
	SourceRoutesURL                  string
	SourceGTFSURL                    string
}

type CatalogOnly struct {
	HTTPTimeoutSec      int
	CatalogMirrorDir    string
	CatalogOutputPath   string
	CatalogRefreshHours int
	SourceStopsURL      string
	SourceRoutesURL     string
	SourceGTFSURL       string
}

func Load() (Config, error) {
	cfg, err := loadCommon()
	if err != nil {
		return Config{}, err
	}
	cfg.BotToken = strings.TrimSpace(os.Getenv("BOT_TOKEN"))
	if cfg.BotToken == "" {
		return Config{}, fmt.Errorf("BOT_TOKEN is required")
	}
	if cfg.SatiksmeWebEnabled {
		if cfg.SatiksmeWebPublicBaseURL == "" {
			return Config{}, fmt.Errorf("SATIKSME_WEB_PUBLIC_BASE_URL is required when SATIKSME_WEB_ENABLED=true")
		}
		if cfg.SatiksmeWebSessionSecretFile == "" {
			return Config{}, fmt.Errorf("SATIKSME_WEB_SESSION_SECRET_FILE is required when SATIKSME_WEB_ENABLED=true")
		}
	}
	return cfg, nil
}

func LoadCatalogOnly() (CatalogOnly, error) {
	cfg, err := loadCommon()
	if err != nil {
		return CatalogOnly{}, err
	}
	return CatalogOnly{
		HTTPTimeoutSec:      cfg.HTTPTimeoutSec,
		CatalogMirrorDir:    cfg.CatalogMirrorDir,
		CatalogOutputPath:   cfg.CatalogOutputPath,
		CatalogRefreshHours: cfg.CatalogRefreshHours,
		SourceStopsURL:      cfg.SourceStopsURL,
		SourceRoutesURL:     cfg.SourceRoutesURL,
		SourceGTFSURL:       cfg.SourceGTFSURL,
	}, nil
}

func loadCommon() (Config, error) {
	longPollTimeout, err := envOrIntStrict("LONG_POLL_TIMEOUT", 30)
	if err != nil {
		return Config{}, err
	}
	httpTimeoutSec, err := envOrIntStrict("HTTP_TIMEOUT_SEC", 20)
	if err != nil {
		return Config{}, err
	}
	dataRetentionHours, err := envOrIntStrict("DATA_RETENTION_HOURS", 24)
	if err != nil {
		return Config{}, err
	}
	reportVisibilityMinutes, err := envOrIntStrict("REPORT_VISIBILITY_MINUTES", 30)
	if err != nil {
		return Config{}, err
	}
	reportCooldownMinutes, err := envOrIntStrict("REPORT_COOLDOWN_MINUTES", 3)
	if err != nil {
		return Config{}, err
	}
	reportDedupeSeconds, err := envOrIntStrict("REPORT_DEDUPE_SECONDS", 90)
	if err != nil {
		return Config{}, err
	}
	webEnabled, err := envOrBoolStrict("SATIKSME_WEB_ENABLED", true)
	if err != nil {
		return Config{}, err
	}
	directProxyEnabled, err := envOrBoolStrict("SATIKSME_WEB_DIRECT_PROXY_ENABLED", true)
	if err != nil {
		return Config{}, err
	}
	webPort, err := envOrIntStrict("SATIKSME_WEB_PORT", 9318)
	if err != nil {
		return Config{}, err
	}
	authMaxAge, err := envOrIntStrict("SATIKSME_WEB_TELEGRAM_AUTH_MAX_AGE_SEC", 300)
	if err != nil {
		return Config{}, err
	}
	refreshHours, err := envOrIntStrict("SATIKSME_CATALOG_REFRESH_HOURS", 24)
	if err != nil {
		return Config{}, err
	}
	cleanupIntervalMinutes, err := envOrIntStrict("SATIKSME_CLEANUP_INTERVAL_MINUTES", 10)
	if err != nil {
		return Config{}, err
	}

	cfg := Config{
		DBPath:                           envOr("DB_PATH", "./satiksme_bot.db"),
		SingleInstanceLockPath:           envOr("SINGLE_INSTANCE_LOCK_PATH", ""),
		Timezone:                         envOr("TZ", "Europe/Riga"),
		LongPollTimeout:                  longPollTimeout,
		HTTPTimeoutSec:                   httpTimeoutSec,
		DataRetentionHours:               dataRetentionHours,
		ReportVisibilityMinutes:          reportVisibilityMinutes,
		ReportCooldownMinutes:            reportCooldownMinutes,
		ReportDedupeSeconds:              reportDedupeSeconds,
		SatiksmeWebEnabled:               webEnabled,
		SatiksmeWebBindAddr:              envOr("SATIKSME_WEB_BIND_ADDR", "127.0.0.1"),
		SatiksmeWebPort:                  webPort,
		SatiksmeWebPublicBaseURL:         strings.TrimRight(strings.TrimSpace(envOr("SATIKSME_WEB_PUBLIC_BASE_URL", "")), "/"),
		SatiksmeWebSessionSecretFile:     strings.TrimSpace(envOr("SATIKSME_WEB_SESSION_SECRET_FILE", "")),
		SatiksmeWebDirectProxyEnabled:    directProxyEnabled,
		SatiksmeWebTelegramAuthMaxAgeSec: authMaxAge,
		ReportDumpChat:                   strings.TrimSpace(envOr("REPORT_DUMP_CHAT", "@satiksme_bot_reports")),
		ReportsChannelURL:                strings.TrimSpace(envOr("REPORTS_CHANNEL_URL", "")),
		LiveDeparturesURL:                strings.TrimRight(strings.TrimSpace(envOr("SATIKSME_LIVE_DEPARTURES_URL", "https://saraksti.rigassatiksme.lv/departures2.php")), "/"),
		CatalogMirrorDir:                 envOr("SATIKSME_CATALOG_MIRROR_DIR", "./data/catalog/source"),
		CatalogOutputPath:                envOr("SATIKSME_CATALOG_OUTPUT_PATH", "./data/catalog/generated/catalog.json"),
		CatalogRefreshHours:              refreshHours,
		CleanupIntervalMinutes:           cleanupIntervalMinutes,
		SourceStopsURL:                   envOr("SATIKSME_SOURCE_STOPS_URL", "https://saraksti.rigassatiksme.lv/riga/stops.txt"),
		SourceRoutesURL:                  envOr("SATIKSME_SOURCE_ROUTES_URL", "https://saraksti.rigassatiksme.lv/riga/routes.txt"),
		SourceGTFSURL:                    envOr("SATIKSME_SOURCE_GTFS_URL", "https://data.gov.lv/dati/dataset/6d78358a-0095-4ce3-b119-6cde5d0ac54f/resource/c576c770-a01b-49b0-bdc4-0005a1ec5838/download/marsrutusaraksti02_2026.zip"),
	}

	if cfg.HTTPTimeoutSec <= cfg.LongPollTimeout {
		cfg.HTTPTimeoutSec = cfg.LongPollTimeout + 10
	}
	if _, err := time.LoadLocation(cfg.Timezone); err != nil {
		return Config{}, fmt.Errorf("invalid TZ: %w", err)
	}
	if cfg.SatiksmeWebPort <= 0 || cfg.SatiksmeWebPort > 65535 {
		return Config{}, fmt.Errorf("SATIKSME_WEB_PORT must be between 1 and 65535, got %d", cfg.SatiksmeWebPort)
	}
	if cfg.SatiksmeWebTelegramAuthMaxAgeSec <= 0 {
		return Config{}, fmt.Errorf("SATIKSME_WEB_TELEGRAM_AUTH_MAX_AGE_SEC must be positive")
	}
	if cfg.CatalogRefreshHours <= 0 {
		cfg.CatalogRefreshHours = 24
	}
	if cfg.CleanupIntervalMinutes <= 0 {
		cfg.CleanupIntervalMinutes = 10
	}
	if cfg.ReportsChannelURL == "" && strings.HasPrefix(cfg.ReportDumpChat, "@") {
		cfg.ReportsChannelURL = "https://t.me/" + strings.TrimPrefix(cfg.ReportDumpChat, "@")
	}
	cfg.CatalogMirrorDir = filepath.Clean(cfg.CatalogMirrorDir)
	cfg.CatalogOutputPath = filepath.Clean(cfg.CatalogOutputPath)
	return cfg, nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envOrIntStrict(key string, fallback int) (int, error) {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			return 0, fmt.Errorf("%s must be an integer, got %q", key, v)
		}
		return n, nil
	}
	return fallback, nil
}

func envOrBoolStrict(key string, fallback bool) (bool, error) {
	if v := os.Getenv(key); v != "" {
		switch strings.ToLower(strings.TrimSpace(v)) {
		case "1", "true", "yes", "on":
			return true, nil
		case "0", "false", "no", "off":
			return false, nil
		default:
			return false, fmt.Errorf("%s must be a boolean, got %q", key, v)
		}
	}
	return fallback, nil
}
