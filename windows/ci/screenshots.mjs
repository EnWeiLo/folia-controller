// Renders the Folia page from the smoke-test server in Chrome (desktop window size and a tablet)
// and saves screenshots, so a build can be checked by eye.
// Usage: node screenshots.mjs <port> <outDir>   (needs playwright-core installed next to it)
import { chromium } from 'playwright-core';
import fs from 'node:fs';

const port = Number(process.argv[2] || 39999);
const outDir = process.argv[3] || 'screenshots';
fs.mkdirSync(outDir, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome' });
const errors = [];

async function shoot(name, options, actions) {
  const context = await browser.newContext(options);
  const page = await context.newPage();
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForTimeout(9000);
  await actions(page);
  await page.screenshot({ path: `${outDir}/${name}.png` });
  await context.close();
}

await shoot('desktop', { viewport: { width: 1600, height: 900 } }, async (page) => {
  await page.mouse.move(800, 400);
  await page.mouse.move(820, 420);
  await page.waitForTimeout(600);
});
await shoot('desktop-settings', { viewport: { width: 1600, height: 900 } }, async (page) => {
  await page.mouse.move(800, 400);
  await page.waitForTimeout(300);
  await page.evaluate(() => document.getElementById('fc-settings').click());
  await page.waitForTimeout(600);
});
await shoot('tablet', {
  viewport: { width: 1024, height: 640 }, deviceScaleFactor: 2.5, isMobile: true, hasTouch: true,
  userAgent: 'Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36',
}, async (page) => {
  await page.touchscreen.tap(500, 200);
  await page.waitForTimeout(600);
});

await browser.close();
console.log(errors.length ? 'page errors:\n' + errors.join('\n') : 'no page errors');
