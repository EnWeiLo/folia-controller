// fc-audio.js — feeds the music playing on the device into Folia's audio-reactive visuals.
//
// The apps capture the sound (Windows: what the PC is playing; Android: the system audio visualizer)
// and send a spectrum ~30 times a second: 1024 bytes in the same scale as Web Audio's
// AnalyserNode.getByteFrequencyData at fftSize 2048 / 44.1 kHz (≈21.5 Hz per bin), which is what
// Folia's own player analyses. Here it becomes Folia's audioPower + five bands with Folia's own
// formulas (usePlaybackVisualizerBridge.ts), smoothed to the screen's frame rate, and goes in
// through window.__foliaAudio (added to Folia by patches/folia-audio.patch).
//
// Frames arrive from Android via window.__fcAudioFrame(base64, playing), and on Windows / LAN
// browsers over ws://<host>/api/ws/audio as {"p":0|1,"s":"<base64>"}.
(function () {
  'use strict';
  var BINS = 1024;
  var HZ_PER_BIN = 44100 / 2048;
  var STALE_MS = 400;     // no frame for this long while "playing": treat as silence
  var ACTIVE_MS = 3000;   // no frame at all for this long: the feature is off, leave Folia alone

  var target = new Float32Array(BINS);
  var current = new Float32Array(BINS);
  var bands = { power: 0, bass: 0, lowMid: 0, mid: 0, vocal: 0, treble: 0 };
  var shown = { power: 0, bass: 0, lowMid: 0, mid: 0, vocal: 0, treble: 0 };
  var playing = false;
  var lastFrameAt = 0;
  var running = false;
  var lastTick = 0;

  function decode(b64) {
    var raw = atob(b64);
    var n = Math.min(raw.length, BINS);
    for (var i = 0; i < n; i++) target[i] = raw.charCodeAt(i);
    for (; i < BINS; i++) target[i] = 0;
  }

  // Folia's band maths, on the target spectrum.
  function energy(minHz, maxHz) {
    var start = Math.floor(minHz / HZ_PER_BIN), end = Math.floor(maxHz / HZ_PER_BIN), sum = 0;
    for (var i = start; i <= end && i < BINS; i++) sum += target[i];
    var count = end - start + 1;
    return count > 0 ? sum / count : 0;
  }
  function boost(value, power) { return Math.pow(value / 255, power) * 255; }
  function computeBands() {
    var bass = energy(20, 150), lowMid = energy(150, 400), mid = energy(400, 1200);
    var vocal = energy(1000, 3500), treble = energy(3500, 12000);
    bands.power = boost((bass + lowMid) / 2, 3);
    bands.bass = boost(bass, 1.8);
    bands.lowMid = boost(lowMid, 2);
    bands.mid = boost(mid, 2);
    bands.vocal = boost(vocal, 1.5);
    bands.treble = boost(treble, 2);
  }

  function frame(b64, isPlaying) {
    try { decode(b64); } catch (e) { return; }
    playing = !!isPlaying;
    lastFrameAt = performance.now();
    computeBands();
    start();
  }

  function start() {
    if (running) return;
    running = true;
    lastTick = performance.now();
    requestAnimationFrame(tick);
  }

  function tick(now) {
    var feed = window.__foliaAudio;
    var age = now - lastFrameAt;
    if (age > ACTIVE_MS) { running = false; return; }
    requestAnimationFrame(tick);
    if (!feed) return;

    var dt = Math.max(1, now - lastTick);
    lastTick = now;
    if (!playing || age > STALE_MS) {
      // Paused or silent: Folia's own idle breathing.
      var breath = (Math.sin(Date.now() / 2000) + 1) * 20;
      feed({
        audioPower: breath,
        bands: { bass: breath, lowMid: breath, mid: breath, vocal: breath, treble: breath },
        spectrum: new Uint8Array(0),
      });
      for (var j = 0; j < BINS; j++) current[j] = 0;
      return;
    }
    // Frames come ~30 times a second; ease toward the newest so 60/120 Hz screens move smoothly.
    var k = 1 - Math.exp(-dt / 28);
    var spectrum = new Uint8Array(BINS);
    for (var i = 0; i < BINS; i++) {
      current[i] += (target[i] - current[i]) * k;
      spectrum[i] = current[i];
    }
    for (var key in shown) shown[key] += (bands[key] - shown[key]) * k;
    feed({
      audioPower: shown.power,
      bands: { bass: shown.bass, lowMid: shown.lowMid, mid: shown.mid, vocal: shown.vocal, treble: shown.treble },
      spectrum: spectrum,
    });
  }

  window.__fcAudioFrame = frame;

  // Windows app and LAN browsers: the audio stream from the PC.
  var isAndroid = !!window.__FOLIA_ANDROID_CONTROLLER__ || !!window.FoliaNative;
  if (!isAndroid && /^https?:$/.test(location.protocol)) {
    var retry = 0;
    var connect = function () {
      var socket;
      try {
        socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/ws/audio');
      } catch (e) {
        return;
      }
      socket.onopen = function () { retry = 0; };
      socket.onmessage = function (event) {
        var msg;
        try { msg = JSON.parse(event.data); } catch (e) { return; }
        if (msg && typeof msg.s === 'string') frame(msg.s, msg.p);
      };
      socket.onclose = function () {
        retry = Math.min(retry + 1, 8);
        setTimeout(connect, 1000 * retry);
      };
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', connect);
    else connect();
  }
})();
