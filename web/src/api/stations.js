import { api } from '../lib/api.js';

// --- Stations -------------------------------------------------------------

/**
 * GET /api/stations/alerts — stations currently in Emergency status
 * (Mic-E message code 0, APRS101 ch 10 table 8), regardless of the
 * caller's map viewport. Polled by stationAlertsTransport.js to drive
 * the popup/OS/sound notification.
 * @returns {Promise<Array<{callsign: string, status_code: number, status_text: string, lat: number, lon: number, last_heard: string}>>}
 */
export function listStationAlerts() {
  return api.get('/stations/alerts');
}
