import { beaconLabel } from './beaconLabel.js';

// Beacons offered by the Messages "Send Beacon" control. Only enabled
// beacons are sendable: a disabled beacon is configured-but-parked and the
// scheduler never transmits it, so surfacing it here would let an operator
// fire a beacon they had deliberately switched off. Order is preserved so
// the picker matches the Beacons page listing.
//
// Each option carries the id the send call needs and a human label (reusing
// beaconLabel so every surface names a beacon the same way). Pure helper so
// the Messages view and its test share one source of truth.
export function sendableBeacons(beacons, stationCallsign = '') {
  if (!Array.isArray(beacons)) return [];
  return beacons
    .filter((b) => b && b.id != null && b.enabled !== false)
    .map((b) => ({ id: b.id, label: beaconLabel(b, stationCallsign) }));
}
