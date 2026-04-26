package runtime

import (
	"sync"
	"time"
)

type CatalogStatus struct {
	Loaded             bool
	LoadedFromFallback bool
	GeneratedAt        time.Time
	LastRefreshAttempt time.Time
	LastRefreshSuccess time.Time
	LastRefreshError   string
	StopCount          int
	RouteCount         int
}

type TelegramStatus struct {
	LastSuccessAt     time.Time
	LastErrorAt       time.Time
	ConsecutiveErrors int
	LastError         string
	LastUpdateID      int64
}

type DumpStatus struct {
	Pending       int
	LastSuccessAt time.Time
	LastError     string
	LastAttemptAt time.Time
}

type State struct {
	mu sync.RWMutex

	startedAt      time.Time
	webEnabled     bool
	webListening   bool
	webBindAddr    string
	lastFatalError string
	catalog        CatalogStatus
	telegram       TelegramStatus
	dump           DumpStatus
}

func New(startedAt time.Time, webEnabled bool, webBindAddr string) *State {
	return &State{
		startedAt:   startedAt.UTC(),
		webEnabled:  webEnabled,
		webBindAddr: webBindAddr,
	}
}

func (s *State) StartedAt() time.Time {
	if s == nil {
		return time.Time{}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.startedAt
}

func (s *State) SetWebListening(listening bool) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.webListening = listening
}

func (s *State) WebStatus() (enabled bool, listening bool, bindAddr string) {
	if s == nil {
		return false, false, ""
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.webEnabled, s.webListening, s.webBindAddr
}

func (s *State) SetFatalError(message string) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lastFatalError = message
}

func (s *State) LastFatalError() string {
	if s == nil {
		return ""
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastFatalError
}

func (s *State) UpdateCatalog(status CatalogStatus) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.catalog = status
}

func (s *State) CatalogStatus() CatalogStatus {
	if s == nil {
		return CatalogStatus{}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.catalog
}

func (s *State) RecordTelegramSuccess(at time.Time, lastUpdateID int64) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.telegram.LastSuccessAt = at.UTC()
	s.telegram.ConsecutiveErrors = 0
	s.telegram.LastError = ""
	s.telegram.LastErrorAt = time.Time{}
	s.telegram.LastUpdateID = lastUpdateID
}

func (s *State) RecordTelegramError(at time.Time, message string) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.telegram.ConsecutiveErrors++
	s.telegram.LastError = message
	if !at.IsZero() {
		s.telegram.LastErrorAt = at.UTC()
	}
}

func (s *State) TelegramStatus() TelegramStatus {
	if s == nil {
		return TelegramStatus{}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.telegram
}

func (s *State) RecordDumpAttempt(at time.Time) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.dump.LastAttemptAt = at.UTC()
}

func (s *State) RecordDumpSuccess(at time.Time, pending int) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.dump.LastAttemptAt = at.UTC()
	s.dump.LastSuccessAt = at.UTC()
	s.dump.LastError = ""
	s.dump.Pending = pending
}

func (s *State) RecordDumpError(at time.Time, message string, pending int) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.dump.LastAttemptAt = at.UTC()
	s.dump.LastError = message
	s.dump.Pending = pending
}

func (s *State) SetDumpPending(pending int) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.dump.Pending = pending
}

func (s *State) DumpStatus() DumpStatus {
	if s == nil {
		return DumpStatus{}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.dump
}
