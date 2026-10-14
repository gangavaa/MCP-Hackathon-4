import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/*
 * An MCP App is served as one HTML resource, so the panel's build (main.js, styles.css, any chunks) is folded into
 * its index.html: dist/panel/browser/* → dist/panel/index.html, the file the backend serves at ui://nms/dashboard.html.
 */

const dist = fileURLToPath(new URL('../dist/panel/', import.meta.url));
const browser = join(dist, 'browser');
let html = await readFile(join(browser, 'index.html'), 'utf8');

const scripts = (await readdir(browser)).filter((f) => f.endsWith('.js'));
if (scripts.some((f) => f.startsWith('chunk-'))) {
  throw new Error(`The panel build has lazy chunks (${scripts.join(', ')}) — they cannot be inlined. Remove dynamic imports from the panel.`);
}

// Stylesheets: <link rel="stylesheet" href="styles.css"> → <style>…</style>
for (const match of html.matchAll(/<link[^>]+href="([^"]+\.css)"[^>]*>/g)) {
  const css = await readFile(join(browser, match[1]), 'utf8');
  html = html.replace(match[0], () => `<style>${css}</style>`);
}
// Scripts: <script src="main.js" type="module"></script> → inline module
for (const match of html.matchAll(/<script[^>]+src="([^"]+\.js)"[^>]*><\/script>/g)) {
  const js = (await readFile(join(browser, match[1]), 'utf8')).replaceAll('</script', '<\\/script');
  html = html.replace(match[0], () => `<script type="module">${js}</script>`);
}
// Preload hints point at files that no longer exist on their own.
html = html.replace(/<link rel="modulepreload"[^>]*>/g, '');

await writeFile(join(dist, 'index.html'), html);
console.log(`Panel inlined: dist/panel/index.html (${Math.round(html.length / 1024)} KB)`);
