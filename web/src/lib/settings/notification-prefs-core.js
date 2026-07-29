// Pure logic for notification-prefs-store.svelte.js — kept runes-free so
// it can be unit-tested under `node --test` without a Svelte compile
// step (mirrors the channelsCore.js / releaseNotesCore.js split).

export const VALID_MODES = ['toast', 'os', 'both'];

/** @param {unknown} stored value read back from localStorage */
export function parseMode(stored) {
  return stored === 'os' || stored === 'both' ? stored : 'toast';
}

/** @param {unknown} stored '1'/'0' from localStorage, or null if never set */
export function parseEnabledFlag(stored) {
  return stored === null || stored === undefined ? true : stored === '1';
}

/**
 * Decide what mode to actually persist after requesting the browser's
 * Notification permission for 'os'/'both'. 'toast' never needs
 * permission. A denial (or anything other than 'granted') falls back to
 * 'toast' so the operator never lands silently in a dead mode.
 * @param {string} requestedMode
 * @param {string} permission Notification.permission after the prompt
 */
export function resolveModeAfterPermission(requestedMode, permission) {
  if (requestedMode !== 'os' && requestedMode !== 'both') return 'toast';
  return permission === 'granted' ? requestedMode : 'toast';
}
