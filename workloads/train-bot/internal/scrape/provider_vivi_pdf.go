package scrape

import (
	"context"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"rsc.io/pdf"
)

type ViviPDFProvider struct {
	name      string
	pageURL   string
	userAgent string
	client    *http.Client
}

func NewViviPDFProvider(name string, pageURL string, userAgent string, timeout time.Duration) *ViviPDFProvider {
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	return &ViviPDFProvider{
		name:      name,
		pageURL:   pageURL,
		userAgent: userAgent,
		client:    &http.Client{Timeout: timeout},
	}
}

func (p *ViviPDFProvider) Name() string {
	return p.name
}

func (p *ViviPDFProvider) Fetch(ctx context.Context, serviceDate time.Time) (RawSchedule, error) {
	if strings.TrimSpace(p.pageURL) == "" {
		return RawSchedule{}, fmt.Errorf("provider %s page URL is empty", p.name)
	}
	req, err := http.NewRequest(http.MethodGet, p.pageURL, nil)
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
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return RawSchedule{}, err
	}

	basePDFs, changePDFs, err := viviCollectPDFLinks(p.pageURL, string(body), serviceDate)
	if err != nil {
		return RawSchedule{}, err
	}
	if len(basePDFs) == 0 && len(changePDFs) == 0 {
		return RawSchedule{}, fmt.Errorf("provider %s: no schedule pdf links found", p.name)
	}

	merged := map[string]RawTrain{}
	parseAndMerge := func(ctx context.Context, urls []string, override bool) error {
		for _, pdfURL := range urls {
			pdfBytes, err := p.fetchBytes(ctx, pdfURL)
			if err != nil {
				return fmt.Errorf("fetch %s: %w", pdfURL, err)
			}
			trains, err := viviParseSchedulePDF(pdfBytes, serviceDate)
			if err != nil {
				return fmt.Errorf("parse %s: %w", pdfURL, err)
			}
			for _, train := range trains {
				key := viviTrainKey(train)
				if key == "" {
					continue
				}
				if _, exists := merged[key]; exists && !override {
					continue
				}
				merged[key] = train
			}
		}
		return nil
	}

	if err := parseAndMerge(ctx, basePDFs, false); err != nil {
		return RawSchedule{}, err
	}
	if err := parseAndMerge(ctx, changePDFs, true); err != nil {
		return RawSchedule{}, err
	}
	if len(merged) == 0 {
		return RawSchedule{}, fmt.Errorf("provider %s: parsed no trains from pdfs", p.name)
	}

	keys := make([]string, 0, len(merged))
	for k := range merged {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := RawSchedule{
		SourceName: p.name,
		FetchedAt:  time.Now().UTC(),
		Trains:     make([]RawTrain, 0, len(keys)),
	}
	for _, k := range keys {
		out.Trains = append(out.Trains, merged[k])
	}
	return out, nil
}

func (p *ViviPDFProvider) fetchBytes(ctx context.Context, target string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	if p.userAgent != "" {
		req.Header.Set("User-Agent", p.userAgent)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

var viviAnchorPDFRe = regexp.MustCompile(`(?is)<a[^>]*href=["']([^"']+\.pdf)["'][^>]*>(.*?)</a>`)
var stripTagRe = regexp.MustCompile(`(?is)<[^>]+>`)

func viviCollectPDFLinks(pageURL string, htmlBody string, serviceDate time.Time) ([]string, []string, error) {
	baseURL, err := url.Parse(pageURL)
	if err != nil {
		return nil, nil, err
	}

	baseSet := map[string]struct{}{}
	changeSet := map[string]struct{}{}
	matches := viviAnchorPDFRe.FindAllStringSubmatch(htmlBody, -1)
	for _, m := range matches {
		if len(m) != 3 {
			continue
		}
		href := strings.TrimSpace(html.UnescapeString(m[1]))
		if href == "" {
			continue
		}
		u, err := url.Parse(href)
		if err != nil {
			continue
		}
		abs := baseURL.ResolveReference(u).String()
		lower := strings.ToLower(abs)
		linkText := strings.TrimSpace(stripTagRe.ReplaceAllString(html.UnescapeString(m[2]), " "))
		linkText = strings.Join(strings.Fields(linkText), " ")
		switch {
		case strings.Contains(lower, "/uploads/saraksti/"):
			baseSet[abs] = struct{}{}
		case strings.Contains(lower, "/uploads/izmainas/"):
			effDate, ok := viviParseEffectiveDate(linkText, serviceDate.Location(), serviceDate)
			if !ok || !serviceDate.Before(effDate) {
				changeSet[abs] = struct{}{}
			}
		}
	}

	base := make([]string, 0, len(baseSet))
	for u := range baseSet {
		base = append(base, u)
	}
	sort.Strings(base)
	changes := make([]string, 0, len(changeSet))
	for u := range changeSet {
		changes = append(changes, u)
	}
	sort.Strings(changes)
	return base, changes, nil
}

var lvMonthByPrefix = map[string]time.Month{
	"janv":  time.January,
	"febru": time.February,
	"mart":  time.March,
	"apri":  time.April,
	"mai":   time.May,
	"jun":   time.June,
	"jul":   time.July,
	"aug":   time.August,
	"sept":  time.September,
	"okt":   time.October,
	"novem": time.November,
	"decem": time.December,
}

var effectiveDateRe = regexp.MustCompile(`(?i)no\s+(\d{1,2})\.\s*([[:alpha:]\x80-\xff]+)`)

func viviParseEffectiveDate(text string, loc *time.Location, serviceDate time.Time) (time.Time, bool) {
	m := effectiveDateRe.FindStringSubmatch(strings.ToLower(text))
	if len(m) != 3 {
		return time.Time{}, false
	}
	day, err := strconv.Atoi(m[1])
	if err != nil {
		return time.Time{}, false
	}
	monthWord := strings.TrimSpace(m[2])
	var month time.Month
	for prefix, v := range lvMonthByPrefix {
		if strings.HasPrefix(monthWord, prefix) {
			month = v
			break
		}
	}
	if month == 0 {
		return time.Time{}, false
	}
	year := serviceDate.Year()
	return time.Date(year, month, day, 0, 0, 0, 0, loc), true
}

type viviRow struct {
	Station string
	Times   []string
}

var trainNumberRe = regexp.MustCompile(`\b\d{3,5}\b`)
var dotTimeRe = regexp.MustCompile(`\b([0-2]?\d)\.([0-5]\d)\b`)

func viviParseSchedulePDF(pdfBytes []byte, serviceDate time.Time) ([]RawTrain, error) {
	lines, err := viviExtractPDFLines(pdfBytes)
	if err != nil {
		return nil, err
	}
	trainNumbers := make([]string, 0)
	rows := make([]viviRow, 0)
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.Contains(strings.ToLower(trimmed), "vilciena nr") {
			nums := trainNumberRe.FindAllString(trimmed, -1)
			if len(nums) > len(trainNumbers) {
				trainNumbers = nums
			}
			continue
		}
		if len(trainNumbers) == 0 {
			continue
		}
		timeLocs := dotTimeRe.FindAllStringIndex(trimmed, -1)
		if len(timeLocs) < 2 {
			continue
		}
		firstTimeIdx := timeLocs[0][0]
		station := strings.TrimSpace(trimmed[:firstTimeIdx])
		station = strings.Trim(station, "-–—:|")
		station = strings.Join(strings.Fields(station), " ")
		stationLower := strings.ToLower(station)
		if station == "" ||
			strings.Contains(stationLower, "vilciena nr") ||
			strings.Contains(stationLower, "autobuss") ||
			strings.Contains(stationLower, "ceļa nr") ||
			strings.Contains(stationLower, "piezīmes") {
			continue
		}
		tokens := dotTimeRe.FindAllString(trimmed, -1)
		if len(tokens) == 0 {
			continue
		}
		rows = append(rows, viviRow{Station: station, Times: tokens})
	}
	if len(trainNumbers) == 0 || len(rows) == 0 {
		return nil, fmt.Errorf("no train table found")
	}

	out := make([]RawTrain, 0, len(trainNumbers))
	for idx, number := range trainNumbers {
		stops := make([]RawStop, 0, len(rows))
		var prevTime *time.Time
		for seq, row := range rows {
			if idx >= len(row.Times) {
				continue
			}
			t, err := viviParseDotTime(row.Times[idx], serviceDate, prevTime)
			if err != nil {
				continue
			}
			tCopy := t
			stop := RawStop{
				StationName: row.Station,
				Seq:         seq + 1,
				ArrivalAt:   &tCopy,
				DepartureAt: &tCopy,
			}
			stops = append(stops, stop)
			prevTime = &t
		}
		if len(stops) < 2 {
			continue
		}
		dep := *stops[0].DepartureAt
		arr := *stops[len(stops)-1].ArrivalAt
		out = append(out, RawTrain{
			TrainNumber: strings.TrimSpace(number),
			ServiceDate: serviceDate.Format("2006-01-02"),
			FromStation: stops[0].StationName,
			ToStation:   stops[len(stops)-1].StationName,
			DepartureAt: dep,
			ArrivalAt:   arr,
			Stops:       stops,
		})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no trains parsed from pdf")
	}
	return out, nil
}

func viviParseDotTime(raw string, serviceDate time.Time, prev *time.Time) (time.Time, error) {
	m := dotTimeRe.FindStringSubmatch(strings.TrimSpace(raw))
	if len(m) != 3 {
		return time.Time{}, fmt.Errorf("invalid time token %q", raw)
	}
	hour, err := strconv.Atoi(m[1])
	if err != nil {
		return time.Time{}, err
	}
	minute, err := strconv.Atoi(m[2])
	if err != nil {
		return time.Time{}, err
	}
	t := time.Date(serviceDate.Year(), serviceDate.Month(), serviceDate.Day(), hour, minute, 0, 0, serviceDate.Location())
	if prev != nil && t.Before(*prev) {
		t = t.Add(24 * time.Hour)
	}
	return t, nil
}

func viviTrainKey(t RawTrain) string {
	if strings.TrimSpace(t.ServiceDate) == "" {
		return ""
	}
	if strings.TrimSpace(t.TrainNumber) != "" {
		return fmt.Sprintf("%s|%s", t.ServiceDate, strings.ToLower(strings.TrimSpace(t.TrainNumber)))
	}
	return fmt.Sprintf("%s|%s|%s|%s", t.ServiceDate, strings.ToLower(strings.TrimSpace(t.FromStation)), strings.ToLower(strings.TrimSpace(t.ToStation)), t.DepartureAt.UTC().Format("1504"))
}

func viviExtractPDFLines(pdfBytes []byte) ([]string, error) {
	tmp, err := os.CreateTemp("", "vivi-schedule-*.pdf")
	if err != nil {
		return nil, err
	}
	tmpPath := tmp.Name()
	_ = tmp.Close()
	defer os.Remove(tmpPath)
	if err := os.WriteFile(tmpPath, pdfBytes, 0o600); err != nil {
		return nil, err
	}

	doc, err := pdf.Open(tmpPath)
	if err != nil {
		return nil, err
	}

	type textFragment struct {
		X float64
		Y float64
		S string
	}
	lines := make([]string, 0, 1024)
	for i := 1; i <= doc.NumPage(); i++ {
		page := doc.Page(i)
		if page.V.IsNull() {
			continue
		}
		content := page.Content()
		fragments := make([]textFragment, 0, len(content.Text))
		for _, item := range content.Text {
			fragment := strings.TrimSpace(item.S)
			if fragment == "" {
				continue
			}
			fragments = append(fragments, textFragment{X: item.X, Y: item.Y, S: fragment})
		}
		sort.Slice(fragments, func(i, j int) bool {
			if abs(fragments[i].Y-fragments[j].Y) < 1.2 {
				return fragments[i].X < fragments[j].X
			}
			return fragments[i].Y > fragments[j].Y
		})
		curY := 0.0
		lineParts := make([]textFragment, 0, 32)
		flush := func() {
			if len(lineParts) == 0 {
				return
			}
			sort.Slice(lineParts, func(i, j int) bool { return lineParts[i].X < lineParts[j].X })
			parts := make([]string, 0, len(lineParts))
			for _, p := range lineParts {
				parts = append(parts, p.S)
			}
			line := strings.Join(parts, " ")
			line = strings.Join(strings.Fields(line), " ")
			if line != "" {
				lines = append(lines, line)
			}
			lineParts = lineParts[:0]
		}
		for _, fragment := range fragments {
			if len(lineParts) == 0 {
				curY = fragment.Y
				lineParts = append(lineParts, fragment)
				continue
			}
			if abs(curY-fragment.Y) > 1.2 {
				flush()
				curY = fragment.Y
			}
			lineParts = append(lineParts, fragment)
		}
		flush()
	}
	return lines, nil
}

func abs(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}
