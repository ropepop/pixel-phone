package stationsearch

import "testing"

func TestNormalize(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name  string
		input string
		want  string
	}{
		{name: "trim and lowercase", input: "  Riga  ", want: "riga"},
		{name: "fold macrons", input: "Rīga", want: "riga"},
		{name: "fold softened consonants", input: "Ķemeri", want: "kemeri"},
		{name: "fold cedilla and accents", input: "Cēsis", want: "cesis"},
		{name: "preserve collapsed spacing", input: "  Ziemeļ-blāzma  ", want: "ziemel blazma"},
	}

	for _, tc := range tests {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := Normalize(tc.input); got != tc.want {
				t.Fatalf("Normalize(%q) = %q, want %q", tc.input, got, tc.want)
			}
		})
	}
}
