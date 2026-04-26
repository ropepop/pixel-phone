package schedule

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadSnapshotFileWithStops(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "2026-02-26.json")
	payload := `{
  "source_version":"snapshot-test",
  "trains":[{
    "id":"t1",
    "service_date":"2026-02-26",
    "from_station":"Riga",
    "to_station":"Jelgava",
    "departure_at":"2026-02-26T08:00:00+02:00",
    "arrival_at":"2026-02-26T09:00:00+02:00",
    "stops":[
      {"station_name":"Riga","seq":1,"departure_at":"2026-02-26T08:00:00+02:00"},
      {"station_name":"Jelgava","seq":2,"arrival_at":"2026-02-26T09:00:00+02:00"}
    ]
  }]
}`
	if err := os.WriteFile(path, []byte(payload), 0o644); err != nil {
		t.Fatalf("write snapshot: %v", err)
	}
	_, trains, stops, err := LoadSnapshotFile(path, "2026-02-26")
	if err != nil {
		t.Fatalf("load snapshot: %v", err)
	}
	if len(trains) != 1 {
		t.Fatalf("expected 1 train, got %d", len(trains))
	}
	if len(stops["t1"]) != 2 {
		t.Fatalf("expected 2 stops, got %d", len(stops["t1"]))
	}
}

func TestLoadSnapshotFileRejectsMismatchedServiceDate(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "2026-02-27.json")
	payload := `{
  "source_version":"snapshot-test",
  "trains":[{
    "id":"t1",
    "service_date":"2026-02-26",
    "from_station":"Riga",
    "to_station":"Jelgava",
    "departure_at":"2026-02-26T08:00:00+02:00",
    "arrival_at":"2026-02-26T09:00:00+02:00",
    "stops":[
      {"station_name":"Riga","seq":1,"departure_at":"2026-02-26T08:00:00+02:00"},
      {"station_name":"Jelgava","seq":2,"arrival_at":"2026-02-26T09:00:00+02:00"}
    ]
  }]
}`
	if err := os.WriteFile(path, []byte(payload), 0o644); err != nil {
		t.Fatalf("write snapshot: %v", err)
	}
	if _, _, _, err := LoadSnapshotFile(path, "2026-02-27"); err == nil {
		t.Fatalf("expected service_date mismatch error")
	}
}
