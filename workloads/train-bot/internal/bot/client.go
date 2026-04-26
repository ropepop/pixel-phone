package bot

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Client struct {
	baseURL         string
	redactedBaseURL string
	httpClient      *http.Client
}

func NewClient(token string, timeout time.Duration) *Client {
	baseURL := fmt.Sprintf("https://api.telegram.org/bot%s", token)
	return &Client{
		baseURL:         baseURL,
		redactedBaseURL: "https://api.telegram.org/bot<redacted>",
		httpClient: &http.Client{
			Timeout: timeout,
		},
	}
}

type Update struct {
	UpdateID      int64          `json:"update_id"`
	Message       *Message       `json:"message,omitempty"`
	CallbackQuery *CallbackQuery `json:"callback_query,omitempty"`
}

type Message struct {
	MessageID int64  `json:"message_id"`
	From      *User  `json:"from,omitempty"`
	Chat      Chat   `json:"chat"`
	Date      int64  `json:"date"`
	Text      string `json:"text,omitempty"`
}

type CallbackQuery struct {
	ID      string   `json:"id"`
	From    User     `json:"from"`
	Message *Message `json:"message,omitempty"`
	Data    string   `json:"data,omitempty"`
}

type User struct {
	ID           int64  `json:"id"`
	IsBot        bool   `json:"is_bot"`
	FirstName    string `json:"first_name"`
	LanguageCode string `json:"language_code,omitempty"`
}

type Chat struct {
	ID   int64  `json:"id"`
	Type string `json:"type"`
}

type apiResponse[T any] struct {
	OK          bool   `json:"ok"`
	Result      T      `json:"result"`
	Description string `json:"description,omitempty"`
}

type getUpdatesResult []Update

type MessageOptions struct {
	ParseMode   string
	ReplyMarkup any
}

type MessageEditOptions struct {
	ParseMode   string
	ReplyMarkup any
}

type BotCommand struct {
	Command     string `json:"command"`
	Description string `json:"description"`
}

type WebAppInfo struct {
	URL string `json:"url"`
}

type MenuButtonWebApp struct {
	Type   string      `json:"type"`
	Text   string      `json:"text"`
	WebApp *WebAppInfo `json:"web_app,omitempty"`
}

func (c *Client) GetUpdates(ctx context.Context, offset int64, timeout int) ([]Update, error) {
	q := url.Values{}
	q.Set("timeout", strconv.Itoa(timeout))
	if offset > 0 {
		q.Set("offset", strconv.FormatInt(offset, 10))
	}
	url := c.baseURL + "/getUpdates?" + q.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, c.sanitizeError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return nil, fmt.Errorf("getUpdates status %d: %s", resp.StatusCode, string(b))
	}
	var out apiResponse[getUpdatesResult]
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	if !out.OK {
		return nil, fmt.Errorf("telegram getUpdates failed: %s", out.Description)
	}
	return out.Result, nil
}

func (c *Client) SendMessage(ctx context.Context, chatID int64, text string, opts MessageOptions) error {
	payload := map[string]any{
		"chat_id": chatID,
		"text":    text,
	}
	if opts.ParseMode != "" {
		payload["parse_mode"] = opts.ParseMode
	}
	if opts.ReplyMarkup != nil {
		payload["reply_markup"] = opts.ReplyMarkup
	}
	return c.post(ctx, "/sendMessage", payload)
}

func (c *Client) EditMessageText(ctx context.Context, chatID int64, messageID int64, text string, opts MessageEditOptions) error {
	payload := map[string]any{
		"chat_id":    chatID,
		"message_id": messageID,
		"text":       text,
	}
	if opts.ParseMode != "" {
		payload["parse_mode"] = opts.ParseMode
	}
	if opts.ReplyMarkup != nil {
		payload["reply_markup"] = opts.ReplyMarkup
	}
	return c.post(ctx, "/editMessageText", payload)
}

func (c *Client) EditMessageReplyMarkup(ctx context.Context, chatID int64, messageID int64, replyMarkup any) error {
	payload := map[string]any{
		"chat_id":      chatID,
		"message_id":   messageID,
		"reply_markup": replyMarkup,
	}
	return c.post(ctx, "/editMessageReplyMarkup", payload)
}

func (c *Client) AnswerCallbackQuery(ctx context.Context, callbackID string, text string, showAlert bool) error {
	payload := map[string]any{
		"callback_query_id": callbackID,
	}
	if text != "" {
		payload["text"] = text
	}
	if showAlert {
		payload["show_alert"] = true
	}
	return c.post(ctx, "/answerCallbackQuery", payload)
}

func (c *Client) SetMyCommands(ctx context.Context, commands []BotCommand) error {
	return c.post(ctx, "/setMyCommands", map[string]any{
		"commands": commands,
	})
}

func (c *Client) SetChatMenuButton(ctx context.Context, button MenuButtonWebApp) error {
	return c.post(ctx, "/setChatMenuButton", map[string]any{
		"menu_button": button,
	})
}

func (c *Client) post(ctx context.Context, path string, payload any) error {
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return c.sanitizeError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return fmt.Errorf("telegram %s status %d: %s", path, resp.StatusCode, string(body))
	}
	var out apiResponse[json.RawMessage]
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return err
	}
	if !out.OK {
		return fmt.Errorf("telegram %s failed: %s", path, out.Description)
	}
	return nil
}

func (c *Client) sanitizeError(err error) error {
	if err == nil {
		return nil
	}
	msg := strings.ReplaceAll(err.Error(), c.baseURL, c.redactedBaseURL)
	return errors.New(msg)
}
