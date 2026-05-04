package configstore

import (
	"testing"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
)

func TestMigrateRemoteActionsTables(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := migrateRemoteActionsTables(db); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	for _, table := range []string{"remote_otp_credentials", "remote_action_macros"} {
		var n int
		if err := db.Raw(
			"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
			table,
		).Scan(&n).Error; err != nil {
			t.Fatalf("probe %s: %v", table, err)
		}
		if n != 1 {
			t.Fatalf("table %s missing", table)
		}
	}
	var idx int
	if err := db.Raw(
		"SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_remote_action_macros_target_call'",
	).Scan(&idx).Error; err != nil {
		t.Fatalf("probe idx: %v", err)
	}
	if idx != 1 {
		t.Fatalf("expected target_call index")
	}
}

func TestMigrateRemoteActionsForeignKeySetNull(t *testing.T) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := db.Exec("PRAGMA foreign_keys = ON").Error; err != nil {
		t.Fatalf("fk on: %v", err)
	}
	if err := migrateRemoteActionsTables(db); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO remote_otp_credentials (name, secret_b32, algorithm, digits, period, created_at)
		 VALUES ('NW5W OTP', 'JBSWY3DPEHPK3PXP', 'sha1', 6, 30, datetime('now'))`,
	).Error; err != nil {
		t.Fatalf("insert cred: %v", err)
	}
	if err := db.Exec(
		`INSERT INTO remote_action_macros (target_call, label, action_name, args_string, remote_otp_credential_id, position, created_at, updated_at)
		 VALUES ('KK7XYZ-9', 'unlock', 'unlock', 'door=front', 1, 0, datetime('now'), datetime('now'))`,
	).Error; err != nil {
		t.Fatalf("insert macro: %v", err)
	}
	if err := db.Exec("DELETE FROM remote_otp_credentials WHERE id = 1").Error; err != nil {
		t.Fatalf("delete cred: %v", err)
	}
	var fk *uint
	if err := db.Raw(
		"SELECT remote_otp_credential_id FROM remote_action_macros WHERE id = 1",
	).Scan(&fk).Error; err != nil {
		t.Fatalf("scan fk: %v", err)
	}
	if fk != nil {
		t.Fatalf("expected NULL after FK ON DELETE SET NULL, got %v", *fk)
	}
}
