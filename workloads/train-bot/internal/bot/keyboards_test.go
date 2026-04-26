package bot

import (
	"testing"

	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
)

func TestMainReplyKeyboardLocalizedEnglish(t *testing.T) {
	catalog := i18n.NewCatalog()
	kb := MainReplyKeyboardWithWebApp(domain.LanguageEN, catalog, "https://train-bot.jolkins.id.lv/app")

	rows, ok := kb["keyboard"].([][]map[string]any)
	if !ok {
		t.Fatalf("keyboard rows missing or wrong type: %T", kb["keyboard"])
	}
	if len(rows) != 4 {
		t.Fatalf("expected 4 keyboard rows, got %d", len(rows))
	}

	webApp, ok := rows[0][0]["web_app"].(map[string]string)
	if !ok {
		t.Fatalf("expected web_app payload in first row, got %T", rows[0][0]["web_app"])
	}
	if webApp["url"] != "https://train-bot.jolkins.id.lv/app" {
		t.Fatalf("unexpected web app url: %q", webApp["url"])
	}
	if rows[1][0]["text"] != catalog.T(domain.LanguageEN, "btn_main_checkin") {
		t.Fatalf("unexpected row 1 col 0: %q", rows[1][0]["text"])
	}
	if rows[1][1]["text"] != catalog.T(domain.LanguageEN, "btn_main_my_ride") {
		t.Fatalf("unexpected row 1 col 1: %q", rows[1][1]["text"])
	}
	if rows[2][0]["text"] != catalog.T(domain.LanguageEN, "btn_main_report") {
		t.Fatalf("unexpected row 2 col 0: %q", rows[2][0]["text"])
	}
	if rows[2][1]["text"] != catalog.T(domain.LanguageEN, "btn_main_settings") {
		t.Fatalf("unexpected row 2 col 1: %q", rows[2][1]["text"])
	}
	if rows[3][0]["text"] != catalog.T(domain.LanguageEN, "btn_main_help") {
		t.Fatalf("unexpected row 3 col 0: %q", rows[3][0]["text"])
	}

	placeholder, ok := kb["input_field_placeholder"].(string)
	if !ok {
		t.Fatalf("input_field_placeholder missing or wrong type: %T", kb["input_field_placeholder"])
	}
	if placeholder != catalog.T(domain.LanguageEN, "main_input_placeholder") {
		t.Fatalf("unexpected placeholder: %q", placeholder)
	}
}

func TestMainReplyKeyboardLocalizedLatvian(t *testing.T) {
	catalog := i18n.NewCatalog()
	kb := MainReplyKeyboard(domain.LanguageLV, catalog)

	rows, ok := kb["keyboard"].([][]map[string]any)
	if !ok {
		t.Fatalf("keyboard rows missing or wrong type: %T", kb["keyboard"])
	}
	if len(rows) != 3 {
		t.Fatalf("expected 3 keyboard rows, got %d", len(rows))
	}

	if rows[0][0]["text"] != catalog.T(domain.LanguageLV, "btn_main_checkin") {
		t.Fatalf("unexpected row 0 col 0: %q", rows[0][0]["text"])
	}
	if rows[0][1]["text"] != catalog.T(domain.LanguageLV, "btn_main_my_ride") {
		t.Fatalf("unexpected row 0 col 1: %q", rows[0][1]["text"])
	}
	if rows[1][0]["text"] != catalog.T(domain.LanguageLV, "btn_main_report") {
		t.Fatalf("unexpected row 1 col 0: %q", rows[1][0]["text"])
	}
	if rows[1][1]["text"] != catalog.T(domain.LanguageLV, "btn_main_settings") {
		t.Fatalf("unexpected row 1 col 1: %q", rows[1][1]["text"])
	}
	if rows[2][0]["text"] != catalog.T(domain.LanguageLV, "btn_main_help") {
		t.Fatalf("unexpected row 2 col 0: %q", rows[2][0]["text"])
	}

	placeholder, ok := kb["input_field_placeholder"].(string)
	if !ok {
		t.Fatalf("input_field_placeholder missing or wrong type: %T", kb["input_field_placeholder"])
	}
	if placeholder != catalog.T(domain.LanguageLV, "main_input_placeholder") {
		t.Fatalf("unexpected placeholder: %q", placeholder)
	}
}
