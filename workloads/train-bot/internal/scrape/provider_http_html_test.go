package scrape

import "testing"

func TestDecodeRawScheduleHTMLRows(t *testing.T) {
	html := `
<table>
  <tr data-id="t1" data-service-date="2026-02-26" data-from-station="Riga" data-to-station="Jelgava" data-departure-at="2026-02-26T08:00:00Z" data-arrival-at="2026-02-26T09:00:00Z"></tr>
  <tr data-train-id="t1" data-stop-station="Riga" data-stop-seq="1" data-stop-departure-at="2026-02-26T08:00:00Z"></tr>
  <tr data-train-id="t1" data-stop-station="Jelgava" data-stop-seq="2" data-stop-arrival-at="2026-02-26T09:00:00Z"></tr>
</table>`
	got, err := decodeRawScheduleHTML("html-source", []byte(html))
	if err != nil {
		t.Fatalf("decode html rows: %v", err)
	}
	if len(got.Trains) != 1 {
		t.Fatalf("expected 1 train, got %d", len(got.Trains))
	}
	if got.Trains[0].ID != "t1" {
		t.Fatalf("expected train id t1, got %s", got.Trains[0].ID)
	}
	if len(got.Trains[0].Stops) != 2 {
		t.Fatalf("expected 2 stops, got %d", len(got.Trains[0].Stops))
	}
}

func TestDecodeRawScheduleHTMLEmbeddedJSON(t *testing.T) {
	html := `
<html>
  <script id="schedule-json" type="application/json">
  {"trains":[{"id":"t2","service_date":"2026-02-26","from_station":"Riga","to_station":"Tukums","departure_at":"2026-02-26T10:00:00Z","arrival_at":"2026-02-26T11:00:00Z"}]}
  </script>
</html>`
	got, err := decodeRawScheduleHTML("html-source", []byte(html))
	if err != nil {
		t.Fatalf("decode embedded json: %v", err)
	}
	if len(got.Trains) != 1 {
		t.Fatalf("expected 1 train, got %d", len(got.Trains))
	}
	if got.Trains[0].ID != "t2" {
		t.Fatalf("expected train id t2, got %s", got.Trains[0].ID)
	}
}

func TestDecodeRawScheduleHTMLMalformedRows(t *testing.T) {
	html := `<table><tr data-id="x"></tr></table>`
	if _, err := decodeRawScheduleHTML("html-source", []byte(html)); err == nil {
		t.Fatalf("expected decode error for malformed html rows")
	}
}
