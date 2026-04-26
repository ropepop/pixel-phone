package bot

import (
	"testing"

	"telegramtrainapp/internal/domain"
	"telegramtrainapp/internal/i18n"
)

func TestMainMenuActionRecognizesEnglishAndLatvianLabels(t *testing.T) {
	catalog := i18n.NewCatalog()
	svc := &Service{catalog: catalog}

	cases := []struct {
		text string
		want string
	}{
		{text: catalog.T(domain.LanguageEN, "btn_open_app"), want: mainMenuActionOpenApp},
		{text: catalog.T(domain.LanguageLV, "btn_open_app"), want: mainMenuActionOpenApp},
		{text: catalog.T(domain.LanguageEN, "btn_main_checkin"), want: mainMenuActionCheckin},
		{text: catalog.T(domain.LanguageLV, "btn_main_checkin"), want: mainMenuActionCheckin},
		{text: catalog.T(domain.LanguageEN, "btn_main_my_ride"), want: mainMenuActionMyRide},
		{text: catalog.T(domain.LanguageLV, "btn_main_my_ride"), want: mainMenuActionMyRide},
		{text: catalog.T(domain.LanguageEN, "btn_main_report"), want: mainMenuActionReport},
		{text: catalog.T(domain.LanguageLV, "btn_main_report"), want: mainMenuActionReport},
		{text: catalog.T(domain.LanguageEN, "btn_main_settings"), want: mainMenuActionSettings},
		{text: catalog.T(domain.LanguageLV, "btn_main_settings"), want: mainMenuActionSettings},
		{text: catalog.T(domain.LanguageEN, "btn_main_help"), want: mainMenuActionHelp},
		{text: catalog.T(domain.LanguageLV, "btn_main_help"), want: mainMenuActionHelp},
	}

	for _, tc := range cases {
		if got := svc.mainMenuAction(tc.text); got != tc.want {
			t.Fatalf("mainMenuAction(%q) = %q, want %q", tc.text, got, tc.want)
		}
	}
}

func TestMainMenuActionUnknownText(t *testing.T) {
	svc := &Service{catalog: i18n.NewCatalog()}
	if got := svc.mainMenuAction("not a menu button"); got != "" {
		t.Fatalf("expected empty action for unknown text, got %q", got)
	}
}
