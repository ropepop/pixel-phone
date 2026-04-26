package util

import (
	"fmt"
	"time"
)

func MustLoadLocation(name string) *time.Location {
	loc, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return loc
}

func FormatMinutesAgo(now, t time.Time) string {
	mins := int(now.Sub(t).Minutes())
	if mins <= 0 {
		return "just now"
	}
	if mins == 1 {
		return "1 min ago"
	}
	return fmt.Sprintf("%d min ago", mins)
}

func ISO(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}

func ParseISO(s string) (time.Time, error) {
	return time.Parse(time.RFC3339, s)
}
