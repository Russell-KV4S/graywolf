// Pure diffing helper for stationNewTransport.js: on each poll of
// GET /api/stations/roster, decides which rows have gone at least
// `thresholdMs` since this device last recorded hearing them --
// "new station" now means "not heard recently", not "never heard
// before, ever". `known` is a Map<callsign, lastHeardMs> (persisted
// across reloads by known-stations-core.js), mutated in place: every
// roster row updates its entry's timestamp regardless of whether it
// counts as newly-crossed, so a later relaxed filter/threshold doesn't
// retroactively re-fire for a crossing that already happened.
//
// A callsign never seen before (`known.get` is undefined) always counts
// as newly-crossed -- this is what makes stationNewTransport.js's
// first-ever-poll "grandfather the current roster without notifying"
// step necessary, same as before.
//
// thresholdMs = Infinity reproduces the old "once per lifetime" behavior
// (a real gap is always finite, so it can never satisfy `>= Infinity`)
// without needing a separate code path -- see notification-prefs-core.js's
// STATION_NEW_THRESHOLDS' "Never" option.
//
// This function does NOT apply the Live Map RF Only / Direct RX Only
// filter or the digipeater/favorites split -- those are policy decisions
// the transport makes per-row on the returned list, since a favorite
// bypasses the map filter while a plain new-station row doesn't (see
// docs/wiki/notifications.md).

/**
 * @param {Map<string, number>} known callsign -> last-recorded-heard epoch ms (mutated in place)
 * @param {Array<{callsign: string, last_heard: string}>} roster latest GET /stations/roster rows
 * @param {number} thresholdMs how long a callsign must go unheard before it counts as new again
 * @returns {Array<object>} the subset of roster rows that just crossed the threshold
 */
export function diffNewlyHeard(known, roster, thresholdMs) {
  const newly = [];
  for (const row of roster || []) {
    if (!row || !row.callsign) continue;
    const heardAt = Date.parse(row.last_heard);
    if (!Number.isFinite(heardAt)) continue;
    const prev = known.get(row.callsign);
    const crossed = prev === undefined || heardAt - prev >= thresholdMs;
    known.set(row.callsign, heardAt);
    if (crossed) newly.push(row);
  }
  return newly;
}
