# Android Auto, Wear OS, Battery Optimizations & UV-PRO Bluetooth Serial Spec

**Date:** 2026-11-27
**Target Release:** `v0.14.15` (retag on `feature/aprs-bulletin-board`)
**Scope:** Android (`:app`), Wear OS (`:wear`), Rust modem (`graywolf-modem`), Go core (`pkg/`), and Svelte Web UI (`web/`). iOS, CarPlay, and Apple Watch are explicitly out of scope.

---

## 1. UV-PRO Bluetooth Serial Fix

### Problem
Paired Bluetooth KISS TNCs (such as the Vero VR-N76 / BTech UV-PRO) could hang indefinitely on `Loading...` in the KISS interface drawer or fail RFCOMM socket connection on Android devices that require insecure SPP sockets or differ in MAC address casing.

### Design
- **Frontend (`web/src/routes/Kiss.svelte`, `Kiss.bt.perm.test.js`)**: Track one-shot `*Fetched` state flags for Bluetooth, USB serial, and desktop serial enumeration `$effect` hooks so list state population does not recursively re-trigger enumeration.
- **Android (`android/app/src/main/kotlin/com/nw5w/graywolf/platformsvc/BluetoothFacade.kt`, `BluetoothFacadeTest.kt`)**:
  - Normalize bonded Bluetooth MAC lookups case-insensitively.
  - Fall back to `createInsecureRfcommSocketToServiceRecord` when standard secure RFCOMM connect fails during KISS TNC attachment.

---

## 2. Mobile Battery Optimizations (Priorities #1, #2, #4)

All battery improvements preserve continuous background APRS RX, PTT/TX capability, and APRS-IS/KISS operation:

### Priority #1 — Audio, Modem & Squelch Gating
- **`graywolf-modem/src/rxonly.rs`**: `SquelchGate` (-55 dBFS RMS threshold with 500 ms hangover) suppresses idle silence processing before full demodulation work.
- **`graywolf-modem/src/android/{mod.rs, audio.rs, upcall.rs}`, `lib.rs`**: Gate RX capture cleanly via `StartAudio` / `StopAudio` / `Shutdown` upcalls (`jni_audio_rx_set_active`) and replace per-chunk heap `vec!` allocations with `thread_local!` scratch buffers.
- **`android/app/.../audio/AudioPump.kt`, `audio/AudioTxPump.kt`, `jni/ModemBridge.kt`, `GraywolfService.kt`**: Gate `AudioPump` recording lifecycle on modem readiness/activity and stop idle `AudioTrack` instances in `AudioTxPump` when TX queues drain.

### Priority #2 — GPS Throttling & Geoid Caching
- **`android/app/.../gps/GpsAdapter.kt`**:
  - Throttle `GnssStatus` satellite-count notifications to at most once every 5 seconds or upon count changes.
  - Cache orthometric `GeoidOffsetCache` lookups across nearby fixes.
  - Apply `AdaptiveGpsPolicy` stepping down fix cadence from 10 seconds to 30 seconds while stationary, returning immediately to 10 seconds when movement resumes.

### Priority #4 — Go Backend Idle Wakeup Removal & Telemetry Cadence
- **`pkg/txgovernor/governor.go`**: Replace the 20 Hz polling ticker with event-driven signaling and idle wait backoff.
- **`pkg/platformsvc/client_impl.go`, `pkg/app/wiring.go`**: Relax Android Prometheus/system stats polling cadence to 60 seconds.

*(Priority #3 WebView lifecycle `onPause` throttling was skipped; instead, native Android notifications carry incoming messages cleanly without relying on WebView toast polling.)*

---

## 3. Native APRS Message Notifications & Direct Reply

- **`android/app/.../notify/MessageNotificationManager.kt`**:
  - Runs an authenticated SSE loop against `GET /api/messages/events` (`Authorization: Bearer <token>`) with bounded exponential backoff (1s to 15s) inside `GraywolfService`.
  - Ignores outbound, already-read, ACK-only, and blank messages; maintains up to 8 recent messages per `(thread_kind, thread_key)` thread.
  - Builds `NotificationCompat.MessagingStyle` notifications supporting both 1:1 DMs and `tactical` group threads, with:
    - `SEMANTIC_ACTION_REPLY` + `RemoteInput` (voice dictation, keyboard, and preset quick-reply choices: `73`, `Copy`, `QSL`, `On my way`, `Standing by`, `73 de mobile`),
    - `SEMANTIC_ACTION_MARK_AS_READ`,
    - `NotificationCompat.WearableExtender` offline-available metadata,
    - Content `PendingIntent` deep-linking into `MainActivity` (`EXTRA_HASH_ROUTE` validated against `^#/messages\?thread=(dm|tactical):[A-Za-z0-9_.:-]+$`).
  - Invokes `onInboundMessage` hook so paired Wear OS watches receive live pushes.
- **`android/app/.../notify/MessageReplyReceiver.kt`**: Handles broadcast reply/mark-read actions asynchronously via `goAsync()`, calling `POST /api/messages` and `POST /api/messages/{id}/read`, then updating or canceling the notification cleanly.

---

## 4. Android Auto Projection (`android/app/.../car/`)

- **Dependencies & Manifest**: `androidx.car.app:app:1.7.0`, `res/xml/automotive_app_desc.xml`, `NAVIGATION_TEMPLATES`, `MAP_TEMPLATES`, and `ACCESS_SURFACE` permissions, with `GraywolfCarAppService` (`androidx.car.app.category.NAVIGATION`).
- **Host Validator**: Permits all hosts when `FLAG_DEBUGGABLE` is set (Android Auto Developer Mode / DHU); uses `hosts_allowlist_sample` for release builds.
- **Filter Synchronization**: `LiveMapV2.svelte` -> `WebAppInterface.setMapFilterPrefs` -> `MainActivity` `SharedPreferences` ensures Android Auto and Wear OS mirror the phone map's `rfOnly` and `directRxOnly` filters alongside `/api/stations/exclusions` (including wildcard `*` and base-call matching).
- **Screens (`GraywolfCarMapScreen.kt`)**:
  - **Split View (`MapWithContentTemplate`, Car App API >= 7)**: Displays live projected APRS map alongside newest-first **Station History** capped by `ConstraintManager.CONTENT_LIMIT_TYPE_LIST` (with `ListTemplate` fallback on pre-API 7 head units).
  - **Full-Screen Toggle**: One-tap **Full Map / History** action switches between split `MapWithContentTemplate` and full-screen `NavigationTemplate`.
  - **Map Surface (`AprsMapSurfaceRenderer.kt`, `AprsMapProjection.kt`)**: Draws offline-safe `#0F141A` dark tactical vector grid, dynamic `niceRangeRingKm` distance rings, cached CartoDB dark-matter tiles when online, heading-rotated ownship marker, color-coded APRS pins (red emergency, green direct RX, amber IS-gated, gold selection halo), `visibleArea`-aware centering, touch scroll/pan, scale/button zoom, and GPS auto-follow recenter.
  - **Station Detail & Beacon TX**: Tapping any station focuses it on the map and pushes `StationDetailCarScreen` (`PaneTemplate`) with distance/bearing, path/speed/temperature telemetry, coordinates, and **Navigate** (`CarContext.ACTION_NAVIGATE` Google Maps handoff). Action strip includes one-tap **Send Beacon Now** (`POST /api/beacons/{id}/send`).

---

## 5. Wear OS Companion App (`android/wear/` & `app/.../wear/`)

- **Identity & Off-Grid Data Layer**: Both `:app` and `:wear` share `applicationId = "com.nw5w.graywolf"` and are signed by the same upload keystore. All watch communication flows strictly over Play Services Wearable `MessageClient` RPCs (`sendRequest` / `onRequest`) against the phone's local Go API so every feature functions off-grid over RF.
- **Phone Bridge (`WearBridgeService.kt`, `WearProtocol.kt`)**:
  - `/graywolf/stations_req`: Pre-filtered newest-first station history (`rfOnly`, `directRxOnly`, `exclusions`, distance/bearing).
  - `/graywolf/conversations_req`: Conversation summaries + Favorites (`/api/stations/favorites`) + recent callsigns.
  - `/graywolf/thread_req`, `/graywolf/send_msg_req`, `/graywolf/mark_read_req`: Thread history, RF/IS TX, and read receipts.
  - `/graywolf/inbound_msg`: Real-time push from `MessageNotificationManager` to connected watch nodes.
- **Watch UI (`WearMainActivity.kt`, Compose for Wear OS `1.3.1`, `minSdk = 30`)**:
  - **Home**: Phone RF link status, unread message count, Station History, Messages, and New Message entry points.
  - **Station History & Detail**: Newest-first scrollable station list with distance/bearing, age, emergency highlighting, and Station Detail with one-tap **Message `<callsign>`**.
  - **Messages & Composer**: DM + tactical threads, voice/keyboard callsign entry (`RemoteInputIntentHelper`), Favorites, and Recent Stations shortcuts.
  - **Thread View**: Auto-refreshes on inbound pushes, marks unread inbound messages read on view, supports voice/keyboard replies and one-tap preset quick replies (`73`, `Copy`, `QSL`, `On my way`, `Standing by`, `73 de mobile`).

---

## 6. Release Pipeline Integration

- **`android/wear/build.gradle.kts`**: Configured with the same `GRAYWOLF_KEYSTORE_*` release signing block as `android/app/build.gradle.kts`.
- **`.github/workflows/android.yml`**: Builds `:wear:assembleDebug` alongside `:app:assembleDebug` on pushes/PRs, and on `v*` tags builds release-signed `graywolf-wear-<version>.apk`, verifies its certificate alongside `graywolf-<version>.apk`, and uploads it to the GitHub Release (only `:app`'s `.aab` goes to Google Play Closed Testing).

---

## 7. Verification Status

- **Automated / Local Builds**:
  - Svelte production build (`npm --prefix web run build`) and `Kiss.bt.perm.test.js` pass.
  - Rust unit tests (`--features android-test-stub`), clippy, and Android NDK checks (`arm64-v8a` / `x86_64`) verified cleanly.
  - Go tests (`./pkg/txgovernor/...`) and `go vet ./...` pass.
  - Android `:app` new unit tests (`StationHistoryFilterTest`, `MessageNotificationTest`, `AprsMapProjectionTest`), `:wear` unit tests (`WearModelsTest`), and `:wear:assembleDebug` / `:wear:assembleRelease` pass.
- **Pending Live Hardware Validation**:
  - Interactive Android Auto head unit / Desktop Head Unit projection test (map split/fullscreen toggle, touch pan/zoom, Google Maps handoff, voice reply).
  - Paired physical Wear OS watch Data Layer RPC & inbound notification push test over RF.
