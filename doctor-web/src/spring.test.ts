import assert from 'node:assert/strict';
import { test } from 'node:test';
import { project, rubberband } from './spring.ts';

test('momentum projection scales proportionally with velocity and points in same direction', () => {
  assert.equal(project(0), 0);
  assert.ok(project(1000) > 400);
  assert.ok(project(-500) < 0);
});

test('rubberband resists progress asymptotically and preserves sign', () => {
  const small = rubberband(20, 200);
  const big = rubberband(400, 200);
  assert.ok(small > 0 && small < 20);
  assert.ok(big > small && big < 200);
});
