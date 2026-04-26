package scrape

import (
	"testing"
	"time"
)

func TestViviCollectPDFLinks(t *testing.T) {
	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.FixedZone("EET", 2*3600))
	html := `
<table>
  <tr>
    <td><a href="/uploads/Saraksti/base-a.pdf">➡️ Base A</a></td>
    <td><a href="/uploads/Izmainas/change-old.pdf">No 20. februāra | Izmaiņas</a></td>
  </tr>
  <tr>
    <td><a href="/uploads/Saraksti/base-b.pdf">➡️ Base B</a></td>
    <td><a href="/uploads/Izmainas/change-future.pdf">No 28. februāra | Izmaiņas</a></td>
  </tr>
</table>`
	base, changes, err := viviCollectPDFLinks("https://www.vivi.lv/lv/informacija-pasazieriem/", html, serviceDate)
	if err != nil {
		t.Fatalf("collect links: %v", err)
	}
	if len(base) != 2 {
		t.Fatalf("expected 2 base links, got %d", len(base))
	}
	if len(changes) != 1 {
		t.Fatalf("expected 1 active change link, got %d", len(changes))
	}
}

func TestViviParseEffectiveDate(t *testing.T) {
	serviceDate := time.Date(2026, 2, 26, 0, 0, 0, 0, time.UTC)
	got, ok := viviParseEffectiveDate("No 28. februāra | Izmaiņas", time.UTC, serviceDate)
	if !ok {
		t.Fatalf("expected date parse success")
	}
	if got.Format("2006-01-02") != "2026-02-28" {
		t.Fatalf("expected 2026-02-28, got %s", got.Format("2006-01-02"))
	}
}
