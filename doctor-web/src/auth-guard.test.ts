import assert from 'node:assert/strict';
import { test } from 'node:test';
import { AuthThrottle } from './auth-guard.ts';

test('auth throttle allows initial tries then escalates cooldowns and resets on success', () => {
  const t = new AuthThrottle();
  assert.equal(t.cooldownSeconds(1000), 0);
  assert.equal(t.recordFailure(1000), 0); // 1st
  assert.equal(t.recordFailure(2000), 0); // 2nd
  assert.equal(t.recordFailure(3000), 5); // 3rd -> 5s
  assert.equal(t.cooldownSeconds(5000), 3);
  assert.equal(t.cooldownSeconds(8000), 0);
  assert.equal(t.recordFailure(9000), 15); // 4th -> 15s
  assert.equal(t.recordFailure(30000), 30); // 5th -> 30s
  t.reset();
  assert.equal(t.cooldownSeconds(31000), 0);
  assert.equal(t.recordFailure(31000), 0);
});
