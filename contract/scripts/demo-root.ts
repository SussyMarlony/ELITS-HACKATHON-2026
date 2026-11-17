// Creates the DEMO root key. shortcut: the private key is committed in demo/root.json so the
// whole team can sign demo licenses; a real deployment keeps the root key offline and secret.
// Re-running replaces the key, which invalidates every license and needs a new APK build.
import { existsSync, writeFileSync } from 'node:fs';
import { generateSigningKey } from '../contract.ts';
import { keyToJson } from './lib.ts';

const path = new URL('../demo/root.json', import.meta.url);
if (existsSync(path) && !process.argv.includes('--force')) {
  console.error('demo/root.json exists. Pass --force to replace it (breaks all existing licenses).');
  process.exit(1);
}
writeFileSync(path, JSON.stringify({ warning: 'DEMO ONLY - never use for real patients', ...keyToJson(await generateSigningKey()) }, null, 2) + '\n');
console.log('wrote', path.pathname);
