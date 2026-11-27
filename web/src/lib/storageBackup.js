// Cross-platform helpers for exporting and restoring Graywolf's SQLite
// backup archives from Settings -> Storage.
//
// Desktop browsers stream GET /api/storage/backup via fetch() and trigger
// a Blob download. Android's WebView delegates to GraywolfWebInterface
// .saveBackup(...) so Android's Storage Access Framework (SAF) picker
// writes the zip directly to user-chosen storage (Downloads, Drive, SD).

function pad2(n) {
  return String(n).padStart(2, '0');
}

export function defaultBackupFilename(now = new Date()) {
  return (
    `graywolf-backup-` +
    `${now.getUTCFullYear()}${pad2(now.getUTCMonth() + 1)}${pad2(now.getUTCDate())}-` +
    `${pad2(now.getUTCHours())}${pad2(now.getUTCMinutes())}${pad2(now.getUTCSeconds())}.zip`
  );
}

function makeCallbackId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `bk-${crypto.randomUUID().replace(/[^A-Za-z0-9_-]/g, '')}`;
  }
  return `bk-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

export function isCancelledError(err) {
  return err?.message === 'cancelled';
}

export async function exportBackup({ includeHistory = false } = {}) {
  const filename = defaultBackupFilename();
  const bridge = globalThis.GraywolfWebInterface;
  if (bridge && typeof bridge.saveBackup === 'function') {
    return new Promise((resolve, reject) => {
      const cbId = makeCallbackId();
      const prevHandler = globalThis.__backupSaveResult;
      const pending = prevHandler?._pending ?? new Map();

      if (!prevHandler || !prevHandler._pending) {
        const dispatch = (id, ok, errMsg) => {
          const entry = pending.get(id);
          if (!entry) return;
          pending.delete(id);
          if (ok) {
            entry.resolve({ via: 'android', filename });
          } else {
            entry.reject(new Error(errMsg || 'Backup export failed'));
          }
        };
        dispatch._pending = pending;
        globalThis.__backupSaveResult = dispatch;
      }

      pending.set(cbId, { resolve, reject });
      try {
        bridge.saveBackup(Boolean(includeHistory), filename, cbId);
      } catch (e) {
        pending.delete(cbId);
        reject(e);
      }
    });
  }

  const res = await fetch(`/api/storage/backup?include_history=${includeHistory ? 'true' : 'false'}`, {
    credentials: 'same-origin',
  });
  if (!res.ok) {
    const errBody = await res.json().catch(() => ({ error: `HTTP ${res.status}` }));
    throw new Error(errBody?.error || `Export failed (HTTP ${res.status})`);
  }

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { via: 'browser', filename };
}

export async function restoreBackup(file) {
  if (!file) throw new Error('Choose a backup .zip or graywolf.db file first');
  const form = new FormData();
  form.append('file', file);

  const res = await fetch('/api/storage/restore', {
    method: 'POST',
    credentials: 'same-origin',
    body: form,
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    throw new Error(body?.error || `Restore failed (HTTP ${res.status})`);
  }
  return body;
}

export async function waitForServerBack({
  initialDelayMs = 800,
  intervalMs = 500,
  timeoutMs = 20000,
} = {}) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  await sleep(initialDelayMs);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch('/api/version', { credentials: 'same-origin', cache: 'no-store' });
      if (res.ok) return true;
    } catch {
      // Expected briefly while Graywolf restarts in-process.
    }
    await sleep(intervalMs);
  }
  return false;
}
