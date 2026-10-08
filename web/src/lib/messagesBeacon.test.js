import test from 'node:test';
import assert from 'node:assert/strict';
import { sendableBeacons } from './messagesBeacon.js';

test('sendableBeacons: keeps enabled beacons, shaped as {id, label}', () => {
  const rows = [
    { id: 1, type: 'position', callsign: 'W1AW-9', enabled: true },
    { id: 2, type: 'object', object_name: 'FIELDDAY', callsign: '', enabled: true },
  ];
  assert.deepEqual(sendableBeacons(rows, 'N0CAL'), [
    { id: 1, label: 'W1AW-9' },
    { id: 2, label: 'FIELDDAY' },
  ]);
});

test('sendableBeacons: drops disabled beacons', () => {
  const rows = [
    { id: 1, callsign: 'W1AW-9', enabled: false },
    { id: 2, callsign: 'W1AW-1', enabled: true },
  ];
  assert.deepEqual(sendableBeacons(rows, ''), [{ id: 2, label: 'W1AW-1' }]);
});

test('sendableBeacons: falls back to the station callsign for a blank per-row callsign', () => {
  const rows = [{ id: 3, type: 'position', callsign: '', enabled: true }];
  assert.deepEqual(sendableBeacons(rows, 'N0CAL'), [{ id: 3, label: 'N0CAL' }]);
});

test('sendableBeacons: preserves list order', () => {
  const rows = [
    { id: 5, callsign: 'C', enabled: true },
    { id: 4, callsign: 'B', enabled: true },
    { id: 6, callsign: 'A', enabled: true },
  ];
  assert.deepEqual(sendableBeacons(rows).map((b) => b.id), [5, 4, 6]);
});

test('sendableBeacons: skips rows with no id', () => {
  const rows = [{ callsign: 'W1AW', enabled: true }, { id: 7, callsign: 'W2AW', enabled: true }];
  assert.deepEqual(sendableBeacons(rows), [{ id: 7, label: 'W2AW' }]);
});

test('sendableBeacons: tolerates a non-array input', () => {
  assert.deepEqual(sendableBeacons(null), []);
  assert.deepEqual(sendableBeacons(undefined), []);
});
