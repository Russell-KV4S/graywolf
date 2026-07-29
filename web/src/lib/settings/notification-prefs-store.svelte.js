// Device-local new-activity notification mode. Like ui-scale-store.svelte.js
// and log-prefs-store.svelte.js, this is NOT synced to the server: whether
// an operator wants OS-level browser notifications is a per-device
// browser-permission concept, not a station preference.
//
//   mode — 'toast' (in-app popup only, default) | 'os' (OS notification
//          only) | 'both'.
//
// Requesting 'os'/'both' triggers the browser's permission prompt via
// setMode(); a denial falls back to 'toast' so the operator never lands
// in a mode that silently does nothing. `supported` feature-detects the
// Notification API rather than hardcoding a platform list — it's false
// inside the Android build's in-process WebView (see
// docs/wiki/code-map.md's Android section), so NotificationsSettings.svelte
// hides the os/both options there while still offering plain toast popups.
//
// messageEnabled/bulletinEnabled/stationEmergencyEnabled are independent
// master switches, on by default — turning all off is "notifications
// off"; turning off just one mutes that type's popups (toast + OS) and
// sound while leaving the others alone. Gates messagesTransport.js's
// maybeNotifyInbound, bulletinsTransport.js's poll(), and
// stationAlertsTransport.js's poll() ahead of the toast/OS/sound calls.

import { parseMode, parseEnabledFlag, resolveModeAfterPermission } from './notification-prefs-core.js';

const LS_MODE = 'gw-notification-mode';
const LS_MESSAGE_ENABLED = 'gw-notification-message-enabled';
const LS_BULLETIN_ENABLED = 'gw-notification-bulletin-enabled';
const LS_STATION_EMERGENCY_ENABLED = 'gw-notification-station-emergency-enabled';

function readMode() {
  try {
    return parseMode(localStorage.getItem(LS_MODE));
  } catch {
    return 'toast';
  }
}

function writeMode(v) {
  try {
    localStorage.setItem(LS_MODE, v);
  } catch {
    /* ignore */
  }
}

function readEnabled(key) {
  try {
    return parseEnabledFlag(localStorage.getItem(key));
  } catch {
    return true;
  }
}

function writeEnabled(key, v) {
  try {
    localStorage.setItem(key, v ? '1' : '0');
  } catch {
    /* ignore */
  }
}

export const notificationPrefsState = (() => {
  let mode = $state(readMode());
  let messageEnabled = $state(readEnabled(LS_MESSAGE_ENABLED));
  let bulletinEnabled = $state(readEnabled(LS_BULLETIN_ENABLED));
  let stationEmergencyEnabled = $state(readEnabled(LS_STATION_EMERGENCY_ENABLED));
  const supported = typeof window !== 'undefined' && typeof Notification !== 'undefined';

  return {
    get mode() {
      return mode;
    },
    get supported() {
      return supported;
    },
    get permission() {
      return supported ? Notification.permission : 'unsupported';
    },
    get toastEnabled() {
      return mode === 'toast' || mode === 'both';
    },
    get osEnabled() {
      return supported && (mode === 'os' || mode === 'both') && Notification.permission === 'granted';
    },
    get messageEnabled() {
      return messageEnabled;
    },
    get bulletinEnabled() {
      return bulletinEnabled;
    },
    get stationEmergencyEnabled() {
      return stationEmergencyEnabled;
    },
    setMessageEnabled(v) {
      messageEnabled = !!v;
      writeEnabled(LS_MESSAGE_ENABLED, messageEnabled);
    },
    setBulletinEnabled(v) {
      bulletinEnabled = !!v;
      writeEnabled(LS_BULLETIN_ENABLED, bulletinEnabled);
    },
    setStationEmergencyEnabled(v) {
      stationEmergencyEnabled = !!v;
      writeEnabled(LS_STATION_EMERGENCY_ENABLED, stationEmergencyEnabled);
    },
    /**
     * Called from the Preferences mode picker. Requesting 'os'/'both'
     * triggers the permission prompt when not yet decided; a denial
     * falls back to 'toast'.
     * @param {'toast'|'os'|'both'} next
     */
    async setMode(next) {
      if ((next === 'os' || next === 'both') && supported) {
        let perm = Notification.permission;
        if (perm === 'default') perm = await Notification.requestPermission();
        if (perm !== 'granted') {
          mode = 'toast';
          writeMode('toast');
          return;
        }
      }
      mode = next;
      writeMode(next);
    },
  };
})();
