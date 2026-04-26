package bot

import (
	"fmt"
	"strings"
)

type CallbackData struct {
	Scope  string
	Action string
	Arg1   string
	Arg2   string
	Args   []string
}

func ParseCallbackData(raw string) (CallbackData, error) {
	parts := strings.Split(raw, ":")
	if len(parts) < 2 {
		return CallbackData{}, fmt.Errorf("invalid callback parts")
	}
	cb := CallbackData{Scope: parts[0], Action: parts[1]}
	if cb.Scope == "" || cb.Action == "" {
		return CallbackData{}, fmt.Errorf("empty scope/action")
	}
	if len(parts) > 2 {
		cb.Args = append(cb.Args, parts[2:]...)
	}
	if len(cb.Args) >= 1 {
		cb.Arg1 = cb.Args[0]
	}
	if len(cb.Args) >= 2 {
		cb.Arg2 = cb.Args[1]
	}
	return cb, nil
}

func BuildCallback(scope string, action string, args ...string) string {
	parts := []string{scope, action}
	parts = append(parts, args...)
	return strings.Join(parts, ":")
}
