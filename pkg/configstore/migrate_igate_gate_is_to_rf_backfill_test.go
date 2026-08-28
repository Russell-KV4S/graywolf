package configstore

import (
	"context"
	"testing"
)

// TestMigrateIGateGateIsToRfBackfill verifies the backfill flips
// gate_is_to_rf on only when an enabled RF filter exists, reconstructing
// the pre-master-switch "has >=1 enabled rule => IS->RF active" behavior.
func TestMigrateIGateGateIsToRfBackfill(t *testing.T) {
	ctx := context.Background()

	seedConfig := func(s *Store) {
		if err := s.UpsertIGateConfig(ctx, &IGateConfig{
			ID: 1, Enabled: true, Server: "rotate.aprs2.net", Port: 14580,
			GateIsToRf: false,
		}); err != nil {
			t.Fatalf("UpsertIGateConfig: %v", err)
		}
	}
	gateIsToRf := func(s *Store) bool {
		cfg, err := s.GetIGateConfig(ctx)
		if err != nil || cfg == nil {
			t.Fatalf("GetIGateConfig: %v", err)
		}
		return cfg.GateIsToRf
	}

	t.Run("no rules stays off", func(t *testing.T) {
		s := newTestStore(t)
		seedConfig(s)
		if err := migrateIGateGateIsToRfBackfill(s.DB()); err != nil {
			t.Fatalf("migrate: %v", err)
		}
		if gateIsToRf(s) {
			t.Fatal("gate_is_to_rf must stay false when no rules exist")
		}
	})

	t.Run("enabled rule turns it on", func(t *testing.T) {
		s := newTestStore(t)
		seedConfig(s)
		if err := s.CreateIGateRfFilter(ctx, &IGateRfFilter{
			Channel: 1, Type: "message_dest", Pattern: "*", Action: "allow",
			Priority: 100, Enabled: true,
		}); err != nil {
			t.Fatalf("CreateIGateRfFilter: %v", err)
		}
		if err := migrateIGateGateIsToRfBackfill(s.DB()); err != nil {
			t.Fatalf("migrate: %v", err)
		}
		if !gateIsToRf(s) {
			t.Fatal("gate_is_to_rf must be true when an enabled rule exists")
		}
	})

	t.Run("only disabled rule stays off", func(t *testing.T) {
		s := newTestStore(t)
		seedConfig(s)
		f := &IGateRfFilter{
			Channel: 1, Type: "callsign", Pattern: "NW5W-7", Action: "allow",
			Priority: 100, Enabled: true,
		}
		if err := s.CreateIGateRfFilter(ctx, f); err != nil {
			t.Fatalf("CreateIGateRfFilter: %v", err)
		}
		// Force enabled=false: gorm's default:true tag re-applies the
		// default when a bool is left at its zero value on Create, so we
		// disable it explicitly here.
		if err := s.DB().Model(&IGateRfFilter{}).Where("id = ?", f.ID).
			UpdateColumn("enabled", false).Error; err != nil {
			t.Fatalf("disable filter: %v", err)
		}
		if err := migrateIGateGateIsToRfBackfill(s.DB()); err != nil {
			t.Fatalf("migrate: %v", err)
		}
		if gateIsToRf(s) {
			t.Fatal("gate_is_to_rf must stay false when the only rule is disabled")
		}
	})
}
