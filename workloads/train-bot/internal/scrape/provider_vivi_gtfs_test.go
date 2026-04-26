package scrape

import (
	"archive/zip"
	"bytes"
	"testing"
	"time"
)

func TestDecodeViviGTFSZip(t *testing.T) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	write := func(name, body string) {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatalf("create %s: %v", name, err)
		}
		if _, err := w.Write([]byte(body)); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	write("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nvivi,Vivi,https://www.vivi.lv,Europe/Riga\n")
	write("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nsvc,1,1,1,1,1,1,1,20260101,20261231\n")
	write("calendar_dates.txt", "service_id,date,exception_type\n")
	write("routes.txt", "route_id,route_short_name,route_long_name,route_desc,route_type,route_url,route_color,route_text_color\nr1,,Riga - Jelgava,,2,,, \n")
	write("trips.txt", "route_id,service_id,trip_id,trip_headsign,shape_id\nr1,svc,6501,Jelgava,\n")
	write("stops.txt", "stop_id,stop_code,stop_name,stop_desc,stop_lat,stop_lon,zone_id,stop_url,location_type,parent_station\ns1,,Riga,,, ,,,,\ns2,,Jelgava,,, ,,,,\n")
	write("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n6501,08:00:00,08:00:00,s1,1,0,0\n6501,08:45:00,08:45:00,s2,2,0,0\n")
	if err := zw.Close(); err != nil {
		t.Fatalf("close zip: %v", err)
	}

	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.FixedZone("EET", 2*3600))
	got, err := decodeViviGTFSZip("vivi-gtfs", buf.Bytes(), serviceDate)
	if err != nil {
		t.Fatalf("decode gtfs: %v", err)
	}
	if len(got.Trains) != 1 {
		t.Fatalf("expected 1 train, got %d", len(got.Trains))
	}
	if got.Trains[0].TrainNumber != "6501" {
		t.Fatalf("unexpected train number: %s", got.Trains[0].TrainNumber)
	}
	if got.Trains[0].FromStation != "Riga" || got.Trains[0].ToStation != "Jelgava" {
		t.Fatalf("unexpected stations: %+v", got.Trains[0])
	}
}
