import { describe, it, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  defaultBackupFilename,
  exportBackup,
  isCancelledError,
  restoreBackup,
} from './storageBackup.js';

const origFetch = globalThis.fetch;
const origBridge = globalThis.GraywolfWebInterface;
const origCb = globalThis.__backupSaveResult;

afterEach(() => {
  globalThis.fetch = origFetch;
  if (origBridge === undefined) delete globalThis.GraywolfWebInterface;
  else globalThis.GraywolfWebInterface = origBridge;
  if (origCb === undefined) delete globalThis.__backupSaveResult;
  else globalThis.__backupSaveResult = origCb;
});

describe('storageBackup', () => {
  it('formats deterministic UTC backup filename', () => {
    const d = new Date(Date.UTC(2026, 10, 26, 4, 5, 6));
    assert.equal(defaultBackupFilename(d), 'graywolf-backup-20261126-040506.zip');
  });

  it('uses Android WebView bridge when saveBackup is present', async () => {
    let called = null;
    globalThis.GraywolfWebInterface = {
      saveBackup(includeHistory, filename, cbId) {
        called = { includeHistory, filename, cbId };
        queueMicrotask(() => globalThis.__backupSaveResult(cbId, true, ''));
      },
    };
    const res = await exportBackup({ includeHistory: true });
    assert.equal(res.via, 'android');
    assert.equal(called.includeHistory, true);
    assert.match(called.filename, /^graywolf-backup-\d{8}-\d{6}\.zip$/);
  });

  it('propagates cancellation cleanly from Android bridge', async () => {
    globalThis.GraywolfWebInterface = {
      saveBackup(_includeHistory, _filename, cbId) {
        queueMicrotask(() => globalThis.__backupSaveResult(cbId, false, 'cancelled'));
      },
    };
    await assert.rejects(() => exportBackup(), (err) => isCancelledError(err));
  });

  it('rejects restore when no file is provided', async () => {
    await assert.rejects(() => restoreBackup(null), /Choose a backup/);
  });

  it('surfaces backend validation error on restore', async () => {
    globalThis.fetch = async () => ({
      ok: false,
      status: 400,
      json: async () => ({ error: 'invalid config database: bad header' }),
    });
    await assert.rejects(
      () => restoreBackup(new Blob(['bad'], { type: 'application/octet-stream' })),
      /bad header/,
    );
  });
});
