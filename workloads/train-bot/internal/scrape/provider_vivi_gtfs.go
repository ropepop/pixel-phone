package scrape

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/csv"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

type ViviGTFSProvider struct {
	name      string
	zipURL    string
	userAgent string
	client    *http.Client
}

func NewViviGTFSProvider(name string, zipURL string, userAgent string, timeout time.Duration) *ViviGTFSProvider {
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	return &ViviGTFSProvider{
		name:      name,
		zipURL:    zipURL,
		userAgent: userAgent,
		client:    &http.Client{Timeout: timeout},
	}
}

func (p *ViviGTFSProvider) Name() string {
	return p.name
}

func (p *ViviGTFSProvider) Fetch(ctx context.Context, serviceDate time.Time) (RawSchedule, error) {
	if strings.TrimSpace(p.zipURL) == "" {
		return RawSchedule{}, fmt.Errorf("provider %s url is empty", p.name)
	}
	target := strings.TrimSpace(p.zipURL)
	if _, err := url.Parse(target); err != nil {
		return RawSchedule{}, fmt.Errorf("provider %s invalid URL: %w", p.name, err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return RawSchedule{}, err
	}
	if p.userAgent != "" {
		req.Header.Set("User-Agent", p.userAgent)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return RawSchedule{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return RawSchedule{}, fmt.Errorf("provider %s status %d", p.name, resp.StatusCode)
	}
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return RawSchedule{}, err
	}
	return decodeViviGTFSZip(p.name, b, serviceDate)
}

type gtfsTrip struct {
	RouteID    string
	ServiceID  string
	TripID     string
	Headsign   string
	TrainLabel string
}

type gtfsStopTime struct {
	StopID       string
	StopSequence int
	ArrivalAt    time.Time
	DepartureAt  time.Time
}

type gtfsStopDetail struct {
	Name      string
	Latitude  *float64
	Longitude *float64
}

func decodeViviGTFSZip(sourceName string, zipBytes []byte, serviceDate time.Time) (RawSchedule, error) {
	readerAt := bytes.NewReader(zipBytes)
	zr, err := zip.NewReader(readerAt, int64(len(zipBytes)))
	if err != nil {
		return RawSchedule{}, fmt.Errorf("decode %s gtfs zip: %w", sourceName, err)
	}

	files := map[string]*zip.File{}
	for _, f := range zr.File {
		files[strings.ToLower(strings.TrimSpace(f.Name))] = f
	}

	readCSV := func(name string) ([][]string, error) {
		f, ok := files[strings.ToLower(name)]
		if !ok {
			return nil, fmt.Errorf("missing file %s", name)
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		r := csv.NewReader(rc)
		r.FieldsPerRecord = -1
		records, err := r.ReadAll()
		if err != nil {
			return nil, err
		}
		return records, nil
	}

	calendarRows, err := readCSV("calendar.txt")
	if err != nil {
		return RawSchedule{}, fmt.Errorf("read calendar: %w", err)
	}
	calendarDatesRows, err := readCSV("calendar_dates.txt")
	if err != nil {
		return RawSchedule{}, fmt.Errorf("read calendar_dates: %w", err)
	}
	tripsRows, err := readCSV("trips.txt")
	if err != nil {
		return RawSchedule{}, fmt.Errorf("read trips: %w", err)
	}
	stopTimesRows, err := readCSV("stop_times.txt")
	if err != nil {
		return RawSchedule{}, fmt.Errorf("read stop_times: %w", err)
	}
	stopsRows, err := readCSV("stops.txt")
	if err != nil {
		return RawSchedule{}, fmt.Errorf("read stops: %w", err)
	}

	dateKey := serviceDate.In(serviceDate.Location()).Format("20060102")
	weekdayKey := strings.ToLower(serviceDate.Weekday().String())

	activeServices, err := gtfsActiveServices(calendarRows, calendarDatesRows, dateKey, weekdayKey)
	if err != nil {
		return RawSchedule{}, err
	}
	stopDetails, err := gtfsStopDetails(stopsRows)
	if err != nil {
		return RawSchedule{}, err
	}
	trips, err := gtfsActiveTrips(tripsRows, activeServices)
	if err != nil {
		return RawSchedule{}, err
	}
	stopTimes, err := gtfsTripStopTimes(stopTimesRows, trips, serviceDate)
	if err != nil {
		return RawSchedule{}, err
	}

	out := RawSchedule{
		SourceName: sourceName,
		FetchedAt:  time.Now().UTC(),
		Trains:     make([]RawTrain, 0, len(stopTimes)),
	}
	tripIDs := make([]string, 0, len(stopTimes))
	for tripID := range stopTimes {
		tripIDs = append(tripIDs, tripID)
	}
	sort.Strings(tripIDs)

	for _, tripID := range tripIDs {
		trip, ok := trips[tripID]
		if !ok {
			continue
		}
		sts := stopTimes[tripID]
		if len(sts) < 2 {
			continue
		}
		sort.Slice(sts, func(i, j int) bool { return sts[i].StopSequence < sts[j].StopSequence })
		first := sts[0]
		last := sts[len(sts)-1]
		fromName := strings.TrimSpace(stopDetails[first.StopID].Name)
		toName := strings.TrimSpace(stopDetails[last.StopID].Name)
		if fromName == "" || toName == "" {
			continue
		}
		stops := make([]RawStop, 0, len(sts))
		for _, st := range sts {
			detail := stopDetails[st.StopID]
			stopName := strings.TrimSpace(detail.Name)
			if stopName == "" {
				continue
			}
			arr := st.ArrivalAt
			dep := st.DepartureAt
			stops = append(stops, RawStop{
				StationName: stopName,
				Seq:         st.StopSequence,
				ArrivalAt:   &arr,
				DepartureAt: &dep,
				Latitude:    detail.Latitude,
				Longitude:   detail.Longitude,
			})
		}
		trainNumber := strings.TrimSpace(trip.TripID)
		if trainNumber == "" {
			trainNumber = strings.TrimSpace(trip.TrainLabel)
		}
		out.Trains = append(out.Trains, RawTrain{
			TrainNumber: trainNumber,
			ServiceDate: serviceDate.Format("2006-01-02"),
			FromStation: fromName,
			ToStation:   toName,
			DepartureAt: first.DepartureAt,
			ArrivalAt:   last.ArrivalAt,
			Stops:       stops,
		})
	}
	if len(out.Trains) == 0 {
		return RawSchedule{}, fmt.Errorf("decode %s gtfs: no trains", sourceName)
	}
	return out, nil
}

func gtfsIndex(header []string) map[string]int {
	idx := make(map[string]int, len(header))
	for i, v := range header {
		idx[strings.TrimSpace(strings.ToLower(v))] = i
	}
	return idx
}

func gtfsValue(row []string, idx map[string]int, key string) string {
	i, ok := idx[strings.TrimSpace(strings.ToLower(key))]
	if !ok || i < 0 || i >= len(row) {
		return ""
	}
	return strings.TrimSpace(row[i])
}

func gtfsActiveServices(calendarRows [][]string, calendarDatesRows [][]string, dateKey string, weekdayKey string) (map[string]bool, error) {
	active := map[string]bool{}
	if len(calendarRows) == 0 {
		return active, fmt.Errorf("calendar.txt is empty")
	}
	h := gtfsIndex(calendarRows[0])
	for _, row := range calendarRows[1:] {
		serviceID := gtfsValue(row, h, "service_id")
		if serviceID == "" {
			continue
		}
		startDate := gtfsValue(row, h, "start_date")
		endDate := gtfsValue(row, h, "end_date")
		if startDate == "" || endDate == "" {
			continue
		}
		if dateKey < startDate || dateKey > endDate {
			continue
		}
		if gtfsValue(row, h, weekdayKey) == "1" {
			active[serviceID] = true
		}
	}
	if len(calendarDatesRows) > 1 {
		hEx := gtfsIndex(calendarDatesRows[0])
		for _, row := range calendarDatesRows[1:] {
			if gtfsValue(row, hEx, "date") != dateKey {
				continue
			}
			serviceID := gtfsValue(row, hEx, "service_id")
			switch gtfsValue(row, hEx, "exception_type") {
			case "1":
				active[serviceID] = true
			case "2":
				delete(active, serviceID)
			}
		}
	}
	return active, nil
}

func gtfsStopDetails(stopsRows [][]string) (map[string]gtfsStopDetail, error) {
	if len(stopsRows) == 0 {
		return nil, fmt.Errorf("stops.txt is empty")
	}
	h := gtfsIndex(stopsRows[0])
	out := map[string]gtfsStopDetail{}
	for _, row := range stopsRows[1:] {
		stopID := gtfsValue(row, h, "stop_id")
		stopName := gtfsValue(row, h, "stop_name")
		if stopID == "" || stopName == "" {
			continue
		}
		out[stopID] = gtfsStopDetail{
			Name:      stopName,
			Latitude:  parseGTFSCoord(gtfsValue(row, h, "stop_lat")),
			Longitude: parseGTFSCoord(gtfsValue(row, h, "stop_lon")),
		}
	}
	return out, nil
}

func parseGTFSCoord(raw string) *float64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return nil
	}
	return &value
}

func gtfsActiveTrips(tripsRows [][]string, activeServices map[string]bool) (map[string]gtfsTrip, error) {
	if len(tripsRows) == 0 {
		return nil, fmt.Errorf("trips.txt is empty")
	}
	h := gtfsIndex(tripsRows[0])
	out := map[string]gtfsTrip{}
	for _, row := range tripsRows[1:] {
		serviceID := gtfsValue(row, h, "service_id")
		if !activeServices[serviceID] {
			continue
		}
		tripID := gtfsValue(row, h, "trip_id")
		if tripID == "" {
			continue
		}
		out[tripID] = gtfsTrip{
			RouteID:    gtfsValue(row, h, "route_id"),
			ServiceID:  serviceID,
			TripID:     tripID,
			Headsign:   gtfsValue(row, h, "trip_headsign"),
			TrainLabel: tripID,
		}
	}
	return out, nil
}

func gtfsTripStopTimes(stopTimesRows [][]string, trips map[string]gtfsTrip, serviceDate time.Time) (map[string][]gtfsStopTime, error) {
	if len(stopTimesRows) == 0 {
		return nil, fmt.Errorf("stop_times.txt is empty")
	}
	h := gtfsIndex(stopTimesRows[0])
	out := map[string][]gtfsStopTime{}
	for _, row := range stopTimesRows[1:] {
		tripID := gtfsValue(row, h, "trip_id")
		if _, ok := trips[tripID]; !ok {
			continue
		}
		stopID := gtfsValue(row, h, "stop_id")
		if stopID == "" {
			continue
		}
		seq, err := strconv.Atoi(gtfsValue(row, h, "stop_sequence"))
		if err != nil {
			continue
		}
		arrivalAt, err := parseGTFSTime(gtfsValue(row, h, "arrival_time"), serviceDate)
		if err != nil {
			continue
		}
		departureAt, err := parseGTFSTime(gtfsValue(row, h, "departure_time"), serviceDate)
		if err != nil {
			continue
		}
		out[tripID] = append(out[tripID], gtfsStopTime{
			StopID:       stopID,
			StopSequence: seq,
			ArrivalAt:    arrivalAt,
			DepartureAt:  departureAt,
		})
	}
	return out, nil
}

func parseGTFSTime(raw string, serviceDate time.Time) (time.Time, error) {
	raw = strings.TrimSpace(raw)
	parts := strings.Split(raw, ":")
	if len(parts) < 2 {
		return time.Time{}, fmt.Errorf("invalid gtfs time %q", raw)
	}
	hour, err := strconv.Atoi(parts[0])
	if err != nil {
		return time.Time{}, err
	}
	minute, err := strconv.Atoi(parts[1])
	if err != nil {
		return time.Time{}, err
	}
	second := 0
	if len(parts) > 2 {
		second, _ = strconv.Atoi(parts[2])
	}
	dayOffset := hour / 24
	hour = hour % 24
	base := time.Date(serviceDate.Year(), serviceDate.Month(), serviceDate.Day(), 0, 0, 0, 0, serviceDate.Location())
	return base.AddDate(0, 0, dayOffset).Add(time.Duration(hour)*time.Hour + time.Duration(minute)*time.Minute + time.Duration(second)*time.Second), nil
}
