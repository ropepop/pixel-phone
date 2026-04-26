package bot

import (
	"errors"
	"strings"
	"testing"
	"time"
)

func TestSanitizeErrorRedactsToken(t *testing.T) {
	token := "123456:secret-token"
	c := NewClient(token, 2*time.Second)

	input := errors.New(`Get "https://api.telegram.org/bot123456:secret-token/getUpdates?timeout=30": context deadline exceeded`)
	out := c.sanitizeError(input)

	if out == nil {
		t.Fatalf("expected sanitized error")
	}
	msg := out.Error()
	if strings.Contains(msg, token) {
		t.Fatalf("token leaked in sanitized error: %s", msg)
	}
	if !strings.Contains(msg, "https://api.telegram.org/bot<redacted>/getUpdates") {
		t.Fatalf("expected redacted URL in error, got: %s", msg)
	}
}
