package webapi

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/chrissnell/graywolf/pkg/webapi/dto"
)

func postCheckDevice(t *testing.T, path string) (*httptest.ResponseRecorder, dto.CheckDeviceResponse) {
	t.Helper()
	s := &Server{logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	body, _ := json.Marshal(dto.CheckDeviceRequest{DevicePath: path})
	req := httptest.NewRequest(http.MethodPost, "/api/ptt/check-device", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	s.checkPttDevice(rec, req)
	var resp dto.CheckDeviceResponse
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("decode response: %v; body=%s", err, rec.Body.String())
		}
	}
	return rec, resp
}

// An empty device_path is a client mistake — 400, not a 200 with a bogus
// "not present" verdict.
func TestCheckPttDevice_EmptyPathIsBadRequest(t *testing.T) {
	rec, _ := postCheckDevice(t, "  ")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body=%s", rec.Code, rec.Body.String())
	}
}

// A not-yet-present udev path must return 200 with exists=false so the UI
// shows a soft advisory and still lets the operator save (GH #511).
func TestCheckPttDevice_MissingPathIsAdvisoryNot400(t *testing.T) {
	rec, resp := postCheckDevice(t, filepath.Join(t.TempDir(), "aioc-aprs-ptt"))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}
	if resp.Exists || resp.CharDevice {
		t.Fatalf("missing path should not exist: %+v", resp)
	}
}

func TestCheckPttDevice_RegularFileFlaggedNotCharDevice(t *testing.T) {
	f := filepath.Join(t.TempDir(), "regular")
	if err := os.WriteFile(f, []byte("x"), 0o600); err != nil {
		t.Fatalf("write temp file: %v", err)
	}
	rec, resp := postCheckDevice(t, f)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}
	if !resp.Exists || resp.CharDevice {
		t.Fatalf("regular file should exist and not be a char device: %+v", resp)
	}
}
