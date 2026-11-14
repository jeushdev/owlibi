import { copyFile, mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
export const assets = ['index.html', 'main.js', 'styles.css', 'api.js', 'session.js', 'model.js', 'ui.js', 'brand-logo.png'];
export async function copyAssets(destination) {
  await rm(destination, { recursive: true, force: true });
  await mkdir(destination, { recursive: true });
  for (const name of assets) {
    await copyFile(new URL(`../src/${name}`, import.meta.url), new URL(name, destination));
  }
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await copyAssets(new URL('../../server-api/target/generated-resources/static/', import.meta.url));
  console.log('Copied eight public assets to Spring generated resources.');
}
