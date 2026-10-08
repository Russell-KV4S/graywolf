package pttdevice

import (
	"os"
	"path/filepath"
	"testing"
)

func TestProbe_EmptyPath(t *testing.T) {
	got := Probe("   ")
	if got.Exists || got.CharDevice {
		t.Fatalf("empty path should not exist: %+v", got)
	}
}

func TestProbe_NotPresent(t *testing.T) {
	got := Probe(filepath.Join(t.TempDir(), "aioc-aprs-ptt-does-not-exist"))
	if got.Exists {
		t.Fatalf("missing path reported as existing: %+v", got)
	}
	if got.Detail == "" {
		t.Fatalf("expected an advisory message for a missing path")
	}
}

func TestProbe_RegularFileIsNotCharDevice(t *testing.T) {
	// A path that exists but is a regular file is the classic "typo'd the
	// path and hit a real file" mistake — must surface as exists-but-not-a-
	// char-device so the UI can warn.
	f := filepath.Join(t.TempDir(), "regular")
	if err := os.WriteFile(f, []byte("x"), 0o600); err != nil {
		t.Fatalf("write temp file: %v", err)
	}
	got := Probe(f)
	if !got.Exists {
		t.Fatalf("regular file should exist: %+v", got)
	}
	if got.CharDevice {
		t.Fatalf("regular file wrongly reported as a character device: %+v", got)
	}
}

func TestProbe_CharDevice(t *testing.T) {
	// /dev/null is a character device on the Unix hosts CI runs on. Skip
	// where it isn't present or isn't a char device rather than fail.
	fi, err := os.Stat("/dev/null")
	if err != nil || fi.Mode()&os.ModeCharDevice == 0 {
		t.Skip("/dev/null unavailable or not a char device on this platform")
	}
	got := Probe("/dev/null")
	if !got.Exists || !got.CharDevice {
		t.Fatalf("/dev/null should probe as an existing char device: %+v", got)
	}
}
