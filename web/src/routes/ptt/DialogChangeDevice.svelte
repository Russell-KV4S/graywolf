<!-- web/src/routes/ptt/DialogChangeDevice.svelte -->
<script>
  import { Button } from '@chrissnell/chonky-ui';
  import Modal from '../../components/Modal.svelte';
  import DevicePicker from './DevicePicker.svelte';

  let {
    open = $bindable(),
    method,              // the currently-chosen method-option
    deviceSource,        // adapter from ptt/devices/{android,desktop}DeviceSource.js
    initialDevicePath,   // string | null
    onSave,              // (device) => void
    onBack,              // () => void; opens Dialog A again
    onCancel,
  } = $props();

  let devices = $state([]);
  let loading = $state(false);
  let error = $state(null);
  let selectedPath = $state(null);

  let wasOpen = false;
  $effect(() => {
    if (open && !wasOpen) {
      selectedPath = initialDevicePath || null;
      wasOpen = true;
      void refresh();
    } else if (!open) {
      wasOpen = false;
    }
  });

  async function refresh() {
    if (!method) return;
    loading = true;
    error = null;
    try {
      devices = await deviceSource.list(method);
    } catch (e) {
      devices = [];
      error = e?.message || 'Failed to list devices';
    } finally {
      loading = false;
    }
  }

  function handleRequestPermission(d) {
    deviceSource.requestPermission?.(d)?.then(refresh);
  }
</script>

<Modal bind:open title="Change PTT Device" onClose={onCancel}>
  {#if loading}
    <div class="state">Loading devices…</div>
  {:else if error}
    <div class="state error">{error}</div>
  {:else}
    <DevicePicker
      {devices}
      {selectedPath}
      onSelect={(d) => { selectedPath = d.path || null; }}
      onRequestPermission={deviceSource.requestPermission ? handleRequestPermission : undefined}
    />
  {/if}
  <div class="modal-actions">
    <Button onclick={onBack}>‹ Back</Button>
    <Button onclick={refresh}>Refresh</Button>
    <Button variant="primary" disabled={!selectedPath} onclick={() => onSave(devices.find(d => d.path === selectedPath))}>
      Save
    </Button>
  </div>
</Modal>

<style>
  .state { padding: 16px; text-align: center; color: var(--text-secondary, #555); }
  .state.error { color: #b91c1c; }
  .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
