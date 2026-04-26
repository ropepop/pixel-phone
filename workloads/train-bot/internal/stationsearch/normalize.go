package stationsearch

import "strings"

var latvianFoldReplacer = strings.NewReplacer(
	"ā", "a",
	"č", "c",
	"ē", "e",
	"ģ", "g",
	"ī", "i",
	"ķ", "k",
	"ļ", "l",
	"ņ", "n",
	"š", "s",
	"ū", "u",
	"ž", "z",
)

func Normalize(value string) string {
	normalized := strings.ToLower(strings.TrimSpace(value))
	if normalized == "" {
		return ""
	}
	normalized = latvianFoldReplacer.Replace(normalized)
	normalized = strings.ReplaceAll(normalized, "-", " ")
	normalized = strings.Join(strings.Fields(normalized), " ")
	return normalized
}
