package historydb

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/chrissnell/graywolf/pkg/stationcache"
)

func TestSnapshotFileAndValidateHistoryDB(t *testing.T) {
	dir := t.TempDir()
	srcPath := filepath.Join(dir, "graywolf-history.db")

	hdb, err := Open(srcPath)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	now := time.Now().UTC()
	if err := hdb.WriteEntries([]stationcache.CacheEntry{
		{
			Key:       "stn:KV4S-9",
			Callsign:  "KV4S-9",
			Lat:       33.2098,
			Lon:       -87.5692,
			HasPos:    true,
			Symbol:    [2]byte{'/', '-'},
			Timestamp: now,
		},
	}); err != nil {
		t.Fatalf("WriteEntries: %v", err)
	}

	snapPath := filepath.Join(dir, "history-snap.db")
	if err := SnapshotFile(srcPath, snapPath); err != nil {
		t.Fatalf("SnapshotFile: %v", err)
	}
	_ = hdb.Close()

	if err := ValidateHistoryDB(snapPath); err != nil {
		t.Fatalf("ValidateHistoryDB: %v", err)
	}

	reopened, err := Open(snapPath)
	if err != nil {
		t.Fatalf("Open(snap): %v", err)
	}
	defer reopened.Close()

	stations, err := reopened.LoadRecent(time.Hour, 100)
	if err != nil {
		t.Fatalf("LoadRecent: %v", err)
	}
	if len(stations) != 1 || stations["stn:KV4S-9"] == nil || stations["stn:KV4S-9"].Callsign != "KV4S-9" {
		t.Fatalf("restored stations = %+v, want 1 entry for KV4S-9", stations)
	}
}

func TestValidateHistoryDBRejectsBadFile(t *testing.T) {
	p := filepath.Join(t.TempDir(), "bad.db")
	if err := os.WriteFile(p, []byte("invalid"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateHistoryDB(p); err == nil {
		t.Fatal("expected error for invalid history file")
	}
}
