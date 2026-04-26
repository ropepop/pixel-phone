package domain

import "time"

type Catalog struct {
	GeneratedAt time.Time `json:"generatedAt"`
	Stops       []Stop    `json:"stops"`
	Routes      []Route   `json:"routes"`
}

type Stop struct {
	ID            string   `json:"id"`
	LiveID        string   `json:"liveId"`
	Name          string   `json:"name"`
	Latitude      float64  `json:"latitude"`
	Longitude     float64  `json:"longitude"`
	Modes         []string `json:"modes"`
	RouteLabels   []string `json:"routeLabels"`
	NearbyStopIDs []string `json:"nearbyStopIds,omitempty"`
}

type Route struct {
	Label   string   `json:"label"`
	Mode    string   `json:"mode"`
	Name    string   `json:"name"`
	StopIDs []string `json:"stopIds,omitempty"`
}

type StopSighting struct {
	ID        string    `json:"id"`
	StopID    string    `json:"stopId"`
	UserID    int64     `json:"-"`
	CreatedAt time.Time `json:"createdAt"`
}

type VehicleSighting struct {
	ID               string    `json:"id"`
	StopID           string    `json:"stopId"`
	UserID           int64     `json:"-"`
	Mode             string    `json:"mode"`
	RouteLabel       string    `json:"routeLabel"`
	Direction        string    `json:"direction"`
	Destination      string    `json:"destination"`
	DepartureSeconds int       `json:"departureSeconds"`
	LiveRowID        string    `json:"liveRowId,omitempty"`
	ScopeKey         string    `json:"-"`
	CreatedAt        time.Time `json:"createdAt"`
}

type VehicleReportInput struct {
	StopID           string `json:"stopId"`
	Mode             string `json:"mode"`
	RouteLabel       string `json:"routeLabel"`
	Direction        string `json:"direction"`
	Destination      string `json:"destination"`
	DepartureSeconds int    `json:"departureSeconds"`
	LiveRowID        string `json:"liveRowId"`
}

type PublicStopSighting struct {
	ID        string    `json:"id"`
	StopID    string    `json:"stopId"`
	StopName  string    `json:"stopName"`
	CreatedAt time.Time `json:"createdAt"`
}

type PublicVehicleSighting struct {
	ID               string    `json:"id"`
	StopID           string    `json:"stopId"`
	StopName         string    `json:"stopName"`
	Mode             string    `json:"mode"`
	RouteLabel       string    `json:"routeLabel"`
	Direction        string    `json:"direction"`
	Destination      string    `json:"destination"`
	DepartureSeconds int       `json:"departureSeconds"`
	LiveRowID        string    `json:"liveRowId,omitempty"`
	CreatedAt        time.Time `json:"createdAt"`
}

type VisibleSightings struct {
	StopSightings    []PublicStopSighting    `json:"stopSightings"`
	VehicleSightings []PublicVehicleSighting `json:"vehicleSightings"`
}

type LiveVehicle struct {
	ID             string    `json:"id"`
	VehicleCode    string    `json:"vehicleCode,omitempty"`
	Mode           string    `json:"mode"`
	RouteLabel     string    `json:"routeLabel"`
	Direction      string    `json:"direction,omitempty"`
	Latitude       float64   `json:"latitude"`
	Longitude      float64   `json:"longitude"`
	UpdatedAt      time.Time `json:"updatedAt"`
	Heading        int       `json:"heading,omitempty"`
	StopID         string    `json:"stopId,omitempty"`
	StopName       string    `json:"stopName,omitempty"`
	ArrivalSeconds int       `json:"arrivalSeconds,omitempty"`
	LowFloor       bool      `json:"lowFloor,omitempty"`
	SightingCount  int       `json:"sightingCount,omitempty"`
}

type PublicMapPayload struct {
	GeneratedAt  time.Time        `json:"generatedAt"`
	Stops        []Stop           `json:"stops"`
	Sightings    VisibleSightings `json:"sightings"`
	LiveVehicles []LiveVehicle    `json:"liveVehicles"`
}

type ReportResult struct {
	Accepted          bool          `json:"accepted"`
	Deduped           bool          `json:"deduped"`
	CooldownRemaining time.Duration `json:"-"`
	CooldownSeconds   int           `json:"cooldownSeconds,omitempty"`
}
