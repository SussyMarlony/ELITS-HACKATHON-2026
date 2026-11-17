import assert from 'node:assert/strict';
import { test } from 'node:test';
import { unwrapSecret, wrapSecret } from './passcode.ts';

test('wrap and unwrap with the right passcode only', async () => {
  const secret = crypto.getRandomValues(new Uint8Array(32));
  const w = await wrapSecret(secret, 'correct horse');
  assert.deepEqual(await unwrapSecret(w, 'correct horse'), secret);
  assert.equal(await unwrapSecret(w, 'wrong horse'), null);
});
