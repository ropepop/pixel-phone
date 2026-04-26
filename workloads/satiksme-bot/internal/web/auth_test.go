package web

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"strconv"
	"testing"
	"time"
)

func TestValidateTelegramInitData(t *testing.T) {
	now := time.Date(2026, 3, 10, 10, 0, 0, 0, time.UTC)
	userRaw, _ := json.Marshal(map[string]any{
		"id":         42,
		"first_name": "Test",
	})
	values := url.Values{}
	values.Set("auth_date", strconv.FormatInt(now.Unix(), 10))
	values.Set("query_id", "abc")
	values.Set("user", string(userRaw))

	keys := []string{"auth_date", "query_id", "user"}
	dataCheck := ""
	for i, key := range keys {
		if i > 0 {
			dataCheck += "\n"
		}
		dataCheck += key + "=" + values.Get(key)
	}
	secret := hmacSHA256([]byte("WebAppData"), []byte("bot-token"))
	hash := hmac.New(sha256.New, secret)
	_, _ = hash.Write([]byte(dataCheck))
	values.Set("hash", hex.EncodeToString(hash.Sum(nil)))

	auth, err := validateTelegramInitData(values.Encode(), "bot-token", 5*time.Minute, now)
	if err != nil {
		t.Fatalf("validateTelegramInitData() error = %v", err)
	}
	if auth.User.ID != 42 {
		t.Fatalf("auth.User.ID = %d", auth.User.ID)
	}
}

func TestSessionLanguageCodeDefaultsToLatvian(t *testing.T) {
	if got := sessionLanguageCode(""); got != "lv" {
		t.Fatalf("sessionLanguageCode(\"\") = %q, want lv", got)
	}
	if got := sessionLanguageCode("EN"); got != "en" {
		t.Fatalf("sessionLanguageCode(\"EN\") = %q, want en", got)
	}
}
