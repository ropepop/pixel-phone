package bot

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"strings"
	"time"

	"satiksmebot/internal/domain"
	"satiksmebot/internal/runtime"
	"satiksmebot/internal/store"
	"satiksmebot/internal/telegram"
)

type DumpDispatcher struct {
	client       MessageClient
	chatID       string
	interval     time.Duration
	loc          *time.Location
	store        store.Store
	runtimeState *runtime.State
}

func NewDumpDispatcher(client MessageClient, st store.Store, runtimeState *runtime.State, chatID string, interval time.Duration, loc *time.Location) *DumpDispatcher {
	return &DumpDispatcher{
		client:       client,
		chatID:       strings.TrimSpace(chatID),
		interval:     interval,
		loc:          loc,
		store:        st,
		runtimeState: runtimeState,
	}
}

func (d *DumpDispatcher) Run(ctx context.Context) error {
	if d == nil {
		<-ctx.Done()
		return nil
	}
	interval := d.interval
	if interval <= 0 {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	d.refreshPending(ctx)
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if d.store == nil {
				continue
			}
			now := time.Now().UTC()
			item, err := d.store.NextReportDump(ctx, now)
			if err != nil {
				log.Printf("report dump load failed: %v", err)
				d.runtimeState.RecordDumpError(now, err.Error(), 0)
				continue
			}
			if item == nil {
				d.refreshPending(ctx)
				continue
			}
			if d.runtimeState != nil {
				d.runtimeState.RecordDumpAttempt(now)
			}
			if d.client == nil || d.chatID == "" {
				d.refreshPending(ctx)
				continue
			}
			if err := d.client.SendMessage(ctx, d.chatID, item.Payload, telegram.MessageOptions{}); err != nil {
				log.Printf("report dump send failed: %v", err)
				nextAttempt := now.Add(reportDumpBackoff(item.Attempts + 1))
				if updateErr := d.store.UpdateReportDumpFailure(ctx, item.ID, item.Attempts+1, nextAttempt, now, err.Error()); updateErr != nil {
					log.Printf("report dump failure update failed: %v", updateErr)
				}
				if d.runtimeState != nil {
					d.runtimeState.RecordDumpError(now, err.Error(), d.pendingCount(ctx))
				}
				continue
			}
			if err := d.store.DeleteReportDump(ctx, item.ID); err != nil {
				log.Printf("report dump delete failed: %v", err)
				if d.runtimeState != nil {
					d.runtimeState.RecordDumpError(now, err.Error(), d.pendingCount(ctx))
				}
				continue
			}
			if d.runtimeState != nil {
				d.runtimeState.RecordDumpSuccess(now, d.pendingCount(ctx))
			}
		}
	}
}

func (d *DumpDispatcher) EnqueueStop(stop domain.Stop, sighting *domain.StopSighting) {
	if d == nil || sighting == nil {
		return
	}
	d.enqueue(d.formatStop(stop, *sighting), sighting.CreatedAt)
}

func (d *DumpDispatcher) EnqueueVehicle(stop domain.Stop, sighting *domain.VehicleSighting) {
	if d == nil || sighting == nil {
		return
	}
	d.enqueue(d.formatVehicle(stop, *sighting), sighting.CreatedAt)
}

func (d *DumpDispatcher) enqueue(message string, createdAt time.Time) {
	if d.store == nil || strings.TrimSpace(message) == "" {
		return
	}
	now := createdAt.UTC()
	if now.IsZero() {
		now = time.Now().UTC()
	}
	item := store.ReportDumpItem{
		ID:            generateQueueID(),
		Payload:       message,
		Attempts:      0,
		CreatedAt:     now,
		NextAttemptAt: now,
	}
	if err := d.store.EnqueueReportDump(context.Background(), item); err != nil {
		log.Printf("report dump enqueue failed: %v", err)
		if d.runtimeState != nil {
			d.runtimeState.RecordDumpError(time.Now().UTC(), err.Error(), d.pendingCount(context.Background()))
		}
		return
	}
	d.refreshPending(context.Background())
}

func (d *DumpDispatcher) formatStop(stop domain.Stop, sighting domain.StopSighting) string {
	return strings.Join([]string{
		fmt.Sprintf("Kontroles novērojums | %s", d.formatTime(sighting.CreatedAt)),
		fmt.Sprintf("Pietura: %s (%s)", dumpValue(stop.Name), dumpValue(stop.ID)),
		"Tips: pietura",
	}, "\n")
}

func (d *DumpDispatcher) formatVehicle(stop domain.Stop, sighting domain.VehicleSighting) string {
	return strings.Join([]string{
		fmt.Sprintf("Kontroles novērojums | %s", d.formatTime(sighting.CreatedAt)),
		fmt.Sprintf("Pietura: %s (%s)", dumpValue(stop.Name), dumpValue(stop.ID)),
		fmt.Sprintf("Tips: %s %s", localizedModeLabel(sighting.Mode), dumpValue(sighting.RouteLabel)),
		fmt.Sprintf("Virziens: %s", dumpValue(sighting.Direction)),
		fmt.Sprintf("Galamērķis: %s", dumpValue(sighting.Destination)),
	}, "\n")
}

func (d *DumpDispatcher) formatTime(at time.Time) string {
	if d != nil && d.loc != nil {
		at = at.In(d.loc)
	}
	return at.Format("2006-01-02 15:04:05")
}

func dumpValue(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "nav zināms"
	}
	return value
}

func localizedModeLabel(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "bus":
		return "autobuss"
	case "tram":
		return "tramvajs"
	case "trol":
		return "trolejbuss"
	case "minibus":
		return "mikroautobuss"
	case "seasonalbus":
		return "sezonas autobuss"
	case "suburbanbus":
		return "piepilsētas autobuss"
	default:
		return dumpValue(mode)
	}
}

func (d *DumpDispatcher) pendingCount(ctx context.Context) int {
	if d == nil || d.store == nil {
		return 0
	}
	count, err := d.store.PendingReportDumpCount(ctx)
	if err != nil {
		return 0
	}
	return count
}

func (d *DumpDispatcher) refreshPending(ctx context.Context) {
	if d.runtimeState != nil {
		d.runtimeState.SetDumpPending(d.pendingCount(ctx))
	}
}

func reportDumpBackoff(attempts int) time.Duration {
	if attempts <= 1 {
		return time.Second
	}
	backoff := time.Second << min(attempts-1, 8)
	if backoff > 5*time.Minute {
		return 5 * time.Minute
	}
	return backoff
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func generateQueueID() string {
	buf := make([]byte, 12)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("dump-%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}
