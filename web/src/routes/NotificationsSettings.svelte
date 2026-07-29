<script>
  import { Toggle, Box, Select, Button } from '@chrissnell/chonky-ui';
  import PageHeader from '../components/PageHeader.svelte';
  import { notificationPrefsState } from '../lib/settings/notification-prefs-store.svelte.js';
  import { notificationSoundState } from '../lib/settings/notification-sound-store.svelte.js';
  import { notifications } from '../lib/notificationsStore.svelte.js';
  import { fireOsNotification } from '../lib/osNotify.js';
  import { SOUND_PRESETS } from '../lib/soundPresets.js';
  import { toasts } from '../lib/stores.js';

  const notificationModeOptions = $derived([
    { value: 'toast', label: 'In-app popup' },
    ...(notificationPrefsState.supported
      ? [
          { value: 'os', label: 'OS notification' },
          { value: 'both', label: 'Both' },
        ]
      : []),
  ]);

  // Plain operator-facing preview — not a dev-only debug affordance.
  // Fires through whichever mode(s) are currently selected, plus
  // whichever sound(s) are currently enabled, so the operator hears and
  // sees the exact combination they'd get for a real message or
  // bulletin before relying on it. Message and bulletin sounds are
  // staggered slightly so both are audible rather than overlapping.
  function sendTestNotification() {
    const title = 'Test notification';
    const body = 'This is what a new message or bulletin notification looks like.';
    if (notificationPrefsState.toastEnabled) {
      notifications.push({ kind: 'test', title, body, href: '' });
    }
    if (notificationPrefsState.osEnabled) {
      fireOsNotification(title, body, null, { force: true });
    }
    if (notificationPrefsState.messageEnabled) notificationSoundState.message.play();
    if (notificationPrefsState.bulletinEnabled) {
      setTimeout(() => notificationSoundState.bulletin.play(), 350);
    }
  }

  function soundOptions(state) {
    const opts = SOUND_PRESETS.map((p) => ({ value: p.id, label: p.label }));
    if (state.hasCustom) opts.push({ value: 'custom', label: `Custom: ${state.customName}` });
    return opts;
  }

  let messageFileInput = $state(null);
  let bulletinFileInput = $state(null);
  let messageUploadError = $state('');
  let bulletinUploadError = $state('');

  async function handleUpload(state, input, setError) {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    setError('');
    try {
      await state.upload(file);
      toasts.success(`Uploaded ${file.name}`);
    } catch (e) {
      setError(e?.message || 'Upload failed.');
    }
  }

  async function handleRemove(state) {
    try {
      await state.removeCustom();
    } catch {
      toasts.error('Could not remove custom sound.');
    }
  }
</script>

<PageHeader title="Notifications" subtitle="Popup and sound alerts for new messages and bulletins" />

<Box title="Popup notifications">
  <Toggle
    checked={notificationPrefsState.messageEnabled}
    onCheckedChange={(v) => notificationPrefsState.setMessageEnabled(v)}
    label="Notify me about new messages"
  />
  <Toggle
    checked={notificationPrefsState.bulletinEnabled}
    onCheckedChange={(v) => notificationPrefsState.setBulletinEnabled(v)}
    label="Notify me about new bulletins"
  />
  <p class="notif-hint">
    Turn off both to disable notifications entirely, or just one to
    mute that type while keeping the other. Applies to popups, OS
    notifications, and sounds together.
  </p>
  <p class="sound-label">How to notify</p>
  <Select
    value={notificationPrefsState.mode}
    onValueChange={(v) => notificationPrefsState.setMode(v)}
    options={notificationModeOptions}
  />
  <p class="notif-hint">
    {#if !notificationPrefsState.supported}
      OS notifications aren't available in this environment (e.g. the
      Android app) — in-app popups always work.
    {:else if notificationPrefsState.permission === 'denied'}
      OS notifications are blocked in your browser's site settings —
      re-enable there, or stick with in-app popups.
    {:else}
      In-app popups appear inside graywolf; OS notifications also show
      up outside the browser tab, once you grant permission.
    {/if}
  </p>
  <Button variant="secondary" onclick={sendTestNotification}>
    Send test notification
  </Button>
</Box>

<Box title="Message sounds">
  <Toggle
    checked={notificationSoundState.message.enabled}
    onCheckedChange={(v) => notificationSoundState.message.setEnabled(v)}
    label="Play a sound for new messages"
  />
  <p class="sound-label">Sound</p>
  <Select
    value={notificationSoundState.message.presetId}
    onValueChange={(v) => notificationSoundState.message.setPreset(v)}
    options={soundOptions(notificationSoundState.message)}
    aria-label="Message notification sound"
  />
  <div class="sound-actions">
    <Button variant="secondary" onclick={() => messageFileInput.click()}>
      Upload custom sound
    </Button>
    {#if notificationSoundState.message.hasCustom}
      <Button variant="ghost" onclick={() => handleRemove(notificationSoundState.message)}>
        Remove custom sound
      </Button>
    {/if}
    <Button variant="ghost" onclick={() => notificationSoundState.message.preview()}>
      Test sound
    </Button>
  </div>
  <input
    class="file-input"
    type="file"
    accept="audio/*"
    bind:this={messageFileInput}
    onchange={() => handleUpload(notificationSoundState.message, messageFileInput, (e) => (messageUploadError = e))}
  />
  {#if messageUploadError}
    <p class="err" role="alert">{messageUploadError}</p>
  {/if}
  <p class="sound-hint">
    Plays when a new directed message or tactical message arrives while
    it wouldn't otherwise raise a popup (muted threads never play a
    sound). Custom uploads are stored in this browser only — up to 2 MB.
  </p>
</Box>

<Box title="Bulletin sounds">
  <Toggle
    checked={notificationSoundState.bulletin.enabled}
    onCheckedChange={(v) => notificationSoundState.bulletin.setEnabled(v)}
    label="Play a sound for new bulletins"
  />
  <p class="sound-label">Sound</p>
  <Select
    value={notificationSoundState.bulletin.presetId}
    onValueChange={(v) => notificationSoundState.bulletin.setPreset(v)}
    options={soundOptions(notificationSoundState.bulletin)}
    aria-label="Bulletin notification sound"
  />
  <div class="sound-actions">
    <Button variant="secondary" onclick={() => bulletinFileInput.click()}>
      Upload custom sound
    </Button>
    {#if notificationSoundState.bulletin.hasCustom}
      <Button variant="ghost" onclick={() => handleRemove(notificationSoundState.bulletin)}>
        Remove custom sound
      </Button>
    {/if}
    <Button variant="ghost" onclick={() => notificationSoundState.bulletin.preview()}>
      Test sound
    </Button>
  </div>
  <input
    class="file-input"
    type="file"
    accept="audio/*"
    bind:this={bulletinFileInput}
    onchange={() => handleUpload(notificationSoundState.bulletin, bulletinFileInput, (e) => (bulletinUploadError = e))}
  />
  {#if bulletinUploadError}
    <p class="err" role="alert">{bulletinUploadError}</p>
  {/if}
  <p class="sound-hint">
    Plays when a new inbound bulletin arrives while the Bulletins page
    isn't open. Custom uploads are stored in this browser only — up to
    2 MB.
  </p>
</Box>

<style>
  .notif-hint,
  .sound-hint {
    margin-top: 12px;
    font-size: 13px;
    color: var(--text-muted);
  }
  .notif-hint + :global(button) {
    margin-top: 12px;
  }
  .sound-label {
    margin-top: 16px;
    margin-bottom: 6px;
    font-size: 13px;
    font-weight: 500;
    color: var(--text-default);
  }
  .sound-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }
  .file-input {
    display: none;
  }
  .err {
    margin-top: 8px;
    color: var(--color-danger, #d33);
    font-size: 13px;
  }
</style>
