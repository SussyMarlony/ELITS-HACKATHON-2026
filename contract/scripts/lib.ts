import { readFileSync } from 'node:fs';
import { b64u, unb64u, type KeyPair } from '../contract.ts';

export const now = () => Math.floor(Date.now() / 1000);
export const DAY = 86_400;

export const keyToJson = (k: KeyPair) => ({ pub: b64u(k.pub), priv: b64u(k.priv) });
export const keyFromJson = (j: { pub: string; priv: string }): KeyPair => ({ pub: unb64u(j.pub), priv: unb64u(j.priv) });

export function demoRoot(): KeyPair {
  return keyFromJson(JSON.parse(readFileSync(new URL('../demo/root.json', import.meta.url), 'utf8')));
}
