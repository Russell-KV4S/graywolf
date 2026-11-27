package configstore

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

func TestSnapshotToAndValidateConfigDB(t *testing.T) {
	dir := t.TempDir()
	srcPath := filepath.Join(dir, "graywolf.db")

	store, err := Open(srcPath)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer store.Close()

	if err := store.UpsertStationConfig(context.Background(), StationConfig{Callsign: "KV4S-9"}); err != nil {
		t.Fatalf("UpsertStationConfig: %v", err)
	}

	snapPath := filepath.Join(dir, "snapshot.db")
	if err := store.SnapshotTo(snapPath); err != nil {
		t.Fatalf("SnapshotTo: %v", err)
	}

	if err := ValidateConfigDB(snapPath); err != nil {
		t.Fatalf("ValidateConfigDB(snapshot): %v", err)
	}

	// Verify the snapshot is readable standalone without WAL sidecars and preserves WAL writes.
	snapStore, err := Open(snapPath)
	if err != nil {
		t.Fatalf("Open(snapshot): %v", err)
	}
	defer snapStore.Close()

	cfg, err := snapStore.GetStationConfig(context.Background())
	if err != nil {
		t.Fatalf("GetStationConfig(snapshot): %v", err)
	}
	if cfg.Callsign != "KV4S-9" {
		t.Fatalf("snapshot callsign = %q, want %q", cfg.Callsign, "KV4S-9")
	}
}

func TestValidateConfigDBRejectsInvalidFiles(t *testing.T) {
	dir := t.TempDir()

	t.Run("non-sqlite", func(t *testing.T) {
		p := filepath.Join(dir, "junk.db")
		if err := os.WriteFile(p, []byte("not a sqlite file at all"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := ValidateConfigDB(p); err == nil {
			t.Fatal("expected error for non-sqlite file")
		}
	})

	t.Run("foreign sqlite schema", func(t *testing.T) {
		p := filepath.Join(dir, "foreign.db")
		db, err := gorm.Open(sqlite.Open(p), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		if err != nil {
			t.Fatal(err)
		}
		if err := db.Exec("CREATE TABLE other (id INTEGER PRIMARY KEY)").Error; err != nil {
			t.Fatal(err)
		}
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
		if err := ValidateConfigDB(p); err == nil {
			t.Fatal("expected error for foreign sqlite schema")
		}
	})

	t.Run("newer schema version", func(t *testing.T) {
		src := filepath.Join(dir, "newer-src.db")
		s, err := Open(src)
		if err != nil {
			t.Fatal(err)
		}
		snap := filepath.Join(dir, "newer-snap.db")
		if err := s.SnapshotTo(snap); err != nil {
			t.Fatal(err)
		}
		_ = s.Close()

		db, err := gorm.Open(sqlite.Open(snap), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
		if err != nil {
			t.Fatal(err)
		}
		if err := db.Exec("PRAGMA user_version = 999999").Error; err != nil {
			t.Fatal(err)
		}
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
		if err := ValidateConfigDB(snap); err == nil {
			t.Fatal("expected error for future schema version")
		}
	})
}

func TestApplyPendingRestore(t *testing.T) {
	dir := t.TempDir()
	livePath := filepath.Join(dir, "graywolf.db")

	// No staged restore -> no-op.
	applied, err := ApplyPendingRestore(livePath)
	if err != nil {
		t.Fatalf("ApplyPendingRestore(no-op): %v", err)
	}
	if applied {
		t.Fatal("expected applied=false when no .restore exists")
	}

	// Create live DB with callsign KV4S-1 and sidecar files.
	live, err := Open(livePath)
	if err != nil {
		t.Fatal(err)
	}
	if err := live.UpsertStationConfig(context.Background(), StationConfig{Callsign: "KV4S-1"}); err != nil {
		t.Fatal(err)
	}
	_ = live.Close()

	if err := os.WriteFile(livePath+"-wal", []byte("stale wal"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(livePath+"-shm", []byte("stale shm"), 0o600); err != nil {
		t.Fatal(err)
	}

	// Create staged restore DB with callsign KV4S-7.
	stagedSrc := filepath.Join(dir, "staged-src.db")
	staged, err := Open(stagedSrc)
	if err != nil {
		t.Fatal(err)
	}
	if err := staged.UpsertStationConfig(context.Background(), StationConfig{Callsign: "KV4S-7"}); err != nil {
		t.Fatal(err)
	}
	pendingPath := livePath + PendingRestoreSuffix
	if err := staged.SnapshotTo(pendingPath); err != nil {
		t.Fatal(err)
	}
	_ = staged.Close()

	applied, err = ApplyPendingRestore(livePath)
	if err != nil {
		t.Fatalf("ApplyPendingRestore: %v", err)
	}
	if !applied {
		t.Fatal("expected applied=true")
	}

	if _, err := os.Stat(pendingPath); !os.IsNotExist(err) {
		t.Fatalf("expected %s to be moved away, err=%v", pendingPath, err)
	}
	if _, err := os.Stat(livePath + "-wal"); !os.IsNotExist(err) {
		t.Fatalf("expected stale -wal to be removed")
	}
	if _, err := os.Stat(livePath + "-shm"); !os.IsNotExist(err) {
		t.Fatalf("expected stale -shm to be removed")
	}
	if _, err := os.Stat(livePath + PreRestoreSuffix); err != nil {
		t.Fatalf("expected %s safety backup: %v", PreRestoreSuffix, err)
	}

	reopened, err := Open(livePath)
	if err != nil {
		t.Fatalf("reopen restored db: %v", err)
	}
	defer reopened.Close()
	cfg, err := reopened.GetStationConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Callsign != "KV4S-7" {
		t.Fatalf("restored callsign = %q, want %q", cfg.Callsign, "KV4S-7")
	}
}
