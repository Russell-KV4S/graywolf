package configstore

import "gorm.io/gorm"

// migrateRemoteActionsTables creates the outbound-Actions feature tables
// (remote_otp_credentials, remote_action_macros) using raw SQL so the
// FK ON DELETE SET NULL clause and the target_call index can be
// expressed precisely. The two matching Go models live in
// pkg/remoteactions/models.go and are intentionally NOT added to the
// AutoMigrate list — this migration is the single source of truth.
//
// FK rationale: a macro keeps its row when the bound credential is
// deleted; remote_otp_credential_id falls to NULL and the UI shows the
// macro as "manual OTP" rather than orphaning it.
//
// secret_b32 is plaintext per the single-user-station design (same
// rationale as otp_credentials for inbound Actions; see
// pkg/configstore/migrate_actions.go).
func migrateRemoteActionsTables(tx *gorm.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS remote_otp_credentials (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			name TEXT NOT NULL UNIQUE,
			secret_b32 TEXT NOT NULL,
			algorithm TEXT NOT NULL DEFAULT 'sha1',
			digits INTEGER NOT NULL DEFAULT 6,
			period INTEGER NOT NULL DEFAULT 30,
			created_at DATETIME NOT NULL,
			last_used_at DATETIME
		)`,
		`CREATE TABLE IF NOT EXISTS remote_action_macros (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			target_call TEXT NOT NULL,
			label TEXT NOT NULL,
			action_name TEXT NOT NULL,
			args_string TEXT NOT NULL DEFAULT '',
			remote_otp_credential_id INTEGER,
			position INTEGER NOT NULL DEFAULT 0,
			created_at DATETIME NOT NULL,
			updated_at DATETIME NOT NULL,
			FOREIGN KEY (remote_otp_credential_id)
				REFERENCES remote_otp_credentials(id) ON DELETE SET NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_remote_action_macros_target_call
			ON remote_action_macros(target_call)`,
	}
	for _, s := range stmts {
		if err := tx.Exec(s).Error; err != nil {
			return err
		}
	}
	return nil
}
