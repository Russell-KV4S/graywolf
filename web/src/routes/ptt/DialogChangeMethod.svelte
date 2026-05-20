<!-- web/src/routes/ptt/DialogChangeMethod.svelte -->
<script>
  import { Button, Input } from '@chrissnell/chonky-ui';
  import Modal from '../../components/Modal.svelte';
  import FormField from '../../components/FormField.svelte';
  import MethodPicker, { key as methodKey } from './MethodPicker.svelte';

  let {
    open = $bindable(),
    methods,
    initialWireKey,
    initialDevicePath,      // for rigctld: parse host:port; otherwise unused
    onSaveAndNext,
    onCancel,
  } = $props();

  let selected = $state(null);
  let rigctldHost = $state('localhost');
  let rigctldPort = $state(4532);

  let wasOpen = false;
  $effect(() => {
    if (open && !wasOpen) {
      selected = methods.find(m => methodKey(m) === initialWireKey) || null;
      // Seed rigctld host/port if the existing config was rigctld.
      if (selected?.wire?.method === 'rigctld' && initialDevicePath && !initialDevicePath.includes('[')) {
        const idx = initialDevicePath.lastIndexOf(':');
        if (idx > 0) {
          rigctldHost = initialDevicePath.slice(0, idx) || 'localhost';
          const p = parseInt(initialDevicePath.slice(idx + 1), 10);
          if (Number.isInteger(p) && p >= 1 && p <= 65535) rigctldPort = p;
        }
      } else {
        rigctldHost = 'localhost';
        rigctldPort = 4532;
      }
      wasOpen = true;
    } else if (!open) {
      wasOpen = false;
    }
  });

  let isRigctld = $derived(selected?.wire?.method === 'rigctld');
  let hostValid = $derived(rigctldHost.trim().length > 0 && !rigctldHost.includes(':'));
  let portValid = $derived(Number.isInteger(Number(rigctldPort)) && Number(rigctldPort) >= 1 && Number(rigctldPort) <= 65535);
  let canSave = $derived(!!selected && (!isRigctld || (hostValid && portValid)));

  function handleSaveAndNext() {
    if (!canSave) return;
    if (isRigctld) {
      onSaveAndNext(selected, { rigctld: { host: rigctldHost.trim(), port: Number(rigctldPort) } });
    } else {
      onSaveAndNext(selected, null);
    }
  }
</script>

<Modal bind:open title="Change PTT Method" onClose={onCancel}>
  <MethodPicker
    {methods}
    selectedWireKey={selected ? methodKey(selected) : null}
    onSelect={(m) => { selected = m; }}
  />
  {#if isRigctld}
    <div class="rigctld-extras">
      <FormField label="rigctld Hostname" id="dlg-rigctld-host">
        <Input id="dlg-rigctld-host" bind:value={rigctldHost} placeholder="localhost" />
      </FormField>
      <FormField label="rigctld Port" id="dlg-rigctld-port">
        <Input id="dlg-rigctld-port" type="number" min={1} max={65535} bind:value={rigctldPort} />
      </FormField>
    </div>
  {/if}
  <div class="modal-actions">
    <Button onclick={onCancel}>Cancel</Button>
    <Button variant="primary" disabled={!canSave} onclick={handleSaveAndNext}>
      {#if isRigctld}Save{:else}Save & next ›{/if}
    </Button>
  </div>
</Modal>

<style>
  .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  .rigctld-extras { display: flex; gap: 8px; margin-top: 12px; }
  .rigctld-extras :global(.form-field) { flex: 1; }
</style>
