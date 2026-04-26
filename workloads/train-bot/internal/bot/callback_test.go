package bot

import "testing"

func TestParseCallbackData(t *testing.T) {
	cb, err := ParseCallbackData("report:confirm:train-1:INSPECTION_STARTED")
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if cb.Scope != "report" || cb.Action != "confirm" || cb.Arg1 != "train-1" || cb.Arg2 != "INSPECTION_STARTED" {
		t.Fatalf("unexpected parsed callback: %+v", cb)
	}
}

func TestParseCallbackDataWithExtraArgs(t *testing.T) {
	cb, err := ParseCallbackData("checkin:route_select:a:b:c:d")
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if cb.Scope != "checkin" || cb.Action != "route_select" {
		t.Fatalf("unexpected scope/action %+v", cb)
	}
	if len(cb.Args) != 4 {
		t.Fatalf("expected 4 args, got %d", len(cb.Args))
	}
	if cb.Arg1 != "a" || cb.Arg2 != "b" {
		t.Fatalf("unexpected arg1/arg2 %+v", cb)
	}
}

func TestParseCallbackDataInvalid(t *testing.T) {
	cases := []string{"", "broken", "::"}
	for _, c := range cases {
		if _, err := ParseCallbackData(c); err == nil {
			t.Fatalf("expected error for %q", c)
		}
	}
}
