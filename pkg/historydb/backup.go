package historydb

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/chrissnell/graywolf/pkg/configstore"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// SnapshotFile writes a consistent, WAL-merged copy of the history
// database at srcPath to destPath using SQLite VACUUM INTO.
func SnapshotFile(srcPath, destPath string) error {
	if srcPath == "" || destPath == "" {
		return errors.New("historydb: source and destination paths must be non-empty")
	}
	if _, err := os.Stat(srcPath); err != nil {
		return fmt.Errorf("stat history db %q: %w", srcPath, err)
	}
	absSrc, err := filepath.Abs(srcPath)
	if err != nil {
		return fmt.Errorf("resolve history db %q: %w", srcPath, err)
	}
	db, err := gorm.Open(sqlite.Open(absSrc), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return fmt.Errorf("open history db %q: %w", absSrc, err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return err
	}
	defer sqlDB.Close()

	_ = db.Exec("PRAGMA busy_timeout=5000").Error
	if err := db.Exec("VACUUM INTO ?", destPath).Error; err != nil {
		return fmt.Errorf("historydb vacuum into %q: %w", destPath, err)
	}
	return nil
}

// ValidateHistoryDB checks that path is a valid SQLite database with
// the expected graywolf-history schema.
func ValidateHistoryDB(path string) error {
	if err := configstore.CheckSQLiteHeader(path); err != nil {
		return err
	}
	db, err := gorm.Open(sqlite.Open(path), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		return fmt.Errorf("open candidate history db: %w", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return fmt.Errorf("unwrap candidate history db: %w", err)
	}
	defer sqlDB.Close()

	var integrity string
	if err := db.Raw("PRAGMA quick_check").Scan(&integrity).Error; err != nil {
		return fmt.Errorf("history quick_check failed: %w", err)
	}
	if !strings.EqualFold(strings.TrimSpace(integrity), "ok") {
		return fmt.Errorf("history database failed quick_check: %s", integrity)
	}

	var count int64
	if err := db.Raw(
		"SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'stations'",
	).Scan(&count).Error; err != nil {
		return fmt.Errorf("inspect history schema: %w", err)
	}
	if count == 0 {
		return errors.New("history database missing required 'stations' table")
	}
	return nil
}
