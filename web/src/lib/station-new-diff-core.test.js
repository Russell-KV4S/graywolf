// Tests for the pure "newly heard" (threshold-crossing) diffing helper
// used by stationNewTransport.js.
//
// Run with:
//   node --test src/lib/station-new-diff-core.test.js

import { strict as assert } from 'node:assert';
import { describe, it } from 'node:test';

import { diffNewlyHeard } from './station-new-diff-core.js';

const HOUR_MS = 60 * 60 * 1000;
const T0 = Date.parse('2026-07-31T00:00:00Z');

function row(callsign, isoTime) {
  return { callsign, last_heard: isoTime };
}

describe('diffNewlyHeard', () => {
  it('treats a callsign never seen before as newly-crossed, and records it', () => {
    const known = new Map();
    const roster = [row('W1NEW-9', '2026-07-31T00:00:00Z')];
    assert.deepEqual(diffNewlyHeard(known, roster, HOUR_MS * 2), roster);
    assert.equal(known.get('W1NEW-9'), T0);
  });

  it('does not re-fire for a station heard again well within the threshold', () => {
    const known = new Map([['W1ABC-9', T0]]);
    const roster = [row('W1ABC-9', '2026-07-31T00:30:00Z')]; // 30 min later
    assert.deepEqual(diffNewlyHeard(known, roster, HOUR_MS * 2), []);
    // Still records the refreshed timestamp.
    assert.equal(known.get('W1ABC-9'), Date.parse('2026-07-31T00:30:00Z'));
  });

  it('fires again once the gap since last recorded hearing meets the threshold', () => {
    const known = new Map([['W1ABC-9', T0]]);
    const roster = [row('W1ABC-9', '2026-07-31T02:00:00Z')]; // exactly 2h later
    assert.deepEqual(diffNewlyHeard(known, roster, HOUR_MS * 2), roster);
  });

  it('does not fire when the gap is just under the threshold', () => {
    const known = new Map([['W1ABC-9', T0]]);
    const roster = [row('W1ABC-9', '2026-07-31T01:59:59Z')];
    assert.deepEqual(diffNewlyHeard(known, roster, HOUR_MS * 2), []);
  });

  it('Infinity threshold reproduces once-per-lifetime behavior', () => {
    const known = new Map();
    const first = [row('W1ABC-9', '2026-07-31T00:00:00Z')];
    assert.deepEqual(diffNewlyHeard(known, first, Infinity), first);
    const muchLater = [row('W1ABC-9', '2026-08-15T00:00:00Z')];
    assert.deepEqual(diffNewlyHeard(known, muchLater, Infinity), []);
  });

  it('handles multiple rows, mixing crossed and not-yet-crossed', () => {
    const known = new Map([
      ['W1OLD-9', T0], // heard 30 min ago, under threshold
    ]);
    const roster = [
      row('W1OLD-9', '2026-07-31T00:30:00Z'),
      row('W1FRESH-9', '2026-07-31T00:30:00Z'), // never seen before
    ];
    assert.deepEqual(diffNewlyHeard(known, roster, HOUR_MS * 2), [roster[1]]);
  });

  it('ignores malformed rows (no callsign, unparseable last_heard)', () => {
    const known = new Map();
    const roster = [{ last_heard: '2026-07-31T00:00:00Z' }, row('W1BAD-9', 'not-a-date'), row('W1OK-9', '2026-07-31T00:00:00Z')];
    assert.deepEqual(diffNewlyHeard(known, roster, HOUR_MS), [roster[2]]);
    assert.ok(!known.has('W1BAD-9'));
  });

  it('handles an empty or missing roster', () => {
    const known = new Map([['W1OLD-9', T0]]);
    assert.deepEqual(diffNewlyHeard(known, [], HOUR_MS), []);
    assert.deepEqual(diffNewlyHeard(known, undefined, HOUR_MS), []);
  });
});
