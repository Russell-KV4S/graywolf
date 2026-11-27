package webapi

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/chrissnell/graywolf/pkg/configstore"
	"github.com/chrissnell/graywolf/pkg/historydb"
	"github.com/chrissnell/graywolf/pkg/stationcache"
	"github.com/chrissnell/graywolf/pkg/webapi/dto"
)

func newBackupTestServer(t *testing.T, withHistory bool) (*Server, string, string) {
	t.Helper()
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "graywolf.db")
	store, err := configstore.Open(cfgPath)
	if err != nil {
		t.Fatalf("configstore.Open: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	if err := store.UpsertStationConfig(context.Background(), configstore.StationConfig{Callsign: "KV4S-5"}); err != nil {
		t.Fatalf("UpsertStationConfig: %v", err)
	}

	histPath := filepath.Join(dir, "graywolf-history.db")
	if withHistory {
		hdb, err := historydb.Open(histPath)
		if err != nil {
			t.Fatalf("historydb.Open: %v", err)
		}
		now := time.Now().UTC()
		if err := hdb.WriteEntries([]stationcache.CacheEntry{
			{Key: "stn:KV4S-5", Callsign: "KV4S-5", Lat: 33.2, Lon: -87.5, HasPos: true, Symbol: [2]byte{'/', '-'}, Timestamp: now},
		}); err != nil {
			t.Fatalf("WriteEntries: %v", err)
		}
		_ = hdb.Close()
	}

	srv, err := NewServer(Config{
		Store:         store,
		ConfigDBPath:  cfgPath,
		HistoryDBPath: histPath,
		Version:       "0.14.15",
	})
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	return srv, cfgPath, histPath
}

func TestExportStorageBackupZip(t *testing.T) {
	srv, _, _ := newBackupTestServer(t, true)

	t.Run("config only", func(t *testing.T) {
		rec := httptest.NewRecorder()
		srv.exportStorageBackup(rec, httptest.NewRequest(http.MethodGet, "/api/storage/backup", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200: %s", rec.Code, rec.Body.String())
		}
		if ct := rec.Header().Get("Content-Type"); ct != "application/zip" {
			t.Fatalf("Content-Type = %q, want application/zip", ct)
		}

		zr, err := zip.NewReader(bytes.NewReader(rec.Body.Bytes()), int64(rec.Body.Len()))
		if err != nil {
			t.Fatalf("zip.NewReader: %v", err)
		}
		names := map[string]bool{}
		for _, f := range zr.File {
			names[f.Name] = true
		}
		if !names["manifest.json"] || !names["graywolf.db"] {
			t.Fatalf("missing expected entries: %+v", names)
		}
		if names["graywolf-history.db"] {
			t.Fatalf("did not expect history db when include_history is false")
		}
	})

	t.Run("with history", func(t *testing.T) {
		rec := httptest.NewRecorder()
		srv.exportStorageBackup(rec, httptest.NewRequest(http.MethodGet, "/api/storage/backup?include_history=true", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200: %s", rec.Code, rec.Body.String())
		}

		zr, err := zip.NewReader(bytes.NewReader(rec.Body.Bytes()), int64(rec.Body.Len()))
		if err != nil {
			t.Fatalf("zip.NewReader: %v", err)
		}
		names := map[string]bool{}
		for _, f := range zr.File {
			names[f.Name] = true
		}
		if !names["manifest.json"] || !names["graywolf.db"] || !names["graywolf-history.db"] {
			t.Fatalf("missing expected entries with history: %+v", names)
		}
	})
}

func TestRestoreStorageBackupZipAndRawDB(t *testing.T) {
	// Produce a valid backup zip from a separate source server.
	srcSrv, srcCfgPath, _ := newBackupTestServer(t, true)
	backupRec := httptest.NewRecorder()
	srcSrv.exportStorageBackup(backupRec, httptest.NewRequest(http.MethodGet, "/api/storage/backup?include_history=true", nil))
	if backupRec.Code != http.StatusOK {
		t.Fatalf("export backup failed: %d", backupRec.Code)
	}
	zipBytes := backupRec.Body.Bytes()

	// Also snapshot raw graywolf.db for raw-upload testing.
	rawSnap := filepath.Join(t.TempDir(), "raw.db")
	if err := srcSrv.store.SnapshotTo(rawSnap); err != nil {
		t.Fatalf("SnapshotTo: %v", err)
	}
	rawBytes, err := os.ReadFile(rawSnap)
	if err != nil {
		t.Fatal(err)
	}
	_ = srcCfgPath

	t.Run("multipart zip restore stages files and triggers restart", func(t *testing.T) {
		dstSrv, dstCfgPath, dstHistPath := newBackupTestServer(t, false)
		var restarted atomic.Bool
		dstSrv.SetRestartFn(func() { restarted.Store(true) })

		var body bytes.Buffer
		mw := multipart.NewWriter(&body)
		fw, err := mw.CreateFormFile("file", "backup.zip")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := io.Copy(fw, bytes.NewReader(zipBytes)); err != nil {
			t.Fatal(err)
		}
		_ = mw.Close()

		req := httptest.NewRequest(http.MethodPost, "/api/storage/restore", &body)
		req.Header.Set("Content-Type", mw.FormDataContentType())
		rec := httptest.NewRecorder()
		dstSrv.restoreStorageBackup(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("restore zip status = %d, want 200: %s", rec.Code, rec.Body.String())
		}
		var resp dto.StorageRestoreResponse
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatal(err)
		}
		if !resp.Staged || !resp.ConfigRestored || !resp.HistoryRestored || !resp.Restarting {
			t.Fatalf("unexpected restore response: %+v", resp)
		}
		if _, err := os.Stat(dstCfgPath + configstore.PendingRestoreSuffix); err != nil {
			t.Fatalf("expected staged config restore: %v", err)
		}
		if _, err := os.Stat(dstHistPath + configstore.PendingRestoreSuffix); err != nil {
			t.Fatalf("expected staged history restore: %v", err)
		}

		deadline := time.Now().Add(2 * time.Second)
		for !restarted.Load() && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
		if !restarted.Load() {
			t.Fatal("expected restartFn to be invoked")
		}
	})

	t.Run("raw db upload stages config db", func(t *testing.T) {
		dstSrv, dstCfgPath, _ := newBackupTestServer(t, false)
		var body bytes.Buffer
		mw := multipart.NewWriter(&body)
		fw, err := mw.CreateFormFile("file", "graywolf.db")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := fw.Write(rawBytes); err != nil {
			t.Fatal(err)
		}
		_ = mw.Close()

		req := httptest.NewRequest(http.MethodPost, "/api/storage/restore", &body)
		req.Header.Set("Content-Type", mw.FormDataContentType())
		rec := httptest.NewRecorder()
		dstSrv.restoreStorageBackup(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("restore raw db status = %d, want 200: %s", rec.Code, rec.Body.String())
		}
		var resp dto.StorageRestoreResponse
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatal(err)
		}
		if !resp.ConfigRestored || resp.HistoryRestored {
			t.Fatalf("unexpected raw restore response: %+v", resp)
		}
		if _, err := os.Stat(dstCfgPath + configstore.PendingRestoreSuffix); err != nil {
			t.Fatalf("expected staged config restore: %v", err)
		}
	})

	t.Run("rejects invalid upload", func(t *testing.T) {
		dstSrv, dstCfgPath, _ := newBackupTestServer(t, false)
		var body bytes.Buffer
		mw := multipart.NewWriter(&body)
		fw, err := mw.CreateFormFile("file", "bad.txt")
		if err != nil {
			t.Fatal(err)
		}
		_, _ = fw.Write([]byte("hello world not a sqlite db"))
		_ = mw.Close()

		req := httptest.NewRequest(http.MethodPost, "/api/storage/restore", &body)
		req.Header.Set("Content-Type", mw.FormDataContentType())
		rec := httptest.NewRecorder()
		dstSrv.restoreStorageBackup(rec, req)

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("invalid upload status = %d, want 400", rec.Code)
		}
		if _, err := os.Stat(dstCfgPath + configstore.PendingRestoreSuffix); !os.IsNotExist(err) {
			t.Fatal("must not stage invalid upload")
		}
	})
}
