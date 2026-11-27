package configstore

import (
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

const (
	// PendingRestoreSuffix marks a validated database file staged beside
	// the live DB path for atomic swap on the next startup/restart.
	PendingRestoreSuffix = ".restore"

	// PreRestoreSuffix is the safety copy of the previous database left
	// behind when ApplyPendingRestore replaces the live file.
	PreRestoreSuffix = ".pre-restore"

	sqliteHeaderMagic = "SQLite format 3\x00"
)

// maxMigrationVersion returns the highest migration version declared in
// schemaMigrations so ValidateConfigDB rejects backups from a newer
// incompatible schema without duplicating a magic constant.
func maxMigrationVersion() int {
	maxVer := 0
	for _, m := range schemaMigrations {
		if m.version > maxVer {
			maxVer = m.version
		}
	}
	return maxVer
}

// SnapshotTo writes a consistent, WAL-merged SQLite copy of the live
// configstore to destPath using VACUUM INTO. destPath must not exist.
func (s *Store) SnapshotTo(destPath string) error {
	if s == nil || s.db == nil {
		return errors.New("configstore: store is not open")
	}
	if destPath == "" {
		return errors.New("configstore: destination path is empty")
	}
	if err := s.db.Exec("VACUUM INTO ?", destPath).Error; err != nil {
		return fmt.Errorf("configstore vacuum into %q: %w", destPath, err)
	}
	return nil
}

// CheckSQLiteHeader verifies the file at path begins with the 16-byte
// SQLite3 magic header.
func CheckSQLiteHeader(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	var hdr [16]byte
	if _, err := io.ReadFull(f, hdr[:]); err != nil {
		return fmt.Errorf("not a valid SQLite database (file too short)")
	}
	if string(hdr[:]) != sqliteHeaderMagic {
		return fmt.Errorf("not a valid SQLite database (bad header)")
	}
	return nil
}

// ValidateConfigDB checks that path is a structurally sound SQLite
// database containing graywolf's core tables at a supported schema
// version.
func ValidateConfigDB(path string) error {
	if err := CheckSQLiteHeader(path); err != nil {
		return err
	}
	db, err := gorm.Open(sqlite.Open(path), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return fmt.Errorf("open candidate config db: %w", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return fmt.Errorf("unwrap candidate config db: %w", err)
	}
	defer sqlDB.Close()

	var integrity string
	if err := db.Raw("PRAGMA integrity_check").Scan(&integrity).Error; err != nil {
		return fmt.Errorf("integrity check failed: %w", err)
	}
	if !strings.EqualFold(strings.TrimSpace(integrity), "ok") {
		return fmt.Errorf("database failed integrity check: %s", integrity)
	}

	for _, table := range []string{"station_configs", "channels"} {
		var count int64
		if err := db.Raw(
			"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
			table,
		).Scan(&count).Error; err != nil {
			return fmt.Errorf("inspect schema table %q: %w", table, err)
		}
		if count == 0 {
			return fmt.Errorf("missing required graywolf table %q", table)
		}
	}

	var userVer int
	if err := db.Raw("PRAGMA user_version").Scan(&userVer).Error; err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	if userVer <= 0 {
		return fmt.Errorf("database has uninitialized schema version (%d)", userVer)
	}
	if maxVer := maxMigrationVersion(); userVer > maxVer {
		return fmt.Errorf("database schema version %d is newer than supported version %d", userVer, maxVer)
	}
	return nil
}

// ApplyPendingRestore checks for a staged `<dbPath>.restore` file and, if
// present, atomically replaces dbPath with it while keeping a safety
// backup at `<dbPath>.pre-restore` and clearing stale WAL/SHM sidecars.
// Must be called when no process holds an open SQLite handle on dbPath.
func ApplyPendingRestore(dbPath string) (bool, error) {
	if dbPath == "" || strings.HasPrefix(dbPath, ":") {
		return false, nil
	}
	pendingPath := dbPath + PendingRestoreSuffix
	if _, err := os.Stat(pendingPath); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, fmt.Errorf("stat pending restore %q: %w", pendingPath, err)
	}

	for _, sidecar := range []string{dbPath + "-wal", dbPath + "-shm"} {
		if err := os.Remove(sidecar); err != nil && !errors.Is(err, os.ErrNotExist) {
			return false, fmt.Errorf("remove stale sidecar %q: %w", sidecar, err)
		}
	}

	prePath := dbPath + PreRestoreSuffix
	movedOld := false
	if _, err := os.Stat(dbPath); err == nil {
		if err := os.Remove(prePath); err != nil && !errors.Is(err, os.ErrNotExist) {
			return false, fmt.Errorf("remove previous backup %q: %w", prePath, err)
		}
		if err := os.Rename(dbPath, prePath); err != nil {
			return false, fmt.Errorf("preserve existing db %q: %w", dbPath, err)
		}
		movedOld = true
	} else if !errors.Is(err, os.ErrNotExist) {
		return false, fmt.Errorf("stat existing db %q: %w", dbPath, err)
	}

	if err := os.Rename(pendingPath, dbPath); err != nil {
		if movedOld {
			_ = os.Rename(prePath, dbPath)
		}
		return false, fmt.Errorf("promote pending restore %q: %w", pendingPath, err)
	}

	_ = os.Chmod(dbPath, 0o600)
	return true, nil
}
