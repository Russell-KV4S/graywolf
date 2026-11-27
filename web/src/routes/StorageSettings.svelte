<script>
  import { onMount } from 'svelte';
  import { Box, Button, Checkbox } from '@chrissnell/chonky-ui';
  import { storageUsageState, colorForKey } from '../lib/settings/storage-usage-store.svelte.js';
  import { formatBytes } from '../lib/maps/format-bytes.js';
  import {
    exportBackup,
    isCancelledError,
    restoreBackup,
    waitForServerBack,
  } from '../lib/storageBackup.js';
  import { toasts } from '../lib/stores.js';
  import PageHeader from '../components/PageHeader.svelte';
  import Modal from '../components/Modal.svelte';

  let includeHistory = $state(false);
  let exporting = $state(false);
  let selectedFile = $state(null);
  let fileInputEl = $state(null);
  let confirmOpen = $state(false);
  let restoring = $state(false);
  let restoreStatus = $state('');

  onMount(() => {
    storageUsageState.refresh();
  });

  // Bar segments as percentages of the total. When nothing is stored
  // yet the bar renders empty rather than dividing by zero.
  let segments = $derived.by(() => {
    const total = storageUsageState.totalBytes;
    return storageUsageState.locations.map((l) => ({
      ...l,
      color: colorForKey(l.key),
      pct: total > 0 ? (l.bytes / total) * 100 : 0,
    }));
  });

  async function handleExport() {
    if (exporting) return;
    exporting = true;
    try {
      const res = await exportBackup({ includeHistory });
      toasts.success(res.via === 'android' ? 'Backup saved' : `Downloaded ${res.filename}`);
    } catch (e) {
      if (!isCancelledError(e)) {
        toasts.error(e?.message || 'Backup export failed');
      }
    } finally {
      exporting = false;
    }
  }

  function handleFileChange(e) {
    const f = e.currentTarget?.files?.[0] ?? null;
    selectedFile = f;
  }

  function openRestoreConfirm() {
    if (!selectedFile || restoring) return;
    confirmOpen = true;
  }

  async function handleRestoreConfirm() {
    if (!selectedFile || restoring) return;
    restoring = true;
    restoreStatus = 'Validating and uploading backup...';
    try {
      const res = await restoreBackup(selectedFile);
      if (res?.restarting) {
        restoreStatus = 'Restoring database and restarting Graywolf...';
        const up = await waitForServerBack();
        if (up) {
          window.location.reload();
          return;
        }
        toasts.success('Backup staged. Reload this page once Graywolf finishes restarting.');
      } else {
        toasts.success(res?.message || 'Backup staged. Restart Graywolf to apply.');
      }
      confirmOpen = false;
      selectedFile = null;
      if (fileInputEl) fileInputEl.value = '';
    } catch (e) {
      toasts.error(e?.message || 'Restore failed');
    } finally {
      restoring = false;
      restoreStatus = '';
    }
  }
</script>

<PageHeader title="Storage" subtitle="Where Graywolf keeps offline maps and history" />

<Box title="Storage usage">
  {#if storageUsageState.error && !storageUsageState.loaded}
    <p class="usage-error">
      Couldn't read storage usage. <button class="linklike" onclick={() => storageUsageState.refresh()}>Retry</button>
    </p>
  {:else}
    <div class="usage-bar" aria-hidden="true">
      {#each segments as seg (seg.key)}
        {#if seg.pct > 0}
          <div class="usage-seg" style="width:{seg.pct}%; background:{seg.color};"></div>
        {/if}
      {/each}
    </div>

    <ul class="usage-list">
      {#each segments as seg (seg.key)}
        <li class="usage-row">
          <span class="usage-swatch" style="background:{seg.color};"></span>
          <span class="usage-name">{seg.label}</span>
          <span class="usage-bytes">{formatBytes(seg.bytes)}</span>
        </li>
      {/each}
    </ul>

    <div class="usage-total">
      <span>Total</span>
      <span class="usage-bytes">{formatBytes(storageUsageState.totalBytes)}</span>
    </div>

    {#if storageUsageState.error}
      <p class="usage-stale">
        Showing last known figures — couldn't refresh.
        <button class="linklike" onclick={() => storageUsageState.refresh()}>Retry</button>
      </p>
    {/if}
  {/if}
</Box>

<Box title="Backup &amp; Restore">
  <div class="backup-section">
    <h3 class="backup-heading">Export backup</h3>
    <p class="backup-hint">
      Save a consistent snapshot of your settings, channels, beacons, messages,
      and alerts as a portable <code>.zip</code> file.
    </p>
    <label class="backup-checkbox">
      <Checkbox id="storage-include-history" bind:checked={includeHistory} />
      <span>Include position history (<code>graywolf-history.db</code>)</span>
    </label>
    <div class="backup-actions">
      <Button variant="primary" onclick={handleExport} disabled={exporting}>
        {exporting ? 'Exporting...' : 'Export backup'}
      </Button>
    </div>
  </div>

  <hr class="backup-divider" />

  <div class="backup-section">
    <h3 class="backup-heading">Restore backup</h3>
    <p class="backup-hint">
      Import a <code>.zip</code> backup or a raw <code>graywolf.db</code> file from
      another Graywolf install. When moving between desktop and Android, re-select
      device-specific audio, serial, or PTT settings afterward.
    </p>
    <div class="backup-restore-row">
      <input
        bind:this={fileInputEl}
        type="file"
        class="backup-file-input"
        accept=".zip,.db,application/zip,application/octet-stream"
        onchange={handleFileChange}
        disabled={restoring}
      />
      <Button
        variant="danger"
        onclick={openRestoreConfirm}
        disabled={!selectedFile || restoring}
      >
        Restore backup
      </Button>
    </div>
  </div>
</Box>

<Box title="Data location">
  <p class="loc-hint">
    These are the paths Graywolf reads and writes on this device — useful
    if you want to find or back up your data.
  </p>
  <ul class="loc-list">
    {#each storageUsageState.locations as loc (loc.key)}
      <li class="loc-row">
        <span class="loc-name">{loc.label}</span>
        <code class="loc-path">{loc.path}</code>
      </li>
    {/each}
  </ul>
</Box>

<style>
  .usage-bar {
    display: flex;
    width: 100%;
    height: 16px;
    border-radius: 5px;
    overflow: hidden;
    background: var(--color-surface-raised);
    margin-bottom: 16px;
  }
  .usage-seg {
    height: 100%;
  }
  .usage-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .usage-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 0;
  }
  .usage-swatch {
    width: 12px;
    height: 12px;
    border-radius: 3px;
    flex: none;
  }
  .usage-name {
    flex: 1;
  }
  .usage-bytes {
    color: var(--color-text);
    font-variant-numeric: tabular-nums;
  }
  .usage-total {
    display: flex;
    justify-content: space-between;
    margin-top: 10px;
    padding-top: 10px;
    border-top: 1px solid var(--color-border);
    color: var(--color-text-muted);
  }
  .usage-total .usage-bytes {
    font-weight: bold;
  }
  .usage-error {
    color: var(--color-text-muted);
  }
  .usage-stale {
    color: var(--color-text-dim);
    font-size: 0.9em;
    margin-top: 12px;
    margin-bottom: 0;
  }
  .linklike {
    background: none;
    border: none;
    color: var(--color-primary);
    cursor: pointer;
    padding: 0;
    font: inherit;
  }
  .loc-hint {
    color: var(--color-text-muted);
    margin-top: 0;
  }
  .loc-list {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .loc-row {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 6px 0;
  }
  .loc-path {
    color: var(--color-text-dim);
    word-break: break-all;
  }
  .backup-section {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .backup-heading {
    margin: 0;
    font-size: 1em;
    color: var(--color-text);
  }
  .backup-hint {
    margin: 0;
    color: var(--color-text-muted);
    font-size: 0.92em;
    line-height: 1.45;
  }
  .backup-checkbox {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    color: var(--color-text);
  }
  .backup-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .backup-divider {
    border: none;
    border-top: 1px solid var(--color-border);
    margin: 16px 0;
  }
  .backup-restore-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .backup-file-input {
    max-width: 100%;
    color: var(--color-text);
  }
  .modal-body-text {
    margin: 0 0 12px;
    color: var(--color-text);
    line-height: 1.45;
  }
  .modal-status {
    margin: 0 0 12px;
    color: var(--color-primary);
    font-size: 0.92em;
  }
  .modal-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
</style>

<Modal
  bind:open={confirmOpen}
  title="Restore database backup?"
  onClose={() => { if (!restoring) confirmOpen = false; }}
>
  <p class="modal-body-text">
    Replace your current Graywolf configuration{#if selectedFile} using <strong>{selectedFile.name}</strong>{/if}?
    Your existing database will be kept as <code>.pre-restore</code>, and Graywolf will restart automatically.
  </p>
  {#if restoreStatus}
    <p class="modal-status">{restoreStatus}</p>
  {/if}
  <div class="modal-actions">
    <Button onclick={() => confirmOpen = false} disabled={restoring}>Cancel</Button>
    <Button variant="danger" onclick={handleRestoreConfirm} disabled={restoring}>
      {restoring ? 'Restoring...' : 'Restore & Restart'}
    </Button>
  </div>
</Modal>
