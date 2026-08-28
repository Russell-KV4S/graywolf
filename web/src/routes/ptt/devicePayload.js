// web/src/routes/ptt/devicePayload.js
//
// Resolves the device object DialogChangeDevice hands to its onSave from
// a chosen path. Two cases feed in:
//   1. A path picked from the enumerated `devices` list — return that row
//      verbatim so its type/description/USB ids ride along.
//   2. A free-form path the operator typed (GH #511 — udev-renamed devices
//      like /dev/aioc-aprs-ptt that never appear in enumeration) — the
//      path won't match any enumerated row, so synthesise a minimal device
//      object carrying the path and the method's expected type.
//
// Kept as a pure function (no Svelte, no DOM) so it is unit-testable and
// the dialog's buildPayload stays a one-liner.

export function resolveDevice(devices, path, fallbackType) {
  const p = (path || '').trim();
  if (!p) return null;
  const list = Array.isArray(devices) ? devices : [];
  const matched = list.find((d) => d && d.path === p);
  if (matched) return matched;
  const dev = { path: p, manual: true };
  if (fallbackType) dev.type = fallbackType;
  return dev;
}
