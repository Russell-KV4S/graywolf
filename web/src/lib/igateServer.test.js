import assert from 'node:assert/strict';
import test from 'node:test';

import {
  CUSTOM_IGATE_SERVER,
  IGATE_SERVER_OPTIONS,
  igateServerSelection,
} from './igateServer.js';

test('offers the five Tier-2 regional rotate addresses and Custom', () => {
  assert.deepEqual(
    IGATE_SERVER_OPTIONS.map((option) => option.value),
    [
      'noam.aprs2.net',
      'soam.aprs2.net',
      'euro.aprs2.net',
      'asia.aprs2.net',
      'aunz.aprs2.net',
      CUSTOM_IGATE_SERVER,
    ]
  );
});

test('selects a known regional server directly', () => {
  assert.equal(igateServerSelection('euro.aprs2.net'), 'euro.aprs2.net');
});

test('preserves legacy and operator-provided hosts through Custom', () => {
  assert.equal(igateServerSelection('rotate.aprs2.net'), CUSTOM_IGATE_SERVER);
  assert.equal(igateServerSelection('aprs.example.net'), CUSTOM_IGATE_SERVER);
});
