package bot

import (
	"context"
	"testing"

	"telegramtrainapp/internal/domain"
)

const expectedReportsChannelURL = "https://t.me/vivi_kontrole_reports"

func TestSendSettingsUsesStateAwareLatvianAlertsLabels(t *testing.T) {
	t.Parallel()

	h := newCheckinHarness(t)
	defer h.closeFunc()

	const userID int64 = 77
	h.ensureLatvian(t, userID)

	if err := h.service.sendSettings(context.Background(), 5001, userID, domain.LanguageLV); err != nil {
		t.Fatalf("send settings with alerts on: %v", err)
	}
	req := h.recorder.lastRequest(t, "/sendMessage")
	rows := extractInlineButtons(t, req.payload)
	if rows[0][0]["text"] != h.service.catalog.T(domain.LanguageLV, "btn_settings_disable_alerts") {
		t.Fatalf("unexpected alerts-on button label: %v", rows[0][0]["text"])
	}
	if rows[1][0]["text"] != h.service.catalog.T(domain.LanguageLV, "btn_settings_toggle_style") {
		t.Fatalf("unexpected style button label: %v", rows[1][0]["text"])
	}
	if rows[3][0]["text"] != h.service.catalog.T(domain.LanguageLV, "btn_open_reports_channel") {
		t.Fatalf("unexpected reports button label: %v", rows[3][0]["text"])
	}
	if rows[3][0]["url"] != expectedReportsChannelURL {
		t.Fatalf("unexpected reports button url: %v", rows[3][0]["url"])
	}

	if err := h.store.SetAlertsEnabled(context.Background(), userID, false); err != nil {
		t.Fatalf("disable alerts: %v", err)
	}
	if err := h.service.sendSettings(context.Background(), 5001, userID, domain.LanguageLV); err != nil {
		t.Fatalf("send settings with alerts off: %v", err)
	}
	req = h.recorder.lastRequest(t, "/sendMessage")
	rows = extractInlineButtons(t, req.payload)
	if rows[0][0]["text"] != h.service.catalog.T(domain.LanguageLV, "btn_settings_enable_alerts") {
		t.Fatalf("unexpected alerts-off button label: %v", rows[0][0]["text"])
	}
	if rows[3][0]["url"] != expectedReportsChannelURL {
		t.Fatalf("unexpected reports button url after alerts toggle: %v", rows[3][0]["url"])
	}
}
