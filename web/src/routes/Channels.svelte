<script>
  import { onMount } from 'svelte';
  import { Button, AlertDialog } from '@chrissnell/chonky-ui';
  import { api, ApiError } from '../lib/api.js';
  import { toasts } from '../lib/stores.js';
  import { Platform } from '../lib/platform.js';
  import PageHeader from '../components/PageHeader.svelte';
  import { channelsStore, start as startChannelsStore, invalidate as refreshChannels } from '../lib/stores/channels.svelte.js';
  import { groupReferrers, totalReferrers } from '../lib/channelReferrers.js';
  import ChannelRow from './channels/ChannelRow.svelte';
  import ChannelEditModal from './channels/ChannelEditModal.svelte';

  // The Channels page itself hydrates the shared store: this page is
  // the cheapest place for a first-visit operator to land, so it
  // starts the poller idempotently. Other picker pages do the same;
  // whoever mounts first wins.
  let channels = $derived(channelsStore.list);
  let audioDevices = $state([]);
  let txTimings = $state({});
  let modalOpen = $state(false);
  let editing = $state(null);

  // Phase 5 two-step delete flow (D12).
  // Stage 1 ("impact") lists referrers grouped by type; the operator
  // chooses to cancel or proceed to stage 2. Stage 2 ("confirm")
  // requires typing the channel's exact name before the red button
  // enables. An unreferenced channel skips stage 1 and goes straight
  // to stage 2 with the same typed-name gate for consistency.
  let deleteTarget = $state(null);
  let deleteImpactOpen = $state(false);
  let deleteConfirmOpen = $state(false);
  let deleteReferrers = $state([]);
  let deleteNameInput = $state('');
  let deleteInFlight = $state(false);
  let deleteGroups = $derived(groupReferrers(deleteReferrers));
  let deleteTotal = $derived(totalReferrers(deleteReferrers));
  let deleteNameMatches = $derived(
    deleteTarget != null && deleteNameInput.trim() === deleteTarget.name
  );

  // Phase 3 -- channel PUT 409 confirm-and-force flow. Mirrors the
  // stage-1 impact dialog above: show the list of referrers that
  // would break if the mutation proceeded, let the operator cancel
  // or confirm, and on confirm retry the PUT with ?force=true
  // (same wire convention as ?cascade=true on DELETE).
  //
  // No typed-name gate here. A PUT that breaks referrers is
  // recoverable (the operator can edit again). A DELETE is not --
  // that's why the delete flow carries the stronger gate. The
  // referrer list itself is the confirmation surface.
  let putConfirmOpen = $state(false);
  let putReferrers = $state([]);
  let putPendingPayload = $state(null);
  let putPendingId = $state(null);
  let putServerError = $state('');
  let putInFlight = $state(false);
  // Captured tx/ptt context from the 409 save attempt; re-used on force retry.
  let putPendingContext = $state(null);
  let putGroups = $derived(groupReferrers(putReferrers));
  let putTotal = $derived(totalReferrers(putReferrers));

  onMount(async () => {
    startChannelsStore();
    await Promise.all([loadChannels(), loadDevices(), loadTxTimings()]);
  });

  // Legacy name; delegates to the shared store so every caller gets
  // the same refresh semantics (including pickers on other tabs).
  async function loadChannels() {
    await refreshChannels();
  }

  async function loadDevices() {
    audioDevices = await api.get('/audio-devices') || [];
  }

  async function loadTxTimings() {
    const list = await api.get('/tx-timing') || [];
    const map = {};
    for (const t of list) map[t.channel] = t;
    txTimings = map;
  }

  function openCreate() {
    editing = null;
    modalOpen = true;
  }

  function openEdit(row) {
    editing = row;
    modalOpen = true;
  }

  function closeModal() {
    modalOpen = false;
  }

  // handleSave receives the payload + context built by ChannelEditModal
  // and delegates to persistSave (PUT/POST + referrer-confirm path).
  async function handleSave({ payload, isTxEnabled, txTiming, androidPttMethod }) {
    await persistSave(payload, { force: false, isTxEnabled, txTiming, androidPttMethod });
  }

  // persistSave runs the actual PUT/POST + follow-up tx-timing save.
  // Factored out of handleSave so the Phase 3 409-force retry path
  // can reuse it without duplicating the tx-timing / modal-close /
  // reload dance. `force` adds ?force=true to the PUT query when
  // true; backend treats that as "I know this breaks referrers,
  // proceed anyway" (Phase 1 handoff).
  // isTxEnabled, txTiming, androidPttMethod are passed from ChannelEditModal
  // via handleSave; the force-retry path (confirmForcePut) re-passes the
  // context it captured when the 409 landed.
  async function persistSave(data, { force, isTxEnabled = false, txTiming = null, androidPttMethod = null }) {
    try {
      let channelId;
      if (editing) {
        const path = force
          ? `/channels/${editing.id}?force=true`
          : `/channels/${editing.id}`;
        await api.put(path, data);
        channelId = editing.id;
        toasts.success('Channel updated');
      } else {
        const created = await api.post('/channels', data);
        channelId = created.id;
        toasts.success('Channel created');
      }

      // Save TX timing if this is a TX-capable channel
      if (isTxEnabled && channelId && txTiming) {
        const timingData = {
          channel: channelId,
          ...txTiming,
        };
        await api.put(`/tx-timing/${channelId}`, timingData);
      }

      // On Android, persist the PTT method via POST /api/ptt.
      // Data shape: method='android', gpio_pin=method_int (1–4 per Appendix B).
      // The Go modembridge reads gpio_pin to determine which USB transport
      // to invoke in UsbPttAdapter (T7). This reuses the existing PttConfig
      // row's gpio_pin field as a method-int carrier rather than adding a
      // new schema column — the semantics are different from the desktop
      // gpio-line use but the field is otherwise unused on Android channels.
      if (Platform.kind === 'android' && androidPttMethod != null && channelId) {
        await api.post('/ptt', {
          channel_id: channelId,
          method: 'android',
          gpio_pin: androidPttMethod,
        });
      }

      modalOpen = false;
      await Promise.all([loadChannels(), loadTxTimings()]);
    } catch (err) {
      // Phase 3 -- PUT 409 with referrers means the mutation would
      // break active config. Reuse the DELETE-cascade referrer-
      // grouping UI (channelReferrers.js) for consistency; the only
      // difference is the copy and the action (force vs cascade).
      // POST / non-409 paths fall through to the toast.
      if (
        editing &&
        !force &&
        err instanceof ApiError &&
        err.status === 409 &&
        Array.isArray(err.body?.referrers)
      ) {
        putReferrers = err.body.referrers;
        putPendingPayload = data;
        putPendingId = editing.id;
        putPendingContext = { isTxEnabled, txTiming, androidPttMethod };
        putServerError = err.body?.error || err.message || '';
        putConfirmOpen = true;
        return;
      }
      toasts.error(err.message);
    }
  }

  // Called from the confirm dialog's Action button when the
  // operator acknowledges the referrer list and chooses to proceed.
  async function confirmForcePut() {
    if (!putPendingPayload || !putPendingId) return;
    const data = putPendingPayload;
    putInFlight = true;
    try {
      // editing can get cleared by other code paths; re-affirm it
      // from the id we captured when the 409 landed so the retry
      // routes to the correct row.
      const targetId = putPendingId;
      if (editing?.id !== targetId) {
        editing = channels.find((c) => c.id === targetId) || editing;
      }
      const ctx = putPendingContext || {};
      await persistSave(data, { force: true, ...ctx });
    } finally {
      putInFlight = false;
      putConfirmOpen = false;
      putReferrers = [];
      putPendingPayload = null;
      putPendingId = null;
      putPendingContext = null;
      putServerError = '';
    }
  }

  // Cancel path: drop the pending payload and leave the edit modal
  // as-is. The operator's form state is preserved so they can
  // adjust the channel config and try again.
  function cancelForcePut() {
    putConfirmOpen = false;
    putReferrers = [];
    putPendingPayload = null;
    putPendingId = null;
    putPendingContext = null;
    putServerError = '';
  }

  // Phase 5 two-step delete flow (D12).
  //
  // Click "Delete" → requestDelete(row):
  //   1. Fetch /api/channels/{id}/referrers.
  //   2. Empty list: skip the impact dialog; open the typed-name
  //      confirm dialog with cascade=false path.
  //   3. Non-empty list: open the impact dialog first. From there the
  //      operator clicks "Remove references…" to advance to the
  //      typed-name confirm dialog with cascade=true.
  //
  // Either way, the final Delete button is enabled only when the
  // operator types the channel's exact name. On confirm we call
  // DELETE with or without ?cascade=true depending on the path.
  async function requestDelete(row) {
    deleteTarget = row;
    deleteNameInput = '';
    deleteReferrers = [];
    try {
      const resp = await api.get(`/channels/${row.id}/referrers`);
      const refs = Array.isArray(resp?.referrers) ? resp.referrers : [];
      deleteReferrers = refs;
      if (refs.length === 0) {
        // Unreferenced — go straight to the typed-name confirm.
        deleteImpactOpen = false;
        deleteConfirmOpen = true;
      } else {
        deleteImpactOpen = true;
        deleteConfirmOpen = false;
      }
    } catch (err) {
      toasts.error(err.message);
      deleteTarget = null;
    }
  }

  function proceedToConfirm() {
    deleteImpactOpen = false;
    deleteConfirmOpen = true;
    deleteNameInput = '';
  }

  function cancelDelete() {
    deleteImpactOpen = false;
    deleteConfirmOpen = false;
    deleteTarget = null;
    deleteReferrers = [];
    deleteNameInput = '';
  }

  async function executeDelete() {
    if (!deleteTarget || !deleteNameMatches) return;
    const cascade = deleteReferrers.length > 0;
    const id = deleteTarget.id;
    deleteInFlight = true;
    try {
      const path = cascade ? `/channels/${id}?cascade=true` : `/channels/${id}`;
      await api.delete(path);
      toasts.success(cascade
        ? `Channel deleted along with ${deleteTotal} reference${deleteTotal === 1 ? '' : 's'}`
        : 'Channel deleted');
      await Promise.all([loadChannels(), loadTxTimings()]);
      deleteImpactOpen = false;
      deleteConfirmOpen = false;
      deleteTarget = null;
      deleteReferrers = [];
      deleteNameInput = '';
    } catch (err) {
      // A 409 here would mean a race (referrers appeared between our
      // GET and DELETE). Surface the same error channel; the impact
      // dialog route will naturally pick them up on the next click.
      if (err instanceof ApiError && err.status === 409 && Array.isArray(err.body?.referrers)) {
        deleteReferrers = err.body.referrers;
        deleteConfirmOpen = false;
        deleteImpactOpen = true;
        toasts.error('New references appeared — review and try again');
      } else {
        toasts.error(err.message);
      }
    } finally {
      deleteInFlight = false;
    }
  }
</script>

<PageHeader title="Channels" subtitle="Radio channel configuration">
  <Button variant="primary" onclick={openCreate}>+ Add Channel</Button>
</PageHeader>

{#if channels.length === 0}
  <div class="empty-state">
    No channels configured. Add a channel to start decoding RF packets.
    <br />
    <span class="empty-state-hint">
      Running an APRS-IS-only station? You don't need a channel — set your
      <a href="#/callsign">station callsign</a>, then enable the
      <a href="#/igate">iGate</a>. Messages will route over APRS-IS automatically.
    </span>
  </div>
{:else}
  <div class="channel-grid">
    {#each channels as ch}
      <ChannelRow
        channel={ch}
        txTiming={txTimings[ch.id]}
        {audioDevices}
        onEdit={openEdit}
        onDelete={requestDelete}
      />
    {/each}
  </div>
{/if}

<!-- Add/Edit modal (extracted to ChannelEditModal) -->
<ChannelEditModal
  bind:open={modalOpen}
  {editing}
  {audioDevices}
  {txTimings}
  onSave={handleSave}
  onCancel={closeModal}
/>

<!-- Phase 5 two-step delete: stage 1 = impact dialog (only when the
     channel has referrers). Lists what the cascade will do to each
     dependent row, grouped by type, so the operator has an informed
     sense of scope before hitting the typed-name gate. -->
<AlertDialog bind:open={deleteImpactOpen}>
  <AlertDialog.Content>
    <AlertDialog.Title>Delete channel {deleteTarget?.name ?? ''}?</AlertDialog.Title>
    <AlertDialog.Description>
      This channel has {deleteTotal} reference{deleteTotal === 1 ? '' : 's'}. Deleting it will affect:
    </AlertDialog.Description>
    <ul class="referrer-groups">
      {#each deleteGroups as g (g.type)}
        <li>
          <strong>{g.items.length} {g.label}</strong>{#if g.action}<span class="referrer-action"> — {g.action}</span>{/if}{#if g.items.some((i) => i.name)}:
            <span class="referrer-items">
              {#each g.items as item, idx (item.id)}{idx > 0 ? ', ' : ''}{item.name || `#${item.id}`}{/each}
            </span>
          {/if}
        </li>
      {/each}
    </ul>
    <div class="modal-footer">
      <AlertDialog.Cancel onclick={cancelDelete}>Cancel</AlertDialog.Cancel>
      <AlertDialog.Action class="secondary-action" onclick={proceedToConfirm}>
        Remove references…
      </AlertDialog.Action>
    </div>
  </AlertDialog.Content>
</AlertDialog>

<!-- Phase 5 two-step delete: stage 2 = typed-name confirm. Fires for
     unreferenced channels directly (no stage 1) and for referenced
     channels after the operator clicks through the impact dialog.
     The delete button only enables when the typed name matches exactly. -->
<AlertDialog bind:open={deleteConfirmOpen}>
  <AlertDialog.Content>
    <AlertDialog.Title>
      {#if deleteReferrers.length > 0}
        Delete channel and {deleteTotal} reference{deleteTotal === 1 ? '' : 's'}
      {:else}
        Delete channel {deleteTarget?.name ?? ''}?
      {/if}
    </AlertDialog.Title>
    <AlertDialog.Description>
      This cannot be undone. To confirm, type the channel name exactly:
      <strong>{deleteTarget?.name ?? ''}</strong>
    </AlertDialog.Description>
    <label class="confirm-label">
      Channel name
      <input
        type="text"
        class="confirm-input"
        bind:value={deleteNameInput}
        autocomplete="off"
        aria-label={`Type ${deleteTarget?.name ?? ''} to confirm delete`}
      />
    </label>
    <div class="modal-footer">
      <AlertDialog.Cancel onclick={cancelDelete}>Cancel</AlertDialog.Cancel>
      <AlertDialog.Action
        class="danger-action"
        onclick={executeDelete}
        disabled={!deleteNameMatches || deleteInFlight}
      >
        {#if deleteReferrers.length > 0}
          Delete channel and {deleteTotal} reference{deleteTotal === 1 ? '' : 's'}
        {:else}
          Delete channel
        {/if}
      </AlertDialog.Action>
    </div>
  </AlertDialog.Content>
</AlertDialog>

<!-- Phase 3 -- channel PUT 409 "force" confirmation. Mirrors the
     stage-1 delete impact dialog above (same AlertDialog shape, same
     groupReferrers() rendering) but the Action retries the PUT with
     ?force=true instead of cascading a delete. No typed-name gate:
     a broken-referrer PUT is recoverable by editing again. -->
<AlertDialog bind:open={putConfirmOpen}>
  <AlertDialog.Content>
    <AlertDialog.Title>Update channel and break references?</AlertDialog.Title>
    <AlertDialog.Description>
      This channel update would break the following active config.
      {#if putServerError}
        <span class="put-error-reason">Reason: {putServerError}</span>
      {/if}
    </AlertDialog.Description>
    <ul class="referrer-groups">
      {#each putGroups as g (g.type)}
        <li>
          <strong>{g.items.length} {g.label}</strong>{#if g.items.some((i) => i.name)}:
            <span class="referrer-items">
              {#each g.items as item, idx (item.id)}{idx > 0 ? ', ' : ''}{item.name || `#${item.id}`}{/each}
            </span>
          {/if}
        </li>
      {/each}
    </ul>
    <p class="put-force-note">
      Saving will apply the change anyway. The referrers listed above
      will remain in the database but may fail to transmit until you
      fix them on their respective pages.
    </p>
    <div class="modal-footer">
      <AlertDialog.Cancel onclick={cancelForcePut}>Cancel</AlertDialog.Cancel>
      <AlertDialog.Action
        class="danger-action"
        onclick={confirmForcePut}
        disabled={putInFlight}
      >
        Save channel and break {putTotal} reference{putTotal === 1 ? '' : 's'}
      </AlertDialog.Action>
    </div>
  </AlertDialog.Content>
</AlertDialog>

<style>
  .empty-state {
    text-align: center;
    color: var(--text-muted);
    padding: 32px;
    border: 1px dashed var(--border-color);
    border-radius: var(--radius);
  }
  .empty-state-hint {
    display: inline-block;
    margin-top: 8px;
    font-size: 13px;
    color: var(--text-muted);
  }
  .empty-state-hint a {
    color: var(--color-primary);
    text-decoration: underline;
  }

  .channel-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
    gap: 12px;
  }

  .modal-footer {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    padding: 1.25rem 1.5rem 1.5rem;
  }
  :global(.danger-action) {
    background: var(--color-danger) !important;
    color: white !important;
  }

  /* Phase 5 two-step delete flow */
  .referrer-groups {
    margin: 12px 1.5rem 0 1.5rem;
    padding: 10px 12px;
    background: var(--bg-tertiary);
    border-radius: var(--radius);
    list-style: disc inside;
    font-size: 13px;
    color: var(--text-primary);
    line-height: 1.6;
  }
  .referrer-groups li + li {
    margin-top: 2px;
  }
  .referrer-action {
    color: var(--text-secondary);
    font-style: italic;
  }
  .referrer-items {
    color: var(--text-secondary);
  }
  .confirm-label {
    display: block;
    margin: 12px 1.5rem 0 1.5rem;
    font-size: 13px;
    color: var(--text-secondary);
  }
  .confirm-input {
    display: block;
    width: 100%;
    margin-top: 4px;
    padding: 8px 10px;
    min-height: 40px;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    border-radius: var(--radius);
    color: var(--text-primary);
    font: inherit;
  }
  .confirm-input:focus-visible {
    outline: 2px solid var(--color-info, #388bfd);
    outline-offset: -2px;
  }
  :global(.secondary-action) {
    background: var(--bg-tertiary) !important;
    color: var(--text-primary) !important;
  }

  /* Phase 3 -- channel PUT 409 confirm dialog copy. Inline "Reason:"
     clause reflects the server's concrete explanation (e.g. "no
     output device configured") so the operator sees why the
     mutation breaks referrers without guessing. */
  .put-error-reason {
    display: block;
    margin-top: 6px;
    font-size: 13px;
    color: var(--color-danger, #f85149);
  }
  .put-force-note {
    margin: 12px 1.5rem 0 1.5rem;
    font-size: 13px;
    color: var(--text-secondary);
    line-height: 1.5;
  }
</style>
