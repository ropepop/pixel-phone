package scrape

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type HTTPJSONProvider struct {
	name      string
	urlTmpl   string
	userAgent string
	client    *http.Client
}

func NewHTTPJSONProvider(name string, urlTemplate string, userAgent string, timeout time.Duration) *HTTPJSONProvider {
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	return &HTTPJSONProvider{
		name:      name,
		urlTmpl:   urlTemplate,
		userAgent: userAgent,
		client:    &http.Client{Timeout: timeout},
	}
}

func (p *HTTPJSONProvider) Name() string {
	return p.name
}

func (p *HTTPJSONProvider) Fetch(ctx context.Context, serviceDate time.Time) (RawSchedule, error) {
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
	return decodeRawScheduleJSON(p.name, b)
}

type inputSchedule struct {
	Trains []inputTrain `json:"trains"`
}

type inputTrain struct {
	ID          string      `json:"id"`
	ServiceDate string      `json:"service_date"`
	FromStation string      `json:"from_station"`
	ToStation   string      `json:"to_station"`
	DepartureAt string      `json:"departure_at"`
	ArrivalAt   string      `json:"arrival_at"`
	Stops       []inputStop `json:"stops"`
}

type inputStop struct {
	StationName string `json:"station_name"`
	Seq         int    `json:"seq"`
	ArrivalAt   string `json:"arrival_at"`
	DepartureAt string `json:"departure_at"`
}

func decodeRawScheduleJSON(sourceName string, b []byte) (RawSchedule, error) {
	var payload inputSchedule
	if err := json.Unmarshal(b, &payload); err != nil {
		return RawSchedule{}, fmt.Errorf("decode %s json: %w", sourceName, err)
	}
	out := RawSchedule{SourceName: sourceName, FetchedAt: time.Now().UTC(), Trains: make([]RawTrain, 0, len(payload.Trains))}
	for _, t := range payload.Trains {
		if strings.TrimSpace(t.ServiceDate) == "" || strings.TrimSpace(t.FromStation) == "" || strings.TrimSpace(t.ToStation) == "" || strings.TrimSpace(t.DepartureAt) == "" || strings.TrimSpace(t.ArrivalAt) == "" {
			continue
		}
		dep, err := time.Parse(time.RFC3339, t.DepartureAt)
		if err != nil {
			continue
		}
		arr, err := time.Parse(time.RFC3339, t.ArrivalAt)
		if err != nil {
			continue
		}
		raw := RawTrain{
			ID:          strings.TrimSpace(t.ID),
			ServiceDate: strings.TrimSpace(t.ServiceDate),
			FromStation: strings.TrimSpace(t.FromStation),
			ToStation:   strings.TrimSpace(t.ToStation),
			DepartureAt: dep,
			ArrivalAt:   arr,
			Stops:       make([]RawStop, 0, len(t.Stops)),
		}
		for _, s := range t.Stops {
			if strings.TrimSpace(s.StationName) == "" {
				continue
			}
			stop := RawStop{StationName: strings.TrimSpace(s.StationName), Seq: s.Seq}
			if strings.TrimSpace(s.ArrivalAt) != "" {
				if at, err := time.Parse(time.RFC3339, s.ArrivalAt); err == nil {
					stop.ArrivalAt = &at
				}
			}
			if strings.TrimSpace(s.DepartureAt) != "" {
				if dt, err := time.Parse(time.RFC3339, s.DepartureAt); err == nil {
					stop.DepartureAt = &dt
				}
			}
			raw.Stops = append(raw.Stops, stop)
		}
		out.Trains = append(out.Trains, raw)
	}
	return out, nil
}
