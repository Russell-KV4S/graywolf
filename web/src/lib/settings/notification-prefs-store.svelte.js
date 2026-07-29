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
// docs/wiki/code-map.md's Android section), so Preferences.svelte hides
// the os/both options there while still offering plain toast popups.

const LS_MODE = 'gw-notification-mode';

function readMode() {
  try {
    const v = localStorage.getItem(LS_MODE);
    return v === 'os' || v === 'both' ? v : 'toast';
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

export const notificationPrefsState = (() => {
  let mode = $state(readMode());
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
