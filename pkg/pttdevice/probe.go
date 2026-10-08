package pttdevice

import (
	"errors"
	"io/fs"
	"os"
	"strings"
)

// ProbeResult reports what a lightweight, side-effect-free inspection of
// a device path found. It backs the UI's advisory for manually-entered
// PTT device paths (e.g. a udev-renamed /dev/aioc-aprs-ptt).
//
// The probe deliberately does NOT open the device: opening a serial tty
// can toggle DTR/RTS and briefly key the radio, and opening it without
// O_NONBLOCK can block on carrier detect. os.Stat has neither hazard, so
// existence and node-type are all we report — enough to catch the common
// mistakes (typo'd path, pointing at a regular file) without ever
// touching the wire.
type ProbeResult struct {
	// Exists is true when the path resolves to a filesystem node.
	Exists bool
	// CharDevice is true when the node is a character device (the shape
	// every real PTT device — serial, hidraw, gpiochip — takes). A path
	// that exists but is not a char device is almost always a mistake.
	CharDevice bool
	// Detail is a short human-readable summary of the outcome.
	Detail string
}

// Probe inspects path without opening it. It is safe on every platform
// and never keys the radio. An empty path is treated as "not present"
// rather than an error, so callers can probe eagerly as the operator
// types.
func Probe(path string) ProbeResult {
	p := strings.TrimSpace(path)
	if p == "" {
		return ProbeResult{Detail: "no path entered"}
	}
	fi, err := os.Stat(p)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			// Not present is not a failure here: a udev-named device is
			// allowed to be absent at config time and appear on plug-in.
			return ProbeResult{Detail: "not present yet"}
		}
		if errors.Is(err, fs.ErrPermission) {
			return ProbeResult{Detail: "permission denied"}
		}
		return ProbeResult{Detail: "cannot inspect: " + err.Error()}
	}
	if fi.Mode()&os.ModeCharDevice != 0 {
		return ProbeResult{Exists: true, CharDevice: true, Detail: "present"}
	}
	return ProbeResult{Exists: true, CharDevice: false, Detail: "present, but not a character device"}
}
