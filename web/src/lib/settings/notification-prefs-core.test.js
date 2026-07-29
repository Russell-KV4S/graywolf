import { strict as assert } from 'node:assert';
import { describe, it } from 'node:test';

import { VALID_MODES, parseMode, parseEnabledFlag, resolveModeAfterPermission } from './notification-prefs-core.js';

describe('parseMode', () => {
  it('passes through valid modes', () => {
    for (const m of VALID_MODES) {
      if (m === 'toast') continue;
      assert.equal(parseMode(m), m);
    }
  });

  it('defaults to toast for null/undefined/garbage', () => {
    assert.equal(parseMode(null), 'toast');
    assert.equal(parseMode(undefined), 'toast');
    assert.equal(parseMode('bogus'), 'toast');
    assert.equal(parseMode(''), 'toast');
  });

  it('explicit toast round-trips', () => {
    assert.equal(parseMode('toast'), 'toast');
  });
});

describe('parseEnabledFlag', () => {
  it('defaults true when never stored (null/undefined) — on-by-default', () => {
    assert.equal(parseEnabledFlag(null), true);
    assert.equal(parseEnabledFlag(undefined), true);
  });

  it('reads back an explicit "1" as true and anything else as false', () => {
    assert.equal(parseEnabledFlag('1'), true);
    assert.equal(parseEnabledFlag('0'), false);
    assert.equal(parseEnabledFlag('garbage'), false);
  });
});

describe('resolveModeAfterPermission', () => {
  it('toast never needs permission and passes straight through', () => {
    assert.equal(resolveModeAfterPermission('toast', 'granted'), 'toast');
    assert.equal(resolveModeAfterPermission('toast', 'denied'), 'toast');
    assert.equal(resolveModeAfterPermission('toast', 'default'), 'toast');
  });

  it('os/both are kept when permission is granted', () => {
    assert.equal(resolveModeAfterPermission('os', 'granted'), 'os');
    assert.equal(resolveModeAfterPermission('both', 'granted'), 'both');
  });

  it('os/both fall back to toast on denial so the operator never lands in a dead mode', () => {
    assert.equal(resolveModeAfterPermission('os', 'denied'), 'toast');
    assert.equal(resolveModeAfterPermission('both', 'denied'), 'toast');
  });

  it('os/both fall back to toast on any non-granted permission (e.g. still "default")', () => {
    assert.equal(resolveModeAfterPermission('os', 'default'), 'toast');
  });

  it('an unrecognized requested mode falls back to toast', () => {
    assert.equal(resolveModeAfterPermission('bogus', 'granted'), 'toast');
  });
});
