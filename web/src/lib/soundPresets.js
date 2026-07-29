// Built-in notification sounds. The two APRS-branded tones (`aprs-message`,
// `aprs-bulletin`) are shipped wav files in web/public/sounds/ — the
// station operator's own recordings, used as the shipped defaults. The
// rest are synthesized via the Web Audio API rather than bundled as
// assets, so they cost nothing to keep in the repo.

export const SOUND_PRESETS = [
  { id: 'aprs-message', label: 'APRS Message', url: '/sounds/aprs-message.wav' },
  { id: 'aprs-bulletin', label: 'APRS Bulletin', url: '/sounds/aprs-bulletin.wav' },
  { id: 'chime', label: 'Chime', tones: [{ freq: 880, dur: 0.09 }, { freq: 1318.5, dur: 0.16 }] },
  { id: 'ping', label: 'Ping', tones: [{ freq: 1046.5, dur: 0.14 }] },
  { id: 'alert', label: 'Alert', tones: [{ freq: 660, dur: 0.08 }, { freq: 660, dur: 0.1 }] },
];

const GAP_SEC = 0.05;

/** @type {AudioContext|null} */
let ctx = null;

function audioCtx() {
  if (typeof window === 'undefined') return null;
  const Ctor = window.AudioContext || window.webkitAudioContext;
  if (!Ctor) return null;
  if (!ctx) ctx = new Ctor();
  if (ctx.state === 'suspended') ctx.resume().catch(() => {});
  return ctx;
}

function playTones(tones) {
  const ac = audioCtx();
  if (!ac) return;
  let t = ac.currentTime;
  for (const tone of tones) {
    const osc = ac.createOscillator();
    const gain = ac.createGain();
    osc.type = 'sine';
    osc.frequency.value = tone.freq;
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(0.25, t + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + tone.dur);
    osc.connect(gain).connect(ac.destination);
    osc.start(t);
    osc.stop(t + tone.dur + 0.02);
    t += tone.dur + GAP_SEC;
  }
}

/** @param {string} id one of SOUND_PRESETS' ids; falls back to the first preset */
export function resolvePreset(id) {
  return SOUND_PRESETS.find((p) => p.id === id) || SOUND_PRESETS[0];
}

/**
 * @param {string} id one of SOUND_PRESETS' ids; falls back to the first preset.
 * No-ops outside a browser (no `window`/`Audio`) rather than throwing, so
 * callers (and their tests) don't need to feature-detect first.
 */
export function playPreset(id) {
  if (typeof window === 'undefined') return;
  const preset = resolvePreset(id);
  if (preset.url) {
    if (typeof Audio === 'undefined') return;
    new Audio(preset.url).play().catch(() => {});
    return;
  }
  playTones(preset.tones);
}
