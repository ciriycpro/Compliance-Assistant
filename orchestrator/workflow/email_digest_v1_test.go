package workflow

import (
	"testing"
	"time"
)

func TestPluralRu(t *testing.T) {
	cases := []struct {
		n    int
		want string
	}{
		{1, "час"},
		{2, "часа"},
		{3, "часа"},
		{4, "часа"},
		{5, "часов"},
		{11, "часов"},
		{12, "часов"},
		{21, "час"},
		{22, "часа"},
		{25, "часов"},
		{100, "часов"},
		{101, "час"},
		{111, "часов"},
		{121, "час"},
	}
	for _, tc := range cases {
		got := pluralRu(tc.n, "час", "часа", "часов")
		if got != tc.want {
			t.Errorf("pluralRu(%d) = %q, want %q", tc.n, got, tc.want)
		}
	}
}

func TestHumanizeDuration(t *testing.T) {
	cases := []struct {
		d    time.Duration
		want string
	}{
		{30 * time.Second, "несколько секунд"},
		{1 * time.Minute, "1 минуту"},
		{5 * time.Minute, "5 минут"},
		{15 * time.Minute, "15 минут"},
		{22 * time.Minute, "22 минуты"},
		{1*time.Hour + 30*time.Minute, "1 час 30 минут"},
		{4*time.Hour + 30*time.Minute, "4 часа 30 минут"},
		{24 * time.Hour, "1 день"},
		{25*time.Hour + 0*time.Minute, "1 день 1 час"},
		{48 * time.Hour, "2 дня"},
		{72 * time.Hour, "3 дня"},
	}
	for _, tc := range cases {
		got := humanizeDuration(tc.d)
		if got != tc.want {
			t.Errorf("humanizeDuration(%v) = %q, want %q", tc.d, got, tc.want)
		}
	}
}

func TestBuildPeriodDescription(t *testing.T) {
	since := time.Date(2026, 5, 16, 10, 0, 0, 0, time.UTC)
	until := time.Date(2026, 5, 16, 14, 30, 0, 0, time.UTC)

	tests := []struct {
		source string
		want   string
	}{
		{"state", "с момента предыдущего обзора (4 часа 30 минут назад)"},
		{"fallback", "за последние 4 часа 30 минут"},
	}
	for _, tc := range tests {
		got := buildPeriodDescription(since, until, tc.source)
		if got != tc.want {
			t.Errorf("buildPeriodDescription(%s): got %q, want %q", tc.source, got, tc.want)
		}
	}
}

func TestBuildPeriodDescription_Days(t *testing.T) {
	since := time.Date(2026, 5, 13, 10, 0, 0, 0, time.UTC)
	until := time.Date(2026, 5, 16, 14, 30, 0, 0, time.UTC)
	got := buildPeriodDescription(since, until, "fallback")
	if got != "за последние 3 дня 4 часа" {
		t.Errorf("got %q", got)
	}
}
