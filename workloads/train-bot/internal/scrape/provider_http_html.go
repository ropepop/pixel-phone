package scrape

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

type HTTPHTMLProvider struct {
	name      string
	urlTmpl   string
	userAgent string
	client    *http.Client
}

func NewHTTPHTMLProvider(name string, urlTemplate string, userAgent string, timeout time.Duration) *HTTPHTMLProvider {
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	return &HTTPHTMLProvider{
		name:      name,
		urlTmpl:   urlTemplate,
		userAgent: userAgent,
		client:    &http.Client{Timeout: timeout},
	}
}

func (p *HTTPHTMLProvider) Name() string {
	return p.name
}

func (p *HTTPHTMLProvider) Fetch(ctx context.Context, serviceDate time.Time) (RawSchedule, error) {
	if strings.TrimSpace(p.urlTmpl) == "" {
		return RawSchedule{}, fmt.Errorf("provider %s url is empty", p.name)
	}
	target := strings.ReplaceAll(p.urlTmpl, "{date}", serviceDate.Format("2006-01-02"))
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
	return decodeRawScheduleHTML(p.name, b)
}

var (
	scriptJSONRe = regexp.MustCompile(`(?is)<script[^>]*id=["']schedule-json["'][^>]*>(.*?)</script>`)
	trOpenTagRe  = regexp.MustCompile(`(?is)<tr\b[^>]*>`)
	attrRe       = regexp.MustCompile(`([a-zA-Z0-9:_-]+)\s*=\s*["']([^"']*)["']`)
)

func decodeRawScheduleHTML(sourceName string, b []byte) (RawSchedule, error) {
	content := string(b)
	if match := scriptJSONRe.FindStringSubmatch(content); len(match) == 2 {
		return decodeRawScheduleJSON(sourceName, []byte(strings.TrimSpace(match[1])))
	}

	stopMap := map[string][]RawStop{}
	trains := make([]RawTrain, 0)
	for _, tag := range trOpenTagRe.FindAllString(content, -1) {
		attrs := parseTagAttributes(tag)
		serviceDate := attrValue(attrs, "data-service-date", "data-service_date")
		from := attrValue(attrs, "data-from", "data-from-station", "data-from_station")
		to := attrValue(attrs, "data-to", "data-to-station", "data-to_station")
		departureAt := attrValue(attrs, "data-departure-at", "data-departure_at")
		arrivalAt := attrValue(attrs, "data-arrival-at", "data-arrival_at")
		if serviceDate != "" && from != "" && to != "" && departureAt != "" && arrivalAt != "" {
			dep, err := time.Parse(time.RFC3339, departureAt)
			if err != nil {
				continue
			}
			arr, err := time.Parse(time.RFC3339, arrivalAt)
			if err != nil {
				continue
			}
			train := RawTrain{
				ID:          strings.TrimSpace(attrValue(attrs, "data-id", "data-train-id", "data-train_id")),
				ServiceDate: strings.TrimSpace(serviceDate),
				FromStation: strings.TrimSpace(from),
				ToStation:   strings.TrimSpace(to),
				DepartureAt: dep,
				ArrivalAt:   arr,
			}
			if strings.TrimSpace(train.ID) == "" {
				train.ID = trainID(train)
			}
			trains = append(trains, train)
			continue
		}

		trainIDValue := strings.TrimSpace(attrValue(attrs, "data-train-id", "data-train_id", "data-stop-train-id"))
		station := strings.TrimSpace(attrValue(attrs, "data-stop-station", "data-station-name", "data-station_name"))
		if trainIDValue == "" || station == "" {
			continue
		}
		stop := RawStop{
			StationName: station,
			Seq:         parseInt(attrValue(attrs, "data-stop-seq", "data-stop_seq", "data-seq"), 0),
		}
		if at := strings.TrimSpace(attrValue(attrs, "data-stop-arrival-at", "data-stop-arrival_at", "data-arrival-at")); at != "" {
			if parsed, err := time.Parse(time.RFC3339, at); err == nil {
				stop.ArrivalAt = &parsed
			}
		}
		if dt := strings.TrimSpace(attrValue(attrs, "data-stop-departure-at", "data-stop-departure_at", "data-departure-at")); dt != "" {
			if parsed, err := time.Parse(time.RFC3339, dt); err == nil {
				stop.DepartureAt = &parsed
			}
		}
		stopMap[trainIDValue] = append(stopMap[trainIDValue], stop)
	}

	for i := range trains {
		stops := stopMap[trains[i].ID]
		if len(stops) == 0 {
			continue
		}
		sort.Slice(stops, func(a, b int) bool {
			if stops[a].Seq == stops[b].Seq {
				return strings.ToLower(stops[a].StationName) < strings.ToLower(stops[b].StationName)
			}
			if stops[a].Seq == 0 {
				return false
			}
			if stops[b].Seq == 0 {
				return true
			}
			return stops[a].Seq < stops[b].Seq
		})
		trains[i].Stops = stops
	}

	if len(trains) == 0 {
		return RawSchedule{}, fmt.Errorf("decode %s html: no trains found", sourceName)
	}
	return RawSchedule{
		SourceName: sourceName,
		FetchedAt:  time.Now().UTC(),
		Trains:     trains,
	}, nil
}

func parseTagAttributes(tag string) map[string]string {
	out := map[string]string{}
	matches := attrRe.FindAllStringSubmatch(tag, -1)
	for _, match := range matches {
		if len(match) != 3 {
			continue
		}
		out[strings.ToLower(strings.TrimSpace(match[1]))] = strings.TrimSpace(match[2])
	}
	return out
}

func attrValue(attrs map[string]string, keys ...string) string {
	for _, key := range keys {
		if v := strings.TrimSpace(attrs[strings.ToLower(strings.TrimSpace(key))]); v != "" {
			return v
		}
	}
	return ""
}

func parseInt(v string, fallback int) int {
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return fallback
	}
	return n
}
