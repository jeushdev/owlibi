import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { assets } from './build.mjs';
assert.ok(Number(process.versions.node.split('.')[0]) >= 24, 'Use Node.js 24 or newer');
for (const directory of ['src', 'scripts', 'tests/unit', 'tests/browser']) {
  for (const file of await readdir(directory)) {
    if (!/\.(mjs|js)$/.test(file)) continue;
    const result = spawnSync(process.execPath, ['--check', `${directory}/${file}`], { stdio: 'inherit' });
    assert.equal(result.status, 0, `Syntax check failed: ${directory}/${file}`);
  }
}
assert.deepEqual((await readdir('src')).sort(), [...assets].sort(), 'Declare every public asset; never copy credentials or extra files');
const html = await readFile('src/index.html', 'utf8');
assert.match(html, /<html lang="en"/); assert.match(html, /role="status"/); assert.match(html, /type="module"/);
console.log('Declared public inventory, frontend/test syntax and semantic entry checks passed.');
