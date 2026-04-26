package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadDerivesReportsChannelURLFromDumpChat(t *testing.T) {
	t.Setenv("BOT_TOKEN", "test-token")
	t.Setenv("SATIKSME_WEB_PUBLIC_BASE_URL", "https://satiksme-bot.jolkins.id.lv")
	t.Setenv("SATIKSME_WEB_SESSION_SECRET_FILE", filepath.Join(t.TempDir(), "secret"))
	if err := os.WriteFile(os.Getenv("SATIKSME_WEB_SESSION_SECRET_FILE"), []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatalf("write secret: %v", err)
	}
	t.Setenv("REPORT_DUMP_CHAT", "@satiksme_bot_reports")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if got, want := cfg.ReportsChannelURL, "https://t.me/satiksme_bot_reports"; got != want {
		t.Fatalf("ReportsChannelURL = %q, want %q", got, want)
	}
}

func TestLoadRejectsInvalidBool(t *testing.T) {
	t.Setenv("SATIKSME_WEB_ENABLED", "maybe")
	if _, err := LoadCatalogOnly(); err == nil {
		t.Fatalf("expected error for invalid boolean")
	}
}

func TestLoadCommonDefaultsLiveDeparturesProxyEnabled(t *testing.T) {
	cfg, err := loadCommon()
	if err != nil {
		t.Fatalf("loadCommon() error = %v", err)
	}
	if !cfg.SatiksmeWebDirectProxyEnabled {
		t.Fatalf("SatiksmeWebDirectProxyEnabled = %v, want true", cfg.SatiksmeWebDirectProxyEnabled)
	}
}
