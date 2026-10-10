// CI check for the Windows exe, run against `FoliaController.exe --smoke-test <port> <log>`.
// Talks to the real server like Folia and the control bar do, and fails the build on any mismatch.
// Usage: node smoke.mjs <port>
import http from 'node:http';

const port = Number(process.argv[2] || 39999);
const base = `http://127.0.0.1:${port}`;
let failures = 0;
const check = (ok, label, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  — ' + detail : ''}`);
  if (!ok) failures++;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function raw(path, headers = {}, method = 'GET', body) {
  return new Promise((resolve) => {
    const req = http.request({ host: '127.0.0.1', port, path, method, headers }, (res) => {
      let data = '';
      res.on('data', (d) => (data += d));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', (e) => resolve({ status: 0, error: e.message, body: '' }));
    req.on('upgrade', (res, socket) => { socket.destroy(); resolve({ status: 101, body: '' }); });
    if (body) req.write(body);
    req.end();
  });
}

// Wait for the server.
let up = false;
for (let i = 0; i < 60 && !up; i++) {
  try { up = (await fetch(base + '/api/state')).ok; } catch { await sleep(500); }
}
check(up, 'server answers');
if (!up) process.exit(1);

const launcher = await fetch(base + '/');
const launcherText = await launcher.text();
check(launcher.ok && launcherText.includes('location.replace'), 'launcher page at /');

const index = await fetch(base + '/?obs=1&obsSource=now-playing&host=127.0.0.1:' + port + '&visualizer=classic');
const indexText = await index.text();
check(index.ok && indexText.includes('/__fc/overlay.js'), 'Folia page has the control bar injected');
check(!/registerSW/.test(indexText), 'service worker registration removed');
check(!/name=["']viewport/.test(indexText), 'Folia viewport tag removed');
const mainScript = (indexText.match(/src="(\/assets\/[^"]+\.js)"/) || [])[1];
check(!!mainScript, 'Folia main script referenced', mainScript || 'none');
if (mainScript) {
  const js = await fetch(base + mainScript);
  check(js.ok && (js.headers.get('content-type') || '').startsWith('text/javascript'), 'Folia bundle served', `${js.status} ${js.headers.get('content-type')}`);
  check((js.headers.get('cache-control') || '').includes('immutable'), 'hashed assets cached');
}
for (const f of ['overlay.js', 'overlay.css', 'launcher.html']) {
  const r = await fetch(base + '/__fc/' + f);
  check(r.ok, `/__fc/${f}`);
}
check((await fetch(base + '/registerSW.js')).status === 404, 'registerSW.js is 404');
check((await fetch(base + '/nope.js')).status === 404, 'unknown file is 404');
check((await raw('/', { Host: 'evil.example:' + port })).status === 403, 'foreign Host header refused (DNS rebinding)');
check((await raw('/api/ws/lyric', { Host: '127.0.0.1:' + port, Origin: 'https://evil.example', Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==', 'Sec-WebSocket-Version': '13' })).status === 403, 'cross-site WebSocket refused');
check((await raw('/api/control', { Host: '127.0.0.1:' + port, 'Content-Type': 'application/json' }, 'POST', '{"action":"next"}')).status === 403, 'POST without X-FC refused');

// WebSocket, as Folia connects.
const events = [];
const ws = new WebSocket(`ws://127.0.0.1:${port}/api/ws/lyric`);
ws.onmessage = (e) => events.push(JSON.parse(e.data));
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = () => reject(new Error('ws failed')); }).catch((e) => check(false, 'WebSocket opens', e.message));
await sleep(1500);
const names = events.map((e) => e.event);
check(['Track', 'Lyric', 'PlayerProgress', 'PlayerPauseState', 'ControllerState'].every((n) => names.includes(n)), 'snapshot events on connect', names.slice(0, 6).join(','));
const lastOf = (name) => [...events].reverse().find((e) => e.event === name)?.data;
const track = lastOf('Track');
check(track && track.title === '晴天' && track.author === '周杰倫' && track.duration === 269000, 'Track event', JSON.stringify(track));
const progress = lastOf('PlayerProgress');
check(progress && progress.progress > 0, 'PlayerProgress event', JSON.stringify(progress));
const order = names.indexOf('PlayerProgress') < names.indexOf('PlayerPauseState');
check(order, 'progress sent before pause state');

// Cover and lyrics arrive a moment later.
for (let i = 0; i < 40 && !(lastOf('Track')?.cover && lastOf('ControllerState')?.lyricStatus?.startsWith('歌詞')); i++) await sleep(500);
const cover = lastOf('Track')?.cover;
check(!!cover, 'cover URL in Track', cover || 'none');
if (cover) {
  const c = await fetch(base + cover);
  check(c.ok && c.headers.get('content-type') === 'image/png', 'cover served', `${c.status} ${c.headers.get('content-type')}`);
}
const state = lastOf('ControllerState');
console.log('lyric status:', state?.lyricStatus);
check(state?.album === '葉惠美', 'album in control state', JSON.stringify(state?.album));
check(typeof state?.cover === 'string' && state.cover.startsWith('/api/cover/'), 'cover in control state', JSON.stringify(state?.cover));
const lyric = lastOf('Lyric');
const container = lyric?.lrc || '';
const tracks = Object.fromEntries((container.match(/^\[awlrc:(.+)\]$/)?.[1] || '').split(',').map((part) => {
  const i = part.indexOf(':');
  return [part.slice(0, i), Buffer.from(part.slice(i + 1), 'base64').toString('utf8')];
}));
console.log('lyric event:', JSON.stringify({ source: lyric?.source, hasLyric: lyric?.hasLyric, tracks: Object.keys(tracks), lrcStart: (tracks.lrc || '').slice(0, 90) }));
if (lyric?.hasLyric) {
  check(container.startsWith('[awlrc:lrc:') && (tracks.lrc || '').includes('['), 'lyrics sent as a Folia container', Object.keys(tracks).join(','));
  check(!/[从这个们发书黄词]/.test((tracks.lrc || '') + (tracks.awlrc || '')), 'Chinese lyrics converted to Traditional');
}

// Controls.
const post = (path, body) => fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-FC': '1' }, body: JSON.stringify(body) });
const before = events.length;
check((await post('/api/control', { action: 'toggle' })).ok, 'toggle accepted');
await sleep(1500);
const afterToggle = events.slice(before);
check(afterToggle.some((e) => e.event === 'PlayerPauseState' && e.data.isPaused === true), 'pause reaches Folia', afterToggle.map((e) => e.event).join(','));
check(afterToggle.some((e) => e.event === 'ControllerState' && e.data.playing === false), 'pause reaches the control bar');
check((await post('/api/control', { action: 'seek', positionMs: 120000 })).ok, 'seek accepted');
await sleep(1500);
const seekProgress = lastOf('PlayerProgress');
check(seekProgress && Math.abs(seekProgress.progress - 120000) < 2000, 'seek reaches Folia', JSON.stringify(seekProgress));
check((await post('/api/settings', { offsetMs: 500 })).ok, 'settings accepted');
await sleep(800);
const settingsState = lastOf('ControllerState');
check(settingsState?.settings?.offsetMs === 500, 'settings broadcast', JSON.stringify(settingsState?.settings));
const offsetProgress = lastOf('PlayerProgress');
check(offsetProgress && Math.abs(offsetProgress.progress - 119500) < 2000, 'offset applied to Folia clock', JSON.stringify(offsetProgress));

// Resync request (page woke up) re-sends the snapshot.
const beforeResync = events.length;
ws.send('{"type":"resync"}');
await sleep(800);
check(events.slice(beforeResync).some((e) => e.event === 'Track'), 'resync re-sends snapshot');
ws.close();

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
