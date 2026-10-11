// Takes a small picture of every lyric animation (Folia's and Flowrics' own) for the settings'
// animation picker. Runs in the web job after Folia is built; the pictures go into the bundle as
// thumbs/<id>.jpg, so both apps ship them (Android: assets/folia/thumbs, Windows: /thumbs/).
// The song is the made-up demo song, fed through folia-bridge.js like on Android.
// Usage: node thumbnails.mjs <folia dist> <controller repo> <out dir>
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const [dist, repo, outDir] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const shared = path.join(repo, 'shared/web');
const bridge = fs.readFileSync(path.join(repo, 'app/src/main/assets/folia-bridge.js'), 'utf8');

// ---------- the demo song ----------
const LINES = [
  [1000, '霓虹在雨裡慢慢融化', 'Neon slowly melting in the rain'],
  [5200, '把心跳調成你的頻率', 'Tuning my heartbeat to your frequency'],
  [9400, '風吹過舊街角', 'Wind across the old corner'],
  [13600, '流星劃過夜空寫下我們的名字', 'A meteor writes our names'],
  [19800, '天亮以前我們還在唱', 'Before dawn we are still singing'],
];
const pad = (n, w) => String(n).padStart(w, '0');
const stamp = (ms) => `[${pad(Math.floor(ms / 60000), 2)}:${pad(Math.floor(ms / 1000) % 60, 2)}.${pad(ms % 1000, 3)}]`;
let lrc = '', tl = '', aw = '';
for (const [t, text, tr] of LINES) {
  lrc += stamp(t) + text + '\n';
  tl += stamp(t) + tr + '\n';
  let s = stamp(t), off = 0;
  for (const ch of Array.from(text)) { s += `<${off},300>${ch}`; off += 300; }
  aw += s + '\n';
}
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
const container = `[awlrc:lrc:${b64(lrc)},tlrc:${b64(tl)},awlrc:${b64(aw)}]`;
const cover = 'data:image/svg+xml;base64,' + b64(
  '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
  '<stop offset="0" stop-color="#2B3A8C"/><stop offset="1" stop-color="#C45B8E"/></linearGradient></defs>' +
  '<rect width="512" height="512" fill="url(#g)"/><circle cx="360" cy="170" r="110" fill="#fff" fill-opacity=".22"/>' +
  '<circle cx="150" cy="370" r="150" fill="#fff" fill-opacity=".14"/></svg>');

function feedScript(at) {
  const events = [
    { event: 'Track', data: { id: 'demo', title: '夜行電波', author: '流詞', album: '縮圖', duration: 30000, cover } },
    { event: 'Lyric', data: { hasLyric: true, lrc: container } },
    { event: 'PlayerProgress', data: { progress: at } },
    { event: 'PlayerPauseState', data: { isPaused: false } },
  ];
  return `<script>${bridge}</script><script>
    window.FoliaNative = { requestSnapshot: function () {
      var ev = ${JSON.stringify(events)};
      setTimeout(function () { ev.forEach(function (e) { window.__foliaPush(JSON.stringify(e)); }); }, 50);
    }, tuningClosed: function () {} };
  </script>`;
}

// ---------- a tiny server: Folia's bundle at /, our shared files at /fx/ ----------
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.woff2': 'font/woff2',
  '.wasm': 'application/wasm', '.mp3': 'audio/mpeg' };
let at = 3200;
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  let file;
  if (url.pathname.startsWith('/fx/')) file = path.join(shared, url.pathname.slice(4));
  else file = path.join(dist, url.pathname === '/' ? 'index.html' : url.pathname);
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html');
  let body = fs.readFileSync(file);
  const ext = path.extname(file);
  if (ext === '.html') body = Buffer.from(body.toString('utf8').replace(/<head[^>]*>/i, (m) => m + feedScript(at)));
  res.writeHead(200, { 'content-type': types[ext] || 'application/octet-stream' });
  res.end(body);
});
await new Promise((r) => server.listen(39876, r));

const ONLY = process.env.THUMBS_ONLY ? process.env.THUMBS_ONLY.split(',') : null;
const FOLIA = ['classic', 'cadenza', 'partita', 'fume', 'monet', 'cappella', 'tilt', 'claddagh', 'diorama', 'pendolo',
  'sonnet', 'tempera', 'lumiere', 'still'];
const FX = ['ink', 'dive', 'minimal', 'karaoke', 'cinema', 'ripple', 'stardust', 'neon', 'vinyl', 'comic', 'terminal',
  'danmaku', 'led', 'boom', 'meteor', 'glitch', 'vortex', 'fireworks', 'jelly', 'warp', 'shatter', 'clock'];

const browser = await chromium.launch({
  channel: process.env.THUMBS_CHANNEL === '' ? undefined : (process.env.THUMBS_CHANNEL || 'chrome'),
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'],
});
const cfg = encodeURIComponent(JSON.stringify({ subtitleContentMode: 'translation' }));
const jobs = [
  ...FOLIA.map((m) => ({ id: m, url: `/?obs=1&obsSource=now-playing&host=127.0.0.1:39876&visualizer=${m}&cfg=${cfg}`, wait: 9000, at: 500 })),
  ...FX.map((m) => ({ id: 'fx-' + m, url: `/fx/fx.html?fx=${m}&host=127.0.0.1:39876&cfg=${cfg}`, wait: 3500, at: m === 'ink' ? 12500 : 4100 })),
];
let ok = 0;
for (const job of jobs) {
  if (ONLY && !ONLY.includes(job.id)) continue;
  at = job.at;
  const page = await browser.newPage({ viewport: { width: 640, height: 360 }, deviceScaleFactor: 1 });
  try {
    await page.goto('http://127.0.0.1:39876' + job.url, { timeout: 30000 });
    await page.waitForTimeout(job.wait);
    await page.screenshot({ path: path.join(outDir, job.id + '.jpg'), type: 'jpeg', quality: 72, timeout: 45000 });
    ok++;
    console.log('thumb ' + job.id);
  } catch (e) {
    console.log('no thumb for ' + job.id + ': ' + e.message.split('\n')[0]);
  } finally {
    await page.close().catch(() => {});
  }
}
await browser.close().catch(() => {});
server.close();
console.log(`${ok}/${jobs.length} thumbnails`);
process.exit(0);
