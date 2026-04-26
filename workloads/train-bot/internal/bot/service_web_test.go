package bot

import (
	"context"
	"testing"
	"time"

	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
)

func TestOpenAppButtonRowUsesConfiguredMiniAppURL(t *testing.T) {
	t.Parallel()

	service := &Service{
		catalog:   i18n.NewCatalog(),
		webAppURL: "https://example.test/pixel-stack/train",
	}

	row := service.openAppButtonRow(domain.LanguageEN)
	if len(row) != 1 {
		t.Fatalf("expected one button, got %d", len(row))
	}

	webApp, ok := row[0]["web_app"].(map[string]string)
	if !ok {
		t.Fatalf("expected web_app payload, got %#v", row[0]["web_app"])
	}
	if webApp["url"] != "https://example.test/pixel-stack/train/app" {
		t.Fatalf("unexpected web app url: %q", webApp["url"])
	}
}

func TestOpenAppButtonRowUsesRootHostedMiniAppURL(t *testing.T) {
	t.Parallel()

	service := &Service{
		catalog:   i18n.NewCatalog(),
		webAppURL: "https://train-bot.jolkins.id.lv",
	}

	row := service.openAppButtonRow(domain.LanguageEN)
	if len(row) != 1 {
		t.Fatalf("expected one button, got %d", len(row))
	}

	webApp, ok := row[0]["web_app"].(map[string]string)
	if !ok {
		t.Fatalf("expected web_app payload, got %#v", row[0]["web_app"])
	}
	if webApp["url"] != "https://train-bot.jolkins.id.lv/app" {
		t.Fatalf("unexpected web app url: %q", webApp["url"])
	}
}

func TestOpenAppButtonRowReturnsNilWithoutBaseURL(t *testing.T) {
	t.Parallel()

	service := &Service{catalog: i18n.NewCatalog()}
	if row := service.openAppButtonRow(domain.LanguageEN); row != nil {
		t.Fatalf("expected nil row when web app URL is unset, got %#v", row)
	}
}

func TestConfigureBotSetsCommandsAndMenuButton(t *testing.T) {
	t.Parallel()

	recorder, client, closeFn := newTelegramRecorder(t)
	defer closeFn()

	service := NewService(
		client,
		nil,
		nil,
		nil,
		nil,
		nil,
		i18n.NewCatalog(),
		time.UTC,
		1,
		true,
		"https://train-bot.jolkins.id.lv",
	)

	service.configureBot(context.Background())

	commandsReq := recorder.lastRequest(t, "/setMyCommands")
	rawCommands, ok := commandsReq.payload["commands"].([]any)
	if !ok {
		t.Fatalf("commands payload missing or wrong type: %T", commandsReq.payload["commands"])
	}
	if len(rawCommands) != 2 {
		t.Fatalf("expected 2 commands, got %d", len(rawCommands))
	}

	menuReq := recorder.lastRequest(t, "/setChatMenuButton")
	menuButton, ok := menuReq.payload["menu_button"].(map[string]any)
	if !ok {
		t.Fatalf("menu_button missing or wrong type: %T", menuReq.payload["menu_button"])
	}
	webApp, ok := menuButton["web_app"].(map[string]any)
	if !ok {
		t.Fatalf("menu_button.web_app missing or wrong type: %T", menuButton["web_app"])
	}
	if webApp["url"] != "https://train-bot.jolkins.id.lv/app" {
		t.Fatalf("unexpected menu button url: %#v", webApp["url"])
	}
}
