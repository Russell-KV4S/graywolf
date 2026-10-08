package configstore

import (
	"fmt"

	"gorm.io/gorm"
)

// migrateIGateGateIsToRfBackfill preserves prior IS->RF behavior across
// the change that made IGateConfig.GateIsToRf the real master switch.
//
// Before that change GateIsToRf was a dead column: the TX governor was
// wired whenever at least one enabled RF filter existed, so operators
// who had configured any enabled gating rule already had IS->RF active.
// Now the governor is wired only when GateIsToRf is set, and the column
// defaults to false — so without this backfill those operators would
// silently stop forwarding IS->RF on upgrade.
//
// The backfill flips the singleton's gate_is_to_rf to true iff at least
// one enabled RF filter exists, exactly reconstructing the old implicit
// on-condition. Fresh databases have no rules, so this is a no-op there
// (the safe default:false stands). Idempotent: guarded by the gate_is_to_rf=0
// predicate and by the migration version.
func migrateIGateGateIsToRfBackfill(tx *gorm.DB) error {
	for _, table := range []string{"i_gate_configs", "i_gate_rf_filters"} {
		var exists int
		if err := tx.Raw(
			"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table,
		).Scan(&exists).Error; err != nil {
			return fmt.Errorf("probe %s: %w", table, err)
		}
		if exists == 0 {
			return nil
		}
	}

	if err := tx.Exec(
		`UPDATE i_gate_configs
		    SET gate_is_to_rf = 1
		  WHERE id = 1
		    AND gate_is_to_rf = 0
		    AND EXISTS (SELECT 1 FROM i_gate_rf_filters WHERE enabled = 1)`,
	).Error; err != nil {
		return fmt.Errorf("backfill gate_is_to_rf: %w", err)
	}
	return nil
}
