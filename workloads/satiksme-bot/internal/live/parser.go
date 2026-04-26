package live

import (
	"bufio"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"
)

type Row struct {
	Mode             string
	RouteLabel       string
	Direction        string
	DepartureSeconds int
	LiveRowID        string
	Destination      string
	StopID           string
	ArrivalAt        time.Time
	BindingKey       string
	CountdownMins    int
}

const staleDepartureGrace = 90 * time.Second

func Parse(r io.Reader, now time.Time, loc *time.Location) (string, []Row, error) {
	scanner := bufio.NewScanner(r)
	stopID := ""
	rows := make([]Row, 0, 16)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		parts := strings.Split(line, ",")
		if lineNo == 1 {
			if len(parts) != 2 || strings.TrimSpace(parts[0]) != "stop" {
				return "", nil, fmt.Errorf("unexpected stop header line %q", line)
			}
			stopID = strings.TrimSpace(parts[1])
			continue
		}
		if len(parts) < 6 {
			return "", nil, fmt.Errorf("unexpected departures line %q", line)
		}
		secondsOfDay, err := strconv.Atoi(strings.TrimSpace(parts[3]))
		if err != nil {
			return "", nil, fmt.Errorf("parse seconds_of_day on line %d: %w", lineNo, err)
		}
		arrival := arrivalAt(now, loc, secondsOfDay)
		if arrival.Before(now.In(loc).Add(-staleDepartureGrace)) {
			continue
		}
		bindingKey := buildBindingKey(stopID, parts[0], parts[1], parts[2], parts[4], parts[5])
		countdown := int(arrival.Sub(now.In(loc)).Minutes())
		if countdown < 0 {
			countdown = 0
		}
		rows = append(rows, Row{
			Mode:             strings.TrimSpace(parts[0]),
			RouteLabel:       strings.TrimSpace(parts[1]),
			Direction:        strings.TrimSpace(parts[2]),
			DepartureSeconds: secondsOfDay,
			LiveRowID:        strings.TrimSpace(parts[4]),
			Destination:      strings.TrimSpace(parts[5]),
			StopID:           stopID,
			ArrivalAt:        arrival,
			BindingKey:       bindingKey,
			CountdownMins:    countdown,
		})
	}
	if err := scanner.Err(); err != nil {
		return "", nil, fmt.Errorf("scan departures: %w", err)
	}
	return stopID, rows, nil
}

func BuildBindingKey(stopID, mode, route, direction, upstreamID, destination string) string {
	return buildBindingKey(stopID, mode, route, direction, upstreamID, destination)
}

func buildBindingKey(stopID, mode, route, direction, upstreamID, destination string) string {
	mode = strings.TrimSpace(mode)
	route = strings.TrimSpace(route)
	direction = strings.TrimSpace(direction)
	upstreamID = strings.TrimSpace(upstreamID)
	destination = strings.TrimSpace(destination)
	if upstreamID != "" {
		return strings.Join([]string{stopID, mode, route, direction, upstreamID}, "|")
	}
	return strings.Join([]string{stopID, mode, route, direction, destination}, "|")
}

func arrivalAt(now time.Time, loc *time.Location, secondsOfDay int) time.Time {
	base := now.In(loc)
	midnight := time.Date(base.Year(), base.Month(), base.Day(), 0, 0, 0, 0, loc)
	arrival := midnight.Add(time.Duration(secondsOfDay) * time.Second)
	if arrival.Before(base.Add(-2 * time.Hour)) {
		return arrival.Add(24 * time.Hour)
	}
	return arrival
}
