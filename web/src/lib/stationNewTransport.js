// New-station-heard transport — polls GET /api/stations/roster (every
// currently-heard station, world-scope, regardless of the caller's map
// viewport) and raises a popup/OS/sound notification once a callsign has
// gone unheard long enough (notificationPrefsState.stationNewThresholdSecs,
// default 2h) to count as new again, mirroring stationAlertsTransport.js's
// shared-poll shape.
//
// "New" is staleness-based, not once-per-lifetime: known-stations-core.js's
// persisted, localStorage-backed map (callsign -> last-recorded-heard ms)
// is the baseline, and station-new-diff-core.js's diffNewlyHeard fires
// again whenever a callsign's gap since that recorded timestamp meets the
// configured threshold. The first poll ever run on a device seeds that
// map from whatever's currently active WITHOUT notifying (grandfathering
// in the existing roster) so enabling this doesn't flood an existing
// roster as if every station were brand new.
//
// Exclusions (excludedStationsStore) are checked FIRST, before anything
// else -- an excluded callsign never notifies, full stop, even if it's
// also a favorite (the more specific "no" wins). This is the manual
// escape hatch for a station the digipeater/repeater/weather-station
// heuristics don't catch.
//
// Two independent notification paths per newly-crossed, non-excluded row:
//   - Favorites (favoriteStationsStore): always notify (bypasses the Live
//     Map's RF Only / Direct RX Only filter and the digipeater/repeater
//     exclusion below -- a deliberate per-callsign favorite is assumed to
//     always be wanted), gated by notificationPrefsState.stationFavoriteEnabled.
//   - General: excludes digipeaters (StationRosterDTO.is_digipeater) and
//     repeaters (StationRosterDTO.is_repeater) unconditionally -- both are
//     "non-human infrastructure that can message" per the operator, no
//     toggle -- and, unless notificationPrefsState.stationNewIncludeWeather
//     is on (off by default), weather stations (StationRosterDTO.is_weather_station
//     -- "some may want to see weather stations", so this one's a toggle,
//     not an unconditional exclusion). Also respects the Live Map's RF
//     Only / Direct RX Only filters (read fresh from localStorage on
//     every poll, not dependent on the map being mounted, same precedence
//     as LiveMapV2.svelte's filter effect -- Direct RX Only wins when
//     both are on), gated by notificationPrefsState.stationNewEnabled.
// A favorite takes the favorite path only -- it never also fires the
// general notification, even when both switches are on.
//
// Lifecycle: start() is called once at App startup (see App.svelte),
// alongside messagesTransport's/bulletinsTransport's/
// stationAlertsTransport's start(). Polling itself is always-on for the
// session (so the known-map keeps building even while the operator has
// both master switches off) -- only the notify step is gated by those
// per-type switches, matching the other transports' shape.

import { listStationRoster } from '../api/stations.js';
import { diffNewlyHeard } from './station-new-diff-core.js';
import { parseKnownStations, serializeKnownStations } from './known-stations-core.js';
import { notifications } from './notificationsStore.svelte.js';
import { fireOsNotification } from './osNotify.js';
import { notificationPrefsState, stationNewThresholdMs } from './settings/notification-prefs-store.svelte.js';
import { notificationSoundState } from './settings/notification-sound-store.svelte.js';
import { notificationsLogStore } from './notificationsLogStore.svelte.js';
import { isRfOnly } from './map/rf-only-core.js';
import { directHeardWithin } from './map/direct-rx-core.js';
import { clockOffset } from './map/clock-offset.svelte.js';
import { parseLayerToggles, LAYER_TOGGLES_KEY } from './map/layer-toggles-core.js';
import { mapState } from './map/map-store.svelte.js';
import { favoriteStationsStore } from './favoriteStationsStore.svelte.js';
import { excludedStationsStore } from './excludedStationsStore.svelte.js';

const POLL_MS = 30_000;
const LS_KNOWN_KEY = 'gw-known-stations';

let timer = null;
let started = false;
let stopped = false;

function safeGetItem(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSetItem(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* ignore */
  }
}

let known = parseKnownStations(safeGetItem(LS_KNOWN_KEY));
// A missing key (not merely an empty map) means this device has never
// run this poll before -- distinguishes "grandfather the first batch"
// from "genuinely knows about nothing" on a later, otherwise-empty poll.
let hasBaseline = safeGetItem(LS_KNOWN_KEY) !== null;

function persistKnown() {
  safeSetItem(LS_KNOWN_KEY, JSON.stringify(serializeKnownStations(known)));
}

// Mirrors LiveMapV2.svelte's filter effect (RF reachability filter):
// Direct RX Only is the stricter of the two and wins when both are on;
// RF Only next; no filter otherwise. Read fresh from localStorage each
// poll rather than depending on the Live Map being mounted. Not applied
// to favorites -- see the file header.
function passesMapFilter(row) {
  const toggles = parseLayerToggles(safeGetItem(LAYER_TOGGLES_KEY));
  if (toggles.directRxOnly) {
    const cutoffMs = clockOffset.serverNow() - mapState.timerange * 1000;
    return directHeardWithin(row, cutoffMs);
  }
  if (toggles.rfOnly) {
    return isRfOnly({ positions: [row] });
  }
  return true;
}

function notifyBodyFor(row) {
  if (row.gated) return `${row.callsign} gated from APRS-IS`;
  if (row.direction === 'IS') return `${row.callsign} heard via APRS-IS`;
  return `${row.callsign} heard on RF`;
}

function fire(kind, title, body, href, soundState) {
  notificationsLogStore.add({ kind, title, body, href });
  if (notificationPrefsState.toastEnabled) {
    notifications.push({ kind, title, body, href });
  }
  fireOsNotification(title, body, () => {
    window.location.hash = href;
  });
  soundState.play();
}

async function poll() {
  if (stopped) return;
  try {
    const [rows] = await Promise.all([
      listStationRoster(),
      favoriteStationsStore.load(),
      excludedStationsStore.load(),
    ]);
    const roster = rows || [];

    if (!hasBaseline) {
      const now = Date.now();
      for (const row of roster) {
        if (!row?.callsign) continue;
        const heardAt = Date.parse(row.last_heard);
        known.set(row.callsign, Number.isFinite(heardAt) ? heardAt : now);
      }
      hasBaseline = true;
      persistKnown();
      return;
    }

    const thresholdMs = stationNewThresholdMs(notificationPrefsState.stationNewThresholdSecs);
    const newly = diffNewlyHeard(known, roster, thresholdMs);
    persistKnown();

    for (const row of newly) {
      // lat/lon required, not just callsign -- see stationAlertsTransport.js's
      // identical note; LiveMapV2.svelte's parseFocusFromHash() discards
      // the whole deep-link if either is missing/non-finite.
      if (excludedStationsStore.has(row.callsign)) continue;

      const href = `#/map?focus=${encodeURIComponent(row.callsign)}&lat=${row.lat}&lon=${row.lon}`;
      const body = notifyBodyFor(row);

      if (favoriteStationsStore.has(row.callsign)) {
        if (!notificationPrefsState.stationFavoriteEnabled) continue;
        fire('station-favorite', `Favorite heard: ${row.callsign}`, body, href, notificationSoundState.stationFavorite);
        continue;
      }
      if (row.is_digipeater || row.is_repeater) continue;
      if (row.is_weather_station && !notificationPrefsState.stationNewIncludeWeather) continue;
      if (!notificationPrefsState.stationNewEnabled) continue;
      if (!passesMapFilter(row)) continue;
      fire('station-new', `New station: ${row.callsign}`, body, href, notificationSoundState.stationNew);
    }
  } catch {
    // Leave state as-is; the next poll retries.
  }
}

/** Start the shared poll. Safe to call multiple times; a no-op after the first. */
export function start() {
  if (started || stopped) return;
  started = true;
  poll();
  timer = setInterval(poll, POLL_MS);
}

/** Stop the poll. Exposed for Vite HMR / tests — production never calls this. */
export function stop() {
  stopped = true;
  started = false;
  clearInterval(timer);
  timer = null;
}
