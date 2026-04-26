package bot

import (
	"context"
	"fmt"
	"log"
	"strings"
	"time"

	"telegramtrainapp/internal/domain"
)

type reportDumpItem struct {
	entry string
}

func (n *Notifier) Run(ctx context.Context) error {
	if n == nil {
		<-ctx.Done()
		return nil
	}
	interval := n.reportDumpInterval
	if interval <= 0 {
		interval = time.Second
	}
	maxChars := n.reportDumpMaxChars
	if maxChars <= 0 {
		maxChars = 3500
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	pendingItems := make([]reportDumpItem, 0, 16)
	pendingMessages := make([]string, 0, 4)

	for {
		select {
		case <-ctx.Done():
			return nil
		case item := <-n.reportDumpQueue:
			if strings.TrimSpace(item.entry) != "" {
				pendingItems = append(pendingItems, item)
			}
		case <-ticker.C:
			n.drainReportDumpQueue(&pendingItems)
			if n.reportDumpChatID == 0 || n.client == nil {
				pendingItems = pendingItems[:0]
				pendingMessages = pendingMessages[:0]
				continue
			}
			if len(pendingMessages) == 0 && len(pendingItems) > 0 {
				pendingMessages = splitReportDumpItems(pendingItems, maxChars)
				pendingItems = pendingItems[:0]
			}
			if len(pendingMessages) == 0 {
				continue
			}
			message := pendingMessages[0]
			pendingMessages = pendingMessages[1:]
			if err := n.client.SendMessage(ctx, n.reportDumpChatID, message, MessageOptions{}); err != nil {
				log.Printf("report dump send failed: %v", err)
			}
		}
	}
}

func (n *Notifier) enqueueReportDump(item reportDumpItem) {
	if n == nil || n.client == nil || n.reportDumpChatID == 0 || strings.TrimSpace(item.entry) == "" {
		return
	}
	select {
	case n.reportDumpQueue <- item:
	default:
		log.Printf("report dump queue full, dropping batch item")
	}
}

func (n *Notifier) drainReportDumpQueue(pending *[]reportDumpItem) {
	if n == nil || pending == nil {
		return
	}
	for {
		select {
		case item := <-n.reportDumpQueue:
			if strings.TrimSpace(item.entry) != "" {
				*pending = append(*pending, item)
			}
		default:
			return
		}
	}
}

func splitReportDumpItems(items []reportDumpItem, maxChars int) []string {
	if maxChars <= 0 {
		maxChars = 3500
	}
	chunks := make([]string, 0, len(items))
	current := ""
	appendChunk := func(text string) {
		if strings.TrimSpace(text) == "" {
			return
		}
		if current == "" {
			current = text
			return
		}
		if len(current)+2+len(text) <= maxChars {
			current += "\n\n" + text
			return
		}
		chunks = append(chunks, current)
		current = text
	}

	for _, item := range items {
		for _, part := range splitReportDumpText(item.entry, maxChars) {
			appendChunk(part)
		}
	}
	if current != "" {
		chunks = append(chunks, current)
	}
	return chunks
}

func splitReportDumpText(text string, maxChars int) []string {
	text = strings.TrimSpace(text)
	if text == "" {
		return nil
	}
	if len(text) <= maxChars {
		return []string{text}
	}

	parts := make([]string, 0, len(text)/maxChars+1)
	remaining := text
	for len(remaining) > maxChars {
		cut := strings.LastIndex(remaining[:maxChars], "\n")
		if cut < maxChars/2 {
			cut = maxChars
		}
		part := strings.TrimSpace(remaining[:cut])
		if part != "" {
			parts = append(parts, part)
		}
		remaining = strings.TrimSpace(remaining[cut:])
	}
	if remaining != "" {
		parts = append(parts, remaining)
	}
	return parts
}

func (n *Notifier) newRideReportDumpItem(payload RideAlertPayload) reportDumpItem {
	lines := []string{
		fmt.Sprintf("Pārbaudes ziņojums | %s", n.formatDumpTimestamp(payload.ReportedAt)),
		fmt.Sprintf("Vilciena ID: %s", dumpValue(payload.TrainID)),
		fmt.Sprintf("Maršruts: %s -> %s", dumpValue(payload.FromStation), dumpValue(payload.ToStation)),
		fmt.Sprintf("Laiki: %s -> %s", n.formatDumpClock(payload.DepartureAt), n.formatDumpClock(payload.ArrivalAt)),
		fmt.Sprintf("Signāls: %s", n.signalLabel(domain.LanguageLV, payload.Signal)),
	}
	return reportDumpItem{entry: strings.Join(lines, "\n")}
}

func (n *Notifier) newStationSightingDumpItem(payload StationSightingAlertPayload, event domain.StationSighting) reportDumpItem {
	lines := []string{
		fmt.Sprintf("Perona novērojums | %s", n.formatDumpTimestamp(payload.ReportedAt)),
		fmt.Sprintf("Stacija: %s (%s)", dumpValue(payload.StationName), dumpValue(payload.StationID)),
	}
	if event.DestinationStationID != nil || strings.TrimSpace(payload.DestinationStationName) != "" {
		lines = append(lines, fmt.Sprintf(
			"Galamērķis: %s (%s)",
			dumpValue(payload.DestinationStationName),
			dumpValue(ptrString(event.DestinationStationID)),
		))
	}
	if payload.MatchedTrainID != "" {
		lines = append(lines,
			fmt.Sprintf("Atbilstošais vilciena ID: %s", dumpValue(payload.MatchedTrainID)),
			fmt.Sprintf("Atbilstošais maršruts: %s -> %s", dumpValue(payload.MatchedFromStation), dumpValue(payload.MatchedToStation)),
			fmt.Sprintf("Atbilstošie laiki: %s -> %s", n.formatDumpClock(payload.MatchedDepartureAt), n.formatDumpClock(payload.MatchedArrivalAt)),
		)
	}
	return reportDumpItem{entry: strings.Join(lines, "\n")}
}

func (n *Notifier) formatDumpTimestamp(t time.Time) string {
	if t.IsZero() {
		return "unknown"
	}
	if n != nil && n.loc != nil {
		t = t.In(n.loc)
	}
	return t.Format("2006-01-02 15:04:05")
}

func (n *Notifier) formatDumpClock(t time.Time) string {
	if t.IsZero() {
		return "unknown"
	}
	if n != nil && n.loc != nil {
		t = t.In(n.loc)
	}
	return t.Format("15:04")
}

func dumpValue(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "unknown"
	}
	return value
}

func ptrString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
