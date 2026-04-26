package schedule

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"telegramtrainapp/internal/domain"
)

type snapshotFile struct {
	SourceVersion string          `json:"source_version"`
	Trains        []snapshotTrain `json:"trains"`
}

type snapshotTrain struct {
	ID          string         `json:"id"`
	ServiceDate string         `json:"service_date"`
	FromStation string         `json:"from_station"`
	ToStation   string         `json:"to_station"`
	DepartureAt string         `json:"departure_at"`
	ArrivalAt   string         `json:"arrival_at"`
	Stops       []snapshotStop `json:"stops"`
}

type snapshotStop struct {
	StationName string   `json:"station_name"`
	Seq         int      `json:"seq"`
	ArrivalAt   string   `json:"arrival_at"`
	DepartureAt string   `json:"departure_at"`
	Latitude    *float64 `json:"latitude"`
	Longitude   *float64 `json:"longitude"`
}

func LoadSnapshotFile(path string, expectedServiceDate string) (string, []domain.TrainInstance, map[string][]domain.TrainStop, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", nil, nil, fmt.Errorf("read snapshot: %w", err)
	}
	var payload snapshotFile
	if err := json.Unmarshal(b, &payload); err != nil {
		return "", nil, nil, fmt.Errorf("decode snapshot: %w", err)
	}
	if payload.SourceVersion == "" {
		payload.SourceVersion = "snapshot-unknown"
	}
	trains := make([]domain.TrainInstance, 0, len(payload.Trains))
	stopsByTrain := make(map[string][]domain.TrainStop, len(payload.Trains))
	for _, t := range payload.Trains {
		dep, err := time.Parse(time.RFC3339, t.DepartureAt)
		if err != nil {
			return "", nil, nil, fmt.Errorf("parse departure for %s: %w", t.ID, err)
		}
		arr, err := time.Parse(time.RFC3339, t.ArrivalAt)
		if err != nil {
			return "", nil, nil, fmt.Errorf("parse arrival for %s: %w", t.ID, err)
		}
		if t.ID == "" || t.ServiceDate == "" {
			return "", nil, nil, fmt.Errorf("invalid train with empty id/service_date")
		}
		if expectedServiceDate != "" && t.ServiceDate != expectedServiceDate {
			return "", nil, nil, fmt.Errorf(
				"train %s has service_date %s (expected %s)",
				t.ID,
				t.ServiceDate,
				expectedServiceDate,
			)
		}
		trains = append(trains, domain.TrainInstance{
			ID:            t.ID,
			ServiceDate:   t.ServiceDate,
			FromStation:   t.FromStation,
			ToStation:     t.ToStation,
			DepartureAt:   dep,
			ArrivalAt:     arr,
			SourceVersion: payload.SourceVersion,
		})
		trainStops := make([]domain.TrainStop, 0, len(t.Stops))
		for _, s := range t.Stops {
			if s.StationName == "" {
				continue
			}
			stop := domain.TrainStop{
				TrainInstanceID: t.ID,
				StationName:     s.StationName,
				Seq:             s.Seq,
				Latitude:        s.Latitude,
				Longitude:       s.Longitude,
			}
			if s.ArrivalAt != "" {
				at, err := time.Parse(time.RFC3339, s.ArrivalAt)
				if err != nil {
					return "", nil, nil, fmt.Errorf("parse stop arrival for %s: %w", t.ID, err)
				}
				stop.ArrivalAt = &at
			}
			if s.DepartureAt != "" {
				dt, err := time.Parse(time.RFC3339, s.DepartureAt)
				if err != nil {
					return "", nil, nil, fmt.Errorf("parse stop departure for %s: %w", t.ID, err)
				}
				stop.DepartureAt = &dt
			}
			trainStops = append(trainStops, stop)
		}
		stopsByTrain[t.ID] = trainStops
	}
	return payload.SourceVersion, trains, stopsByTrain, nil
}
