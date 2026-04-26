package bot

import (
	"strings"
	"testing"
	"time"

	"satiksmebot/internal/domain"
)

func TestFormatVehicleReportDoesNotLeakReporterIdentity(t *testing.T) {
	dispatcher := &DumpDispatcher{loc: time.FixedZone("Riga", 2*60*60)}
	message := dispatcher.formatVehicle(domain.Stop{
		ID:   "3012",
		Name: "Centrāltirgus",
	}, domain.VehicleSighting{
		UserID:      123456,
		Mode:        "tram",
		RouteLabel:  "1",
		Direction:   "b-a",
		Destination: "Imanta",
		CreatedAt:   time.Date(2026, 3, 10, 12, 0, 0, 0, time.UTC),
	})
	if strings.Contains(message, "123456") {
		t.Fatalf("report dump leaked reporter id: %s", message)
	}
	if !strings.Contains(message, "tramvajs 1") {
		t.Fatalf("expected mode/route in dump message: %s", message)
	}
}
