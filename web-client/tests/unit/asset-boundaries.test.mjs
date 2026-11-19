import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { assets, copyAssets } from '../../scripts/build.mjs';
test('copy build includes only public assets and removes stale files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'owlibi-assets-'));
  try {
    await writeFile(join(dir, 'stale-secret.env'), 'synthetic-secret');
    await copyAssets(pathToFileURL(`${dir}/`));
    assert.deepEqual((await readdir(dir)).sort(), [...assets].sort());
    for (const name of assets) {
      assert.deepEqual(await readFile(join(dir, name)), await readFile(new URL(`../../src/${name}`, import.meta.url)));
    }
  } finally { await rm(dir, { recursive: true, force: true }); }
});
test('public modules retain same-origin, memory-only, secret-free boundaries', async () => {
  const source = (await Promise.all(assets.filter(name => /\.(html|js|css)$/.test(name)).map(name => readFile(new URL(`../../src/${name}`, import.meta.url), 'utf8')))).join('\n');
  const api = await readFile(new URL('../../src/api.js', import.meta.url), 'utf8');
  const css = await readFile(new URL('../../src/styles.css', import.meta.url), 'utf8');
  assert.match(api, /path.startsWith\('\/api\/v1\/'\)/);
  assert.match(api, /credentials: 'same-origin'/);
  assert.match(api, /AbortSignal.timeout/);
  assert.doesNotMatch(source, /localStorage|sessionStorage|indexedDB|serviceWorker|jdbc:|postgres:|OWLIBI_.*PASSWORD|supabase|https?:\/\//i);
  assert.doesNotMatch(source, /Reset demo|demo role|document.cookie|eval\(/i);
  const motion = css.match(/@media \(prefers-reduced-motion:no-preference\)\s*\{\s*button,\.card\s*\{[^{}]*transition:[^{}]*\}\s*\}/);
  assert.ok(motion, 'Optional control transitions respect reduced motion');
  assert.doesNotMatch(css.replace(motion[0], ''), /animation|transition/);
  assert.match(source, /X-Session-Epoch/); assert.match(source, /X-CSRF-TOKEN/);
});
