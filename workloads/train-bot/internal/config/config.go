package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	BotToken                           string
	DBPath                             string
	SingleInstanceLockPath             string
	Timezone                           string
	ScheduleDir                        string
	LongPollTimeout                    int
	HTTPTimeoutSec                     int
	DataRetentionHrs                   int
	CooldownMin                        int
	DedupeSec                          int
	TrainWebEnabled                    bool
	TrainWebBindAddr                   string
	TrainWebPort                       int
	TrainWebPublicBaseURL              string
	TrainWebSessionSecretFile          string
	TrainWebTelegramAuthMaxAgeSec      int
	ExternalTrainMapEnabled            bool
	ExternalTrainMapBaseURL            string
	ExternalTrainMapWsURL              string
	FeatureInspectionSignals           bool
	FeatureInspectionSignalsConfigured bool
	FeatureStationCheckin              bool
	ScraperViviPageURL                 string
	ScraperViviGTFSURL                 string
	ScraperDailyHour                   int
	ScraperMinTrains                   int
	ScraperOutputDir                   string
	ScraperUserAgent                   string
	RuntimeSnapshotGCEnabled           bool
	ReportDumpChatID                   int64
}

func Load() (Config, error) {
	inspectionValue, inspectionConfigured, err := envBoolWithPresenceStrict("FEATURE_INSPECTION_SIGNALS", true)
	if err != nil {
		return Config{}, err
	}

	longPollTimeout, err := envOrIntStrict("LONG_POLL_TIMEOUT", 30)
	if err != nil {
		return Config{}, err
	}
	httpTimeoutSec, err := envOrIntStrict("HTTP_TIMEOUT_SEC", 15)
	if err != nil {
		return Config{}, err
	}
	dataRetentionHrs, err := envOrIntStrict("DATA_RETENTION_HOURS", 24)
	if err != nil {
		return Config{}, err
	}
	cooldownMin, err := envOrIntStrict("REPORT_COOLDOWN_MINUTES", 3)
	if err != nil {
		return Config{}, err
	}
	dedupeSec, err := envOrIntStrict("REPORT_DEDUPE_SECONDS", 90)
	if err != nil {
		return Config{}, err
	}
	trainWebEnabled, err := envOrBoolStrict("TRAIN_WEB_ENABLED", false)
	if err != nil {
		return Config{}, err
	}
	trainWebPort, err := envOrIntStrict("TRAIN_WEB_PORT", 9317)
	if err != nil {
		return Config{}, err
	}
	trainWebTelegramAuthMaxAgeSec, err := envOrIntStrict("TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC", 300)
	if err != nil {
		return Config{}, err
	}
	externalTrainMapEnabled, err := envOrBoolStrict("EXTERNAL_TRAINMAP_ENABLED", true)
	if err != nil {
		return Config{}, err
	}
	featureStationCheckin, err := envOrBoolStrict("FEATURE_STATION_CHECKIN", true)
	if err != nil {
		return Config{}, err
	}
	scraperDailyHour, err := envOrIntStrict("SCRAPER_DAILY_HOUR", 3)
	if err != nil {
		return Config{}, err
	}
	scraperMinTrains, err := envOrIntStrict("SCRAPER_MIN_TRAINS", 1)
	if err != nil {
		return Config{}, err
	}
	runtimeSnapshotGCEnabled, err := envOrBoolStrict("TRAIN_RUNTIME_SNAPSHOT_GC_ENABLED", false)
	if err != nil {
		return Config{}, err
	}
	reportDumpChatID, err := envOrInt64Strict("REPORT_DUMP_CHAT_ID", 0)
	if err != nil {
		return Config{}, err
	}

	cfg := Config{
		BotToken:                           os.Getenv("BOT_TOKEN"),
		DBPath:                             envOr("DB_PATH", "./train_bot.db"),
		SingleInstanceLockPath:             envOr("SINGLE_INSTANCE_LOCK_PATH", ""),
		Timezone:                           envOr("TZ", "Europe/Riga"),
		ScheduleDir:                        envOr("SCHEDULE_DIR", "./data/schedules"),
		LongPollTimeout:                    longPollTimeout,
		HTTPTimeoutSec:                     httpTimeoutSec,
		DataRetentionHrs:                   dataRetentionHrs,
		CooldownMin:                        cooldownMin,
		DedupeSec:                          dedupeSec,
		TrainWebEnabled:                    trainWebEnabled,
		TrainWebBindAddr:                   envOr("TRAIN_WEB_BIND_ADDR", "127.0.0.1"),
		TrainWebPort:                       trainWebPort,
		TrainWebPublicBaseURL:              strings.TrimRight(strings.TrimSpace(envOr("TRAIN_WEB_PUBLIC_BASE_URL", "")), "/"),
		TrainWebSessionSecretFile:          strings.TrimSpace(envOr("TRAIN_WEB_SESSION_SECRET_FILE", "")),
		TrainWebTelegramAuthMaxAgeSec:      trainWebTelegramAuthMaxAgeSec,
		ExternalTrainMapEnabled:            externalTrainMapEnabled,
		ExternalTrainMapBaseURL:            strings.TrimRight(strings.TrimSpace(envOr("EXTERNAL_TRAINMAP_BASE_URL", "https://trainmap.vivi.lv")), "/"),
		ExternalTrainMapWsURL:              strings.TrimSpace(envOr("EXTERNAL_TRAINMAP_WS_URL", "wss://trainmap.pv.lv/ws")),
		FeatureInspectionSignals:           inspectionValue,
		FeatureInspectionSignalsConfigured: inspectionConfigured,
		FeatureStationCheckin:              featureStationCheckin,
		ScraperViviPageURL:                 envOr("SCRAPER_VIVI_PAGE_URL", "https://www.vivi.lv/lv/informacija-pasazieriem/"),
		ScraperViviGTFSURL:                 envOr("SCRAPER_VIVI_GTFS_URL", "https://www.vivi.lv/uploads/GTFS.zip"),
		ScraperDailyHour:                   scraperDailyHour,
		ScraperMinTrains:                   scraperMinTrains,
		ScraperOutputDir:                   envOr("SCRAPER_OUTPUT_DIR", "./data/schedules"),
		ScraperUserAgent:                   envOr("SCRAPER_USER_AGENT", "telegram-train-bot-scraper/1.0"),
		RuntimeSnapshotGCEnabled:           runtimeSnapshotGCEnabled,
		ReportDumpChatID:                   reportDumpChatID,
	}

	if cfg.BotToken == "" {
		return Config{}, fmt.Errorf("BOT_TOKEN is required")
	}
	if cfg.HTTPTimeoutSec <= cfg.LongPollTimeout {
		// Keep HTTP timeout higher than Telegram long poll timeout to avoid guaranteed client-side timeouts.
		cfg.HTTPTimeoutSec = cfg.LongPollTimeout + 10
	}
	if _, err := time.LoadLocation(cfg.Timezone); err != nil {
		return Config{}, fmt.Errorf("invalid TZ: %w", err)
	}
	if cfg.ScraperMinTrains < 1 {
		cfg.ScraperMinTrains = 1
	}
	if cfg.ScraperDailyHour < 0 || cfg.ScraperDailyHour > 23 {
		cfg.ScraperDailyHour = 3
	}
	if cfg.TrainWebPort <= 0 || cfg.TrainWebPort > 65535 {
		return Config{}, fmt.Errorf("TRAIN_WEB_PORT must be between 1 and 65535, got %d", cfg.TrainWebPort)
	}
	if cfg.TrainWebTelegramAuthMaxAgeSec <= 0 {
		return Config{}, fmt.Errorf("TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC must be positive, got %d", cfg.TrainWebTelegramAuthMaxAgeSec)
	}
	if cfg.TrainWebEnabled {
		if cfg.TrainWebPublicBaseURL == "" {
			return Config{}, fmt.Errorf("TRAIN_WEB_PUBLIC_BASE_URL is required when TRAIN_WEB_ENABLED=true")
		}
		if cfg.TrainWebSessionSecretFile == "" {
			return Config{}, fmt.Errorf("TRAIN_WEB_SESSION_SECRET_FILE is required when TRAIN_WEB_ENABLED=true")
		}
	}
	if cfg.ExternalTrainMapEnabled {
		if cfg.ExternalTrainMapBaseURL == "" {
			return Config{}, fmt.Errorf("EXTERNAL_TRAINMAP_BASE_URL is required when EXTERNAL_TRAINMAP_ENABLED=true")
		}
		if cfg.ExternalTrainMapWsURL == "" {
			return Config{}, fmt.Errorf("EXTERNAL_TRAINMAP_WS_URL is required when EXTERNAL_TRAINMAP_ENABLED=true")
		}
	}
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
		if err == nil {
			return n, nil
		}
		return 0, fmt.Errorf("%s must be an integer, got %q", key, v)
	}
	return fallback, nil
}

func envOrBoolStrict(key string, fallback bool) (bool, error) {
	if v := os.Getenv(key); v != "" {
		if parsed, ok := parseBool(v); ok {
			return parsed, nil
		}
		return false, fmt.Errorf("%s must be a boolean, got %q", key, v)
	}
	return fallback, nil
}

func envOrInt64Strict(key string, fallback int64) (int64, error) {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil {
			return n, nil
		}
		return 0, fmt.Errorf("%s must be an integer, got %q", key, v)
	}
	return fallback, nil
}

func envBoolWithPresenceStrict(key string, fallback bool) (bool, bool, error) {
	v, ok := os.LookupEnv(key)
	if !ok {
		return fallback, false, nil
	}
	if parsed, ok := parseBool(v); ok {
		return parsed, true, nil
	}
	return false, true, fmt.Errorf("%s must be a boolean, got %q", key, v)
}

func parseBool(v string) (bool, bool) {
	switch strings.TrimSpace(v) {
	case "1", "true", "TRUE", "yes", "YES", "on", "ON":
		return true, true
	case "0", "false", "FALSE", "no", "NO", "off", "OFF":
		return false, true
	default:
		return false, false
	}
}
