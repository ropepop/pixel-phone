package scrape

import (
	"context"
	"time"
)

type RawStop struct {
	StationName string
	Seq         int
	ArrivalAt   *time.Time
	DepartureAt *time.Time
	Latitude    *float64
	Longitude   *float64
}

type RawTrain struct {
	ID          string
	TrainNumber string
	ServiceDate string
	FromStation string
	ToStation   string
	DepartureAt time.Time
	ArrivalAt   time.Time
	Stops       []RawStop
}

type RawSchedule struct {
	SourceName string
	FetchedAt  time.Time
	Trains     []RawTrain
}

type SnapshotStop struct {
	StationName string   `json:"station_name"`
	Seq         int      `json:"seq"`
	ArrivalAt   string   `json:"arrival_at,omitempty"`
	DepartureAt string   `json:"departure_at,omitempty"`
	Latitude    *float64 `json:"latitude,omitempty"`
	Longitude   *float64 `json:"longitude,omitempty"`
}

type SnapshotTrain struct {
	ID          string         `json:"id"`
	ServiceDate string         `json:"service_date"`
	FromStation string         `json:"from_station"`
	ToStation   string         `json:"to_station"`
	DepartureAt string         `json:"departure_at"`
	ArrivalAt   string         `json:"arrival_at"`
	Stops       []SnapshotStop `json:"stops,omitempty"`
}

type SnapshotFile struct {
	SourceVersion string          `json:"source_version"`
	Trains        []SnapshotTrain `json:"trains"`
}

type Stats struct {
	ProvidersTried     int
	ProvidersSucceeded int
	TrainsMerged       int
	TrainsDropped      int
	ConflictsResolved  int
	StopsFilledFromB   int
}

type Provider interface {
	Name() string
	Fetch(ctx context.Context, serviceDate time.Time) (RawSchedule, error)
}
