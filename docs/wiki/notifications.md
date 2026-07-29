# New-activity notifications

Clickable popup notifications for new DMs/tactical messages and new
bulletins, plus the unread-badge lag fix that motivated the same change.
Design rationale: `docs/superpowers/specs/2026-07-27-unread-notifications-design.md`.
Execution plan + manual test checklist:
`docs/superpowers/plans/2026-07-27-unread-notifications.md`.

## Why this exists (the bug)

The sidebar/top-bar unread dot used to lag up to 30 seconds behind what
the operator had actually just read:

- **Messages**: `MessageThread.svelte`'s dwell-to-read flow called
  `POST /messages/{id}/read` but never updated
  `messagesStore.svelte.js`'s `conversations` map — the badge only got
  corrected by `messagesTransport.js`'s 30s `refreshConversations()`
  rollup.
- **Bulletins**: `Bulletins.svelte` updated its own local list instantly,
  but `Sidebar.svelte` polled `GET /api/bulletins?...unread_only=true` on
  a completely independent 30s timer with no shared state.

Neither backend mark-read endpoint publishes a push event
(`pkg/messages/service.go`'s `MarkRead`/`MarkUnread`,
`pkg/webapi/bulletins.go`'s `markBulletinRead`/`markAllBulletinsRead` are
plain REST), so the fix is frontend-only: update the shared unread
counters optimistically at the point of the state change. See
[invariant 63](invariants.md).

## Unread-lag fix

| Concern | Where |
|---|---|
| Messages optimistic decrement/rollback | `web/src/lib/messagesStore.svelte.js` (`decrementUnread`, `incrementUnread`) |
| Messages dwell-to-read batching | `web/src/components/messages/MessageThread.svelte` `flushBatch` — decrements per-thread immediately, rolls back on a rejected `markRead` |
| `activeThreadId` cleanup on route leave | `web/src/routes/Messages.svelte` `onMount` cleanup — without this, leaving `/messages` for another route left `activeThreadId` stale, which would permanently suppress popups for that thread (see `shouldNotifyMessage` below) |
| Bulletins shared store | `web/src/lib/bulletinsStore.svelte.js` — single source of truth for inbound bulletins + unread count, replacing Sidebar's old independent poll |
| Bulletins shared poll | `web/src/lib/bulletinsTransport.js` — one 30s poll (started app-wide from `App.svelte`), consumed by both `Sidebar.svelte` and `Bulletins.svelte` |
| "Newly unread" diffing | `web/src/lib/bulletins-diff-core.js` (`diffNewlyUnread`) — pure, unit-tested; also feeds the bulletin notification trigger below |

`Bulletins.svelte` now only polls its own outbound list
(`loadOutboundOnly`); inbound comes from `bulletinsStore.inboundList`.
Mark-read/mark-all-read/delete route through the store's methods so the
Sidebar badge and the page update in the same tick.

## Popup notifications

Two independent axes: **what triggers a notification** (suppression
rules) and **how it's delivered** (in-app toast vs. OS notification, per
operator preference).

| Concern | Where |
|---|---|
| Notification queue (push/cap/dismiss) | `web/src/lib/notifications-core.js` (pure) + `web/src/lib/notificationsStore.svelte.js` (runes wrapper, module singleton) |
| Popup UI | `web/src/components/NotificationPopup.svelte` — mounted unconditionally in `App.svelte` (not gated like `NewsPopup`), since it must be live on every route including the full-bleed map/messages views. Chonky-ui's `toast()`/`<Toaster/>` only accepts a plain string (no click handler, no markup), so this is a small purpose-built component rather than a wrapper around that primitive. |
| Suppression rules (pure) | `web/src/lib/notification-rules-core.js` — `shouldNotifyMessage` (false if the thread is muted or is the currently-open thread), `shouldNotifyBulletin` (false while `Bulletins.svelte` is mounted), `shouldFireOsNotification` (enabled + granted + (hidden or forced)) |
| Messages trigger | `web/src/lib/messagesTransport.js` `applyChange()` -> `maybeNotifyInbound(msg)` — the single funnel both the poll and SSE paths already call. Dedups by message id (bounded `Set`, cleared past 500 entries) so a redelivered message doesn't double-notify. |
| Bulletins trigger | `web/src/lib/bulletinsTransport.js` `poll()` — diffs via `bulletinsStore.replaceInbound()` (uses `diffNewlyUnread`), gates via `shouldNotifyBulletin` |
| Bulletin deep-link | `#/bulletins?focus=<id>` — mirrors the `#/map?focus=CALL&lat=…&lon=…` convention. `Bulletins.svelte` parses `focus` from `querystring`, scrolls the matching row into view, and applies a transient `.is-focused` highlight. |
| Message deep-link | `#/messages?thread=<kind>:<key>` — the existing convention already used throughout `Messages.svelte`. |

### Notification mode (toast / OS / both)

Device-local preference (localStorage, **not** server-synced — this is a
per-device browser-permission concept):

| Concern | Where |
|---|---|
| Mode store | `web/src/lib/settings/notification-prefs-store.svelte.js` — `mode`: `'toast'` (default) \| `'os'` \| `'both'`. `setMode('os'|'both')` triggers the browser permission prompt; a denial falls back to `'toast'` so the operator never lands silently in a dead mode. `supported` feature-detects `window.Notification` rather than hardcoding a platform list — false inside the Android build's in-process WebView (see code-map.md's Android section), so `os`/`both` are hidden from the picker there while toast stays available. |
| OS notification firer | `web/src/lib/osNotify.js` `fireOsNotification(title, body, onClick, {force})` — only fires when the tab is hidden/unfocused, unless `force: true`. This gate exists so the operator isn't double-signaled (in-app popup + OS banner) while already looking at the tab. |
| Preferences UI | `web/src/routes/Preferences.svelte` — "Notifications" `Box`: a mode picker plus a **"Send test notification" button**, usable by any operator (not a dev-only affordance), that fires a real sample through whichever mode is currently selected via `{force: true}` — so the operator can preview exactly what they'll get before relying on it. |

## Troubleshooting: OS notification granted but nothing appears

The Notification API reporting `permission === 'granted'` only means the
*browser* will let the page ask for a notification — it does not mean
the OS will actually display one. `osNotify.js`'s `fireOsNotification`
now logs to the console in both failure shapes so this is diagnosable
from DevTools instead of a silent no-op:

- `[osNotify] suppressed: {...}` — `shouldFireOsNotification` gated it
  (wrong mode, permission not granted, or the tab is focused and `force`
  wasn't set). The logged object shows exactly which condition failed.
- `[osNotify] Notification construction failed: ...` — `new
  Notification()` itself threw.

If neither line appears (the call reached `new Notification(...)`
without error) but no banner shows up, the browser handed the
notification to the OS and the OS or the desktop environment is the one
suppressing it — nothing left in our code to check. On Windows this is
almost always one of:

- **Focus Assist** (Action Center) set to "Priority only" or "Alarms
  only" — silently drops normal app notifications with no error
  anywhere.
- **Settings → System → Notifications** — notifications off globally,
  or specifically disabled for the browser.
- **Chrome's own per-site notification permission** (padlock icon →
  Notifications) showing something other than "Allow", independent of
  what `Notification.permission` reports to the page in some edge
  cases.

None of these are bugs in graywolf's notification code — they're
OS/browser configuration outside a web page's control, which is exactly
why the in-app toast (always available regardless of `mode`) is the
delivery path that doesn't depend on any of this.

## Test coverage

Pure logic is unit-tested (`node --test`, no Playwright/e2e suite in this
repo):

- `bulletins-diff-core.test.js`
- `notifications-core.test.js`
- `notification-rules-core.test.js`

UI/permission behavior (popup placement, OS-notification permission
flows, Android graceful degradation) is covered by the manual checklist
in `docs/superpowers/plans/2026-07-27-unread-notifications.md`, not by
automated tests.
