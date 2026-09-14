// Guard for the Svelte 5 effect-dependency rule that GH #578 and GH #584 both
// broke. LiveMapV2.svelte states it as a MUST:
//
//   We MUST read the reactive value before the optional-chain so Svelte 5
//   tracks it as a dependency on the initial run. With
//   `layer?.setVisible(toggle)`, if `layer` is null on first run (mount before
//   onMapReady), the RHS is short-circuited and `toggle` is never read -- the
//   effect ends up with zero deps and never re-fires.
//
// The map layer modules are assigned in onMapReady(), which fires on the map's
// `load` event -- after effects first run -- so every such effect hits the null
// case on run 1 and silently dies.
//
// This is a source-level check because the web suite is plain `node --test`
// over pure JS with no Svelte component harness, so a miswired effect is
// otherwise untestable by construction: the layer modules' own unit tests call
// setOpacity()/setFrames() directly and pass either way.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..');

function svelteFiles(dir) {
  const out = [];
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, ent.name);
    if (ent.isDirectory()) out.push(...svelteFiles(p));
    else if (ent.name.endsWith('.svelte')) out.push(p);
  }
  return out;
}

// Drop whole-line comments so the commented-out effects in LiveMapV2.svelte
// don't register as findings.
function stripLineComments(src) {
  return src
    .split('\n')
    .map((l) => (l.trim().startsWith('//') ? '' : l))
    .join('\n');
}

// Extract the text between the parens/braces starting at `open`.
function balanced(src, open, [l, r]) {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === l) depth++;
    else if (src[i] === r && --depth === 0) return src.slice(open + 1, i);
  }
  return '';
}

function effectBodies(src) {
  const out = [];
  const re = /\$effect\(/g;
  let m;
  while ((m = re.exec(src))) out.push(balanced(src, m.index + '$effect'.length, ['(', ')']));
  return out;
}

// `thing?.method(<args>)` where an arg dereferences a property off some other
// object -- i.e. a read that the optional chain will skip when `thing` is null.
// A hoisted local (`layer?.setOpacity(v)`) has no dot and is fine.
function shortCircuitedReads(body) {
  const out = [];
  const re = /(\w+)\?\.(\w+)\(/g;
  let m;
  while ((m = re.exec(body))) {
    const args = balanced(body, m.index + m[0].length - 1, ['(', ')']);
    if (/[A-Za-z_$][\w$]*\.[A-Za-z_$]/.test(args)) {
      out.push(`${m[1]}?.${m[2]}(${args.trim()})`);
    }
  }
  return out;
}

test('no $effect reads reactive state only behind an optional chain', () => {
  const findings = [];
  for (const file of svelteFiles(SRC)) {
    const src = stripLineComments(readFileSync(file, 'utf8'));
    for (const body of effectBodies(src)) {
      for (const hit of shortCircuitedReads(body)) {
        findings.push(`${relative(SRC, file)}: ${hit}`);
      }
    }
  }
  assert.deepEqual(
    findings,
    [],
    'Reactive state read only inside an optional-chained call never registers as ' +
      'an effect dependency when the receiver is null on the first run, so the ' +
      'effect never re-fires. Hoist the read into a const first:\n  ' +
      findings.join('\n  '),
  );
});
