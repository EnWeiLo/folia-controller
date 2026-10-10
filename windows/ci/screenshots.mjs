// Renders the Folia page from the smoke-test server in Chrome (desktop window size and a tablet)
// and saves screenshots, so a build can be checked by eye.
// Usage: node screenshots.mjs <port> <outDir>   (needs playwright-core installed next to it)
import { chromium } from 'playwright-core';
import fs from 'node:fs';

const port = Number(process.argv[2] || 39999);
const outDir = process.argv[3] || 'screenshots';
fs.mkdirSync(outDir, { recursive: true });
// Build servers have no GPU: software WebGL, or screenshots wait forever for a frame.
const browser = await chromium.launch({
  channel: 'chrome',
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const errors = [];

async function shoot(name, options, actions) {
  const context = await browser.newContext(options);
  try {
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
    await page.goto(`http://127.0.0.1:${port}/`);
    await page.waitForTimeout(9000);
    await actions(page);
    await page.screenshot({ path: `${outDir}/${name}.png`, timeout: 60000 });
    console.log(`saved ${name}.png`);
  } catch (e) {
    errors.push(`${name}: screenshot failed: ${e.message.split('\n')[0]}`);
  } finally {
    await context.close();
  }
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
  // The info card shows from the start (a tap would hide it); give the lyrics time to arrive.
  await page.waitForTimeout(12000);
  // Folia's bottom subtitle line should sit above the card.
  const info = await page.evaluate(() => {
    const el = document.querySelector('div.absolute.left-0.right-0.text-center.z-20.pointer-events-none[style*="bottom"]');
    const bar = document.getElementById('fc-bar').getBoundingClientRect();
    const sub = el && el.getBoundingClientRect();
    return { found: !!el, subtitleBottomEdge: sub && Math.round(sub.bottom), cardTop: Math.round(bar.top),
      inset: getComputedStyle(document.documentElement).getPropertyValue('--fc-inset') };
  });
  console.log('tablet subtitle vs card: ' + JSON.stringify(info));
  // 音樂律動: the patched Folia page takes audio, and a frame goes through without errors.
  const audio = await page.evaluate(() => {
    const ready = typeof window.__foliaAudio === 'function';
    const bytes = new Uint8Array(1024).map((_, i) => (i < 60 ? 220 : 40));
    let s = ''; bytes.forEach((b) => { s += String.fromCharCode(b); });
    window.__fcAudioFrame(btoa(s), 1);
    return { ready, feeder: typeof window.__fcAudioFrame };
  });
  await page.waitForTimeout(500);
  console.log('audio feed: ' + JSON.stringify(audio));
  if (!audio.ready) errors.push('tablet: Folia does not take audio (patch missing?)');
});

// 商籟 with its outer frame text switched off and the next-line preview hidden (options from the
// 動畫與外觀 panel), then the panel itself. Folia must start with them and show no errors.
await shoot('sonnet-tuned', { viewport: { width: 1600, height: 900 } }, async (page) => {
  await page.evaluate(() => {
    localStorage.setItem('fc.mode', JSON.stringify('sonnet'));
    localStorage.setItem('fc.tuning', JSON.stringify({ m: { sonnet: { outerFrameMode: 'none', showGiantDecorativeText: false } }, x: { hideUpcoming: true } }));
  });
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForTimeout(12000);
  const cfg = await page.evaluate(() => new URL(location.href).searchParams.get('cfg'));
  console.log('sonnet cfg: ' + cfg.slice(0, 160));
  if (!/"outerFrameMode":"none"/.test(cfg)) errors.push('sonnet-tuned: options were not merged into cfg');
});
await shoot('tuning-panel', { viewport: { width: 1600, height: 900 } }, async (page) => {
  await page.evaluate(() => window.FcTuning.open({ u: 1 }));
  await page.waitForTimeout(500);
});

await browser.close();
console.log(errors.length ? 'page errors:\n' + errors.join('\n') : 'no page errors');
