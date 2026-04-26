package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func withEnv(key, value string, fn func()) {
	old, had := os.LookupEnv(key)
	_ = os.Setenv(key, value)
	defer func() {
		if had {
			_ = os.Setenv(key, old)
		} else {
			_ = os.Unsetenv(key)
		}
	}()
	fn()
}

func withRequiredEnv(t *testing.T, fn func()) {
	t.Helper()
	withEnv("BOT_TOKEN", "token", fn)
}

func TestLoadFailsOnInvalidIntegerEnv(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("LONG_POLL_TIMEOUT", "abc", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "LONG_POLL_TIMEOUT") {
				t.Fatalf("expected LONG_POLL_TIMEOUT parse error, got %v", err)
			}
		})
	})
}

func TestLoadFailsOnInvalidBooleanEnv(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("FEATURE_STATION_CHECKIN", "not-bool", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "FEATURE_STATION_CHECKIN") {
				t.Fatalf("expected FEATURE_STATION_CHECKIN parse error, got %v", err)
			}
		})
	})
}

func TestLoadFailsOnInvalidFeatureInspectionSignalsEnv(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("FEATURE_INSPECTION_SIGNALS", "bad-value", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "FEATURE_INSPECTION_SIGNALS") {
				t.Fatalf("expected FEATURE_INSPECTION_SIGNALS parse error, got %v", err)
			}
		})
	})
}

func TestLoadFailsOnInvalidReportDumpChatID(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("REPORT_DUMP_CHAT_ID", "not-an-int", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "REPORT_DUMP_CHAT_ID") {
				t.Fatalf("expected REPORT_DUMP_CHAT_ID parse error, got %v", err)
			}
		})
	})
}

func TestLoadAcceptsValidConfiguredValues(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("LONG_POLL_TIMEOUT", "15", func() {
			withEnv("HTTP_TIMEOUT_SEC", "45", func() {
				withEnv("FEATURE_STATION_CHECKIN", "false", func() {
					withEnv("REPORT_DUMP_CHAT_ID", "-1003867662138", func() {
						cfg, err := Load()
						if err != nil {
							t.Fatalf("expected valid config, got error: %v", err)
						}
						if cfg.LongPollTimeout != 15 {
							t.Fatalf("expected long poll timeout 15, got %d", cfg.LongPollTimeout)
						}
						if cfg.HTTPTimeoutSec != 45 {
							t.Fatalf("expected http timeout 45, got %d", cfg.HTTPTimeoutSec)
						}
						if cfg.FeatureStationCheckin {
							t.Fatalf("expected feature station checkin disabled")
						}
						if cfg.ReportDumpChatID != -1003867662138 {
							t.Fatalf("expected report dump chat id to be parsed, got %d", cfg.ReportDumpChatID)
						}
					})
				})
			})
		})
	})
}

func TestLoadKeepsReportDumpDisabledWhenUnset(t *testing.T) {
	withRequiredEnv(t, func() {
		cfg, err := Load()
		if err != nil {
			t.Fatalf("expected valid config, got %v", err)
		}
		if cfg.ReportDumpChatID != 0 {
			t.Fatalf("expected dump sink disabled by default, got %d", cfg.ReportDumpChatID)
		}
	})
}

func TestLoadRequiresTrainWebFieldsWhenEnabled(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("TRAIN_WEB_ENABLED", "true", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "TRAIN_WEB_PUBLIC_BASE_URL") {
				t.Fatalf("expected missing train web base url error, got %v", err)
			}
		})
	})
}

func TestLoadAcceptsTrainWebConfigWhenEnabled(t *testing.T) {
	withRequiredEnv(t, func() {
		secretPath := filepath.Join(t.TempDir(), "session-secret")
		if err := os.WriteFile(secretPath, []byte("secret-value"), 0o600); err != nil {
			t.Fatalf("write secret: %v", err)
		}
		withEnv("TRAIN_WEB_ENABLED", "true", func() {
			withEnv("TRAIN_WEB_PUBLIC_BASE_URL", "https://example.test/pixel-stack/train", func() {
				withEnv("TRAIN_WEB_SESSION_SECRET_FILE", secretPath, func() {
					cfg, err := Load()
					if err != nil {
						t.Fatalf("expected valid train web config, got %v", err)
					}
					if !cfg.TrainWebEnabled {
						t.Fatalf("expected train web enabled")
					}
					if cfg.TrainWebPort != 9317 {
						t.Fatalf("expected default train web port, got %d", cfg.TrainWebPort)
					}
					if cfg.TrainWebPublicBaseURL != "https://example.test/pixel-stack/train" {
						t.Fatalf("unexpected train web public base url: %q", cfg.TrainWebPublicBaseURL)
					}
					if cfg.TrainWebSessionSecretFile != secretPath {
						t.Fatalf("unexpected session secret file: %q", cfg.TrainWebSessionSecretFile)
					}
				})
			})
		})
	})
}

func TestLoadAcceptsExternalTrainMapDefaults(t *testing.T) {
	withRequiredEnv(t, func() {
		cfg, err := Load()
		if err != nil {
			t.Fatalf("expected valid config, got %v", err)
		}
		if !cfg.ExternalTrainMapEnabled {
			t.Fatalf("expected external train map enabled by default")
		}
		if cfg.ExternalTrainMapBaseURL != "https://trainmap.vivi.lv" {
			t.Fatalf("unexpected external train map base url: %q", cfg.ExternalTrainMapBaseURL)
		}
		if cfg.ExternalTrainMapWsURL != "wss://trainmap.pv.lv/ws" {
			t.Fatalf("unexpected external train map websocket url: %q", cfg.ExternalTrainMapWsURL)
		}
	})
}

func TestLoadRequiresExternalTrainMapURLsWhenEnabled(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("EXTERNAL_TRAINMAP_BASE_URL", "   ", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "EXTERNAL_TRAINMAP_BASE_URL") {
				t.Fatalf("expected missing external train map base url error, got %v", err)
			}
		})
		withEnv("EXTERNAL_TRAINMAP_WS_URL", "   ", func() {
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), "EXTERNAL_TRAINMAP_WS_URL") {
				t.Fatalf("expected missing external train map websocket url error, got %v", err)
			}
		})
	})
}

func TestLoadAllowsExternalTrainMapToBeDisabled(t *testing.T) {
	withRequiredEnv(t, func() {
		withEnv("EXTERNAL_TRAINMAP_ENABLED", "false", func() {
			withEnv("EXTERNAL_TRAINMAP_BASE_URL", "", func() {
				withEnv("EXTERNAL_TRAINMAP_WS_URL", "", func() {
					cfg, err := Load()
					if err != nil {
						t.Fatalf("expected valid config with external train map disabled, got %v", err)
					}
					if cfg.ExternalTrainMapEnabled {
						t.Fatalf("expected external train map disabled")
					}
				})
			})
		})
	})
}
