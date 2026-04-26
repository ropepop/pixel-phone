package bot

import (
	"testing"

	"telegramtrainapp/internal/domain"
)

func TestFilterStationsByPrefixMatchesPlainLatinForLatvianNames(t *testing.T) {
	t.Parallel()

	stations := []domain.Station{
		{ID: "riga", Name: "Rīga", NormalizedKey: "rīga"},
		{ID: "kegums", Name: "Ķegums", NormalizedKey: "ķegums"},
		{ID: "cesis", Name: "Cēsis", NormalizedKey: "cēsis"},
	}

	if got := filterStationsByPrefix(stations, "riga"); len(got) != 1 || got[0].Name != "Rīga" {
		t.Fatalf("filterStationsByPrefix(riga) = %+v", got)
	}
	if got := filterStationsByPrefix(stations, "keg"); len(got) != 1 || got[0].Name != "Ķegums" {
		t.Fatalf("filterStationsByPrefix(keg) = %+v", got)
	}
	if got := filterStationsByPrefix(stations, "ces"); len(got) != 1 || got[0].Name != "Cēsis" {
		t.Fatalf("filterStationsByPrefix(ces) = %+v", got)
	}
}

func TestStationPrefixKeysFoldLatvianDiacritics(t *testing.T) {
	t.Parallel()

	stations := []domain.Station{
		{ID: "riga", Name: "Rīga", NormalizedKey: "rīga"},
		{ID: "kegums", Name: "Ķegums", NormalizedKey: "ķegums"},
		{ID: "cesis", Name: "Cēsis", NormalizedKey: "cēsis"},
	}

	got := stationPrefixKeys(stations)
	want := []string{"c", "k", "r"}
	if len(got) != len(want) {
		t.Fatalf("stationPrefixKeys length = %d, want %d (%+v)", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("stationPrefixKeys[%d] = %q, want %q (%+v)", i, got[i], want[i], got)
		}
	}
}

func TestRankStationsMatchesPlainLatinExactQuery(t *testing.T) {
	t.Parallel()

	stations := []domain.Station{
		{ID: "riga", Name: "Rīga", NormalizedKey: "rīga"},
		{ID: "riga_east", Name: "Rīgas centrs", NormalizedKey: "rīgas centrs"},
	}

	matches, exact := rankStations(stations, "riga")
	if len(exact) != 1 || exact[0].Name != "Rīga" {
		t.Fatalf("rankStations exact = %+v", exact)
	}
	if len(matches) != 2 || matches[0].Name != "Rīga" || matches[1].Name != "Rīgas centrs" {
		t.Fatalf("rankStations matches = %+v", matches)
	}
}
