// overlay.js — Folia 控制器's control bar and settings, drawn over Folia's Now Playing page.
// Loaded as a classic script at the top of <head>, so the first part runs before Folia
// (whose bundle is a deferred module) and can still see the real devicePixelRatio and set
// the viewport. The UI is built once the DOM is ready.
(function () {
  'use strict';

  // ---------- per-device display settings ----------
  var store = {
    get: function (key, fallback) {
      try {
        var raw = localStorage.getItem('fc.' + key);
        return raw == null ? fallback : JSON.parse(raw);
      } catch (e) { return fallback; }
    },
    set: function (key, value) {
      try { localStorage.setItem('fc.' + key, JSON.stringify(value)); } catch (e) { /* private mode */ }
    },
  };

  var webview = window.chrome && window.chrome.webview ? window.chrome.webview : null;
  var isMobile = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent) ||
    (navigator.maxTouchPoints > 1 && /Macintosh/.test(navigator.userAgent));

  var QUALITIES = [
    { id: 'high', label: '高畫質', detail: '原生解析度', scale: 1 },
    { id: 'balanced', label: '均衡', detail: '約 75% 解析度', scale: 0.75 },
    { id: 'smooth', label: '流暢', detail: '約 60%，關閉幾何背景', scale: 0.6 },
  ];
  var MODES = [
    ['classic', '流光'], ['cadenza', '心象'], ['partita', '雲階'], ['fume', '浮名'], ['monet', '莫奈'],
    ['cappella', '群唱'], ['tilt', '傾訴'], ['claddagh', '回環'], ['diorama', '鏡台'], ['pendolo', '時計'],
    ['sonnet', '商籟'], ['tempera', '凝彩'], ['lumiere', '繪光'], ['still', '靜止'],
  ];
  var OFFSETS = [-1000, -500, -250, 0, 250, 500, 750, 1000, 1500];
  // Folia's subtitle line under the lyrics (subtitleContentMode), chosen per device.
  var SUBTITLES = [['translation', '中文翻譯'], ['romanization', '羅馬拼音'], ['both', '翻譯＋羅馬拼音'], ['none', '不顯示']];

  // Quality applies where we control how many pixels a CSS px is: the desktop window
  // (through WebView2 zoom) and phones / tablets (through the viewport). A desktop
  // browser always renders at its own zoom, so it gets no quality setting.
  var qualityApplies = !!webview || isMobile;
  function qualityId() {
    var id = store.get('quality', webview ? 'high' : 'balanced');
    for (var i = 0; i < QUALITIES.length; i++) if (QUALITIES[i].id === id) return id;
    return 'balanced';
  }
  function qualityScale(id) {
    for (var i = 0; i < QUALITIES.length; i++) if (QUALITIES[i].id === id) return QUALITIES[i].scale;
    return 1;
  }

  // How many CSS px make one "real" px, for sizing our own UI.
  var uiScale = 1;
  var realDpr = window.devicePixelRatio || 1;

  // Phones / tablets: lay the page out in physical pixels × quality, like the Android app,
  // so Folia (designed for a 1080 / 1920 px canvas) renders sharp instead of upscaled.
  if (isMobile && !webview) {
    var r = qualityScale(qualityId());
    var s = (1 / (realDpr * r)).toFixed(4);
    var meta = document.createElement('meta');
    meta.name = 'viewport';
    meta.content = 'initial-scale=' + s + ', minimum-scale=' + s + ', maximum-scale=' + s +
      ', user-scalable=no, viewport-fit=cover';
    document.head.appendChild(meta);
    uiScale = realDpr * r;
  }

  // ---------- helpers ----------
  function $(id) { return document.getElementById(id); }
  function fmt(ms) {
    var t = Math.max(0, Math.floor((ms || 0) / 1000));
    return Math.floor(t / 60) + ':' + ('0' + (t % 60)).slice(-2);
  }
  function post(path, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-FC': '1' },
      body: JSON.stringify(body || {}),
    }).catch(function () { /* the next state push will correct the UI */ });
  }
  function hostMessage(message) {
    if (webview) webview.postMessage(message);
  }
  function reloadWithSettings() {
    location.replace('/');
  }

  var ICONS = {
    play: 'M8 5.14v13.72a1 1 0 0 0 1.52.85l10.98-6.86a1 1 0 0 0 0-1.7L9.52 4.29A1 1 0 0 0 8 5.14z',
    pause: 'M7 4.5h3.2a.8.8 0 0 1 .8.8v13.4a.8.8 0 0 1-.8.8H7a.8.8 0 0 1-.8-.8V5.3a.8.8 0 0 1 .8-.8zm6.8 0H17a.8.8 0 0 1 .8.8v13.4a.8.8 0 0 1-.8.8h-3.2a.8.8 0 0 1-.8-.8V5.3a.8.8 0 0 1 .8-.8z',
    prev: 'M6.5 5a1 1 0 0 1 1 1v4.6l9.98-5.94A1 1 0 0 1 19 5.52v12.96a1 1 0 0 1-1.52.86L7.5 13.4V18a1 1 0 1 1-2 0V6a1 1 0 0 1 1-1z',
    next: 'M17.5 5a1 1 0 0 0-1 1v4.6L6.52 4.66A1 1 0 0 0 5 5.52v12.96a1 1 0 0 0 1.52.86l9.98-5.94V18a1 1 0 1 0 2 0V6a1 1 0 0 0-1-1z',
    gear: 'M19.43 12.98c.04-.32.07-.64.07-.98s-.03-.66-.07-.98l2.11-1.65a.5.5 0 0 0 .12-.64l-2-3.46a.5.5 0 0 0-.61-.22l-2.49 1a7.3 7.3 0 0 0-1.69-.98l-.38-2.65A.49.49 0 0 0 14 2h-4a.49.49 0 0 0-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1a.5.5 0 0 0-.61.22l-2 3.46a.49.49 0 0 0 .12.64l2.11 1.65c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65a.5.5 0 0 0-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.23.09.49 0 .61-.22l2-3.46a.5.5 0 0 0-.12-.64l-2.11-1.65zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z',
    expand: 'M4 9V5a1 1 0 0 1 1-1h4v2H6v3H4zm11-5h4a1 1 0 0 1 1 1v4h-2V6h-3V4zM4 15h2v3h3v2H5a1 1 0 0 1-1-1v-4zm14 0h2v4a1 1 0 0 1-1 1h-4v-2h3v-3z',
    shrink: 'M7 4h2v4a1 1 0 0 1-1 1H4V7h3V4zm8 0h2v3h3v2h-4a1 1 0 0 1-1-1V4zM4 15h4a1 1 0 0 1 1 1v4H7v-3H4v-2zm12 0h4v2h-3v3h-2v-4a1 1 0 0 1 1-1z',
    close: 'M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z',
    lyrics: 'M4 6h12v2H4V6zm0 4h12v2H4v-2zm0 4h8v2H4v-2zm14-1.5V5h4v2h-2v7.5a2.5 2.5 0 1 1-2-2.45z',
    desktop: 'M5 3h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-6v2h3v2H8v-2h3v-2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm0 2v10h14V5H5zm2 3h10v2H7V8zm2 3h6v2H9v-2z',
    note: 'M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z',
  };
  function icon(name) {
    return '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill-rule="evenodd" d="' + ICONS[name] + '"/></svg>';
  }

  // ---------- colours from the album cover ----------
  // Same maths as CoverPalette.kt / CoverPalette.cs: a dark tint for the card, a light one for progress.
  function hsvToRgb(h, s, v) {
    var c = v * s, hp = (h % 360) / 60, x = c * (1 - Math.abs(hp % 2 - 1)), m = v - c, r = 0, g = 0, b = 0;
    if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; }
    else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
    return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
  }
  function paletteFrom(data) {
    var x = 0, y = 0, weight = 0, satSum = 0, n = data.length / 4;
    for (var i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      var r = data[i] / 255, g = data[i + 1] / 255, b = data[i + 2] / 255;
      var mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
      if (mx < 0.15) continue;
      var s = mx === 0 ? 0 : d / mx;
      var w = s * s * mx;
      if (w <= 0) continue;
      var h = d === 0 ? 0 : mx === r ? 60 * (((g - b) / d) % 6) : mx === g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
      if (h < 0) h += 360;
      x += Math.cos(h * Math.PI / 180) * w;
      y += Math.sin(h * Math.PI / 180) * w;
      weight += w;
      satSum += s * w;
    }
    if (weight < n * 0.02) return null;
    var hue = Math.atan2(y, x) * 180 / Math.PI;
    if (hue < 0) hue += 360;
    var sat = satSum / weight, agreement = Math.sqrt(x * x + y * y) / weight;
    if (agreement < 0.25) return null;
    return {
      card: hsvToRgb(hue, Math.min(0.55, sat * 0.8) * Math.min(1, agreement * 1.4), 0.2),
      highlight: hsvToRgb(hue, Math.min(0.6, Math.max(0.35, sat * 0.9)), 0.97),
    };
  }
  var paletteFor = '';
  function applyCover(url) {
    var img = $('fc-cover-img');
    var box = $('fc-cover');
    if (!url) {
      box.classList.remove('fc-has-cover');
      img.removeAttribute('src');
      setPalette(null);
      paletteFor = '';
      return;
    }
    if (img.getAttribute('src') !== url) img.setAttribute('src', url);
    box.classList.add('fc-has-cover');
    if (paletteFor === url) return;
    paletteFor = url;
    var probe = new Image();
    probe.onload = function () {
      if (paletteFor !== url) return;
      try {
        var canvas = document.createElement('canvas');
        canvas.width = 32;
        canvas.height = 32;
        var ctx = canvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(probe, 0, 0, 32, 32);
        setPalette(paletteFrom(ctx.getImageData(0, 0, 32, 32).data));
      } catch (e) { setPalette(null); }
    };
    probe.src = url;
  }
  function setPalette(p) {
    var card = p ? p.card : [28, 28, 33];
    var hl = p ? p.highlight : [232, 228, 216];
    root.style.setProperty('--card-rgb', card.join(','));
    root.style.setProperty('--hl', 'rgb(' + hl.join(',') + ')');
  }

  // ---------- state from the computer ----------
  var state = {
    connected: false,     // our socket to the helper program
    hasSession: false,    // a player is reporting to Windows
    app: '', title: '', artist: '', album: '', cover: '', playing: false,
    positionMs: 0, durationMs: 0, canSeek: false, lyricStatus: '',
    settings: { translation: true, traditional: true, offsetMs: 0 },
    lan: { enabled: false, urls: [] },
    receivedAt: 0,
  };
  var fullscreen = false;
  var desktopLyrics = false;

  function livePosition() {
    var pos = state.positionMs;
    if (state.playing) pos += performance.now() - state.receivedAt;
    if (state.durationMs > 0) pos = Math.min(pos, state.durationMs);
    return Math.max(0, pos);
  }

  // ---------- UI ----------
  var root, bar, seek, seeking = false, panelOpen = false, cardOpen = true, clickTimer = 0, idleTimer = 0;

  function build() {
    root = document.createElement('div');
    root.id = 'fc-root';
    root.innerHTML =
      '<div id="fc-fps" aria-hidden="true"></div>' +
      '<div id="fc-notice" role="status"></div>' +
      '<div id="fc-bar" role="region" aria-label="正在播放">' +
        '<button id="fc-cover" title="切換到播放器" aria-label="切換到播放器">' +
          '<img id="fc-cover-img" alt="">' + '<span class="fc-cover-ph">' + icon('note') + '</span>' +
        '</button>' +
        '<div class="fc-info">' +
          '<div id="fc-title"></div>' +
          '<div id="fc-sub"></div>' +
          '<div id="fc-lyric-status"></div>' +
        '</div>' +
        '<div class="fc-seek"><span id="fc-pos">0:00</span>' +
          '<input id="fc-seekbar" type="range" min="0" max="1" step="250" value="0" aria-label="播放進度">' +
          '<span id="fc-dur">0:00</span></div>' +
        '<div class="fc-transport">' +
          '<button class="fc-btn" id="fc-prev" title="上一首（P）" aria-label="上一首">' + icon('prev') + '</button>' +
          '<button class="fc-btn fc-big" id="fc-play" title="播放／暫停（空白鍵）" aria-label="播放">' + icon('play') + '</button>' +
          '<button class="fc-btn" id="fc-next" title="下一首（N）" aria-label="下一首">' + icon('next') + '</button>' +
        '</div>' +
        '<div class="fc-extras fc-extras-a">' +
          '<button class="fc-btn fc-small" id="fc-lyrics" title="換歌詞（L）" aria-label="換歌詞">' + icon('lyrics') + '</button>' +
          (webview ? '<button class="fc-btn fc-small" id="fc-desktop" title="桌面歌詞（D）" aria-label="桌面歌詞" aria-pressed="false">' + icon('desktop') + '</button>' : '') +
        '</div>' +
        '<div class="fc-extras fc-extras-b">' +
          '<button class="fc-btn fc-small" id="fc-settings" title="設定" aria-label="設定">' + icon('gear') + '</button>' +
          '<button class="fc-btn fc-small" id="fc-full" title="全螢幕（F11）" aria-label="全螢幕">' + icon('expand') + '</button>' +
        '</div>' +
      '</div>' +
      '<div id="fc-scrim"></div>' +
      '<div id="fc-panel" role="dialog" aria-label="設定"></div>' +
      '<div id="fc-picker" class="fc-sheet" role="dialog" aria-label="換歌詞"></div>' +
      '<div id="fc-toast" role="status"></div>';
    document.body.appendChild(root);
    if (isMobile) root.classList.add('fc-touch');
    if (qualityId() === 'smooth') document.documentElement.classList.add('fc-lowfx');
    applyUiScale();

    bar = $('fc-bar');
    seek = $('fc-seekbar');
    setPalette(null);
    layoutCard();
    window.addEventListener('resize', layoutCard);
    $('fc-play').onclick = function () {
      state.playing = !state.playing; // optimistic
      state.positionMs = livePosition();
      state.receivedAt = performance.now();
      render();
      post('/api/control', { action: 'toggle' });
    };
    $('fc-prev').onclick = function () { post('/api/control', { action: 'prev' }); };
    $('fc-next').onclick = function () { post('/api/control', { action: 'next' }); };
    $('fc-full').onclick = toggleFullscreen;
    $('fc-settings').onclick = function () { openPanel(!panelOpen); };
    $('fc-lyrics').onclick = function () { openPicker(!pickerOpen); };
    $('fc-lyric-status').onclick = function () { openPicker(true); };
    $('fc-scrim').onclick = function () { openPanel(false); openPicker(false); };
    $('fc-title').onclick = function () { hostMessage({ type: 'focusPlayer' }); };
    $('fc-cover').onclick = function () { hostMessage({ type: 'focusPlayer' }); };
    if ($('fc-desktop')) $('fc-desktop').onclick = function () { hostMessage({ type: 'desktopLyrics' }); };
    if (!webview) $('fc-cover').title = '';

    seek.addEventListener('input', function () {
      seeking = true;
      $('fc-pos').textContent = fmt(Number(seek.value));
      paintSeek();
    });
    seek.addEventListener('change', function () {
      var target = Number(seek.value);
      seeking = false;
      state.positionMs = target;
      state.receivedAt = performance.now();
      post('/api/control', { action: 'seek', positionMs: Math.round(target) });
    });

    // The card stays until a click / tap on the lyrics hides it, and the next one brings it back.
    // A double click is full screen, so a single click waits a moment to be sure it isn't one.
    document.addEventListener('pointermove', function (e) {
      if (e.pointerType === 'mouse') wake();
    }, { passive: true });
    function onOurUi(target) {
      return bar.contains(target) || $('fc-panel').contains(target) || $('fc-picker').contains(target) ||
        !!(target && target.closest && target.closest('.fct-root'));
    }
    document.addEventListener('pointerup', function (e) {
      if (e.button > 0 || panelOpen || pickerOpen || tuningOpen()) return;
      if (onOurUi(e.target)) return;
      clearTimeout(clickTimer);
      clickTimer = setTimeout(function () { setCard(!cardOpen); }, e.pointerType === 'mouse' ? 240 : 0);
    });
    document.addEventListener('dblclick', function (e) {
      if (onOurUi(e.target) || tuningOpen()) return;
      clearTimeout(clickTimer);
      toggleFullscreen();
    });
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('fullscreenchange', function () {
      if (!webview) { fullscreen = !!document.fullscreenElement; render(); }
    });
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') resync();
    });

    renderPanel();
    render();
    setCard(true);
    wake();
    setInterval(tick, 250);
    if (store.get('fps', false)) startFps();
  }

  function applyUiScale() {
    if (!root) return;
    root.style.setProperty('--u', uiScale + 'px');
    layoutCard();
  }

  function setCard(open) {
    cardOpen = open;
    root.classList.toggle('fc-show', open);
    if (!open && document.activeElement && root.contains(document.activeElement)) document.activeElement.blur();
    if (open) tick();
    updateInset();
  }

  // Folia pins its subtitle line (translation / romaji) to the bottom of the screen, under the card.
  // While the card is open, lift that line above it. Folia sets `bottom` as an inline style from its
  // animation library; a stylesheet rule with !important wins over it.
  var insetStyle = null;
  function updateInset() {
    if (!insetStyle) {
      insetStyle = document.createElement('style');
      insetStyle.textContent =
        'html.fc-inset div.absolute.left-0.right-0.text-center.z-20.pointer-events-none[style*="bottom"]' +
        '{bottom:var(--fc-inset)!important;transition:bottom .3s ease}';
      document.head.appendChild(insetStyle);
    }
    var html = document.documentElement;
    if (!cardOpen || !bar) { html.classList.remove('fc-inset'); return; }
    // The card's resting place (its transform animates in from a little lower).
    var cs = getComputedStyle(bar);
    var inset = parseFloat(cs.bottom) + bar.offsetHeight + 12 * uiScale;
    html.style.setProperty('--fc-inset', Math.round(inset) + 'px');
    html.classList.add('fc-inset');
  }
  // Only the mouse pointer hides itself when idle; the card stays.
  function wake() {
    document.documentElement.classList.remove('fc-idle');
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () {
      if (!panelOpen && !pickerOpen && !seeking) document.documentElement.classList.add('fc-idle');
    }, 3000);
  }
  // Phones, tablets in portrait and small windows get the stacked card.
  function layoutCard() {
    if (!root) return;
    root.classList.toggle('fc-narrow', window.innerWidth / uiScale < 760);
    updateInset();
  }

  function tuningOpen() { return !!(window.FcTuning && window.FcTuning.isOpen()); }

  function openTuning() {
    if (!window.FcTuning) return;
    openPanel(false);
    openPicker(false);
    window.FcTuning.open({ u: uiScale });
  }

  function onKey(e) {
    if (tuningOpen()) return;
    var tag = (e.target && e.target.tagName) || '';
    if (tag === 'SELECT' || tag === 'INPUT' && e.target.type !== 'range') return;
    var handled = true;
    switch (e.key) {
      case ' ':
      case 'MediaPlayPause':
      case 'k':
        $('fc-play').click();
        break;
      case 'ArrowLeft':
      case 'ArrowRight':
        if (!state.canSeek) { handled = false; break; }
        var target = livePosition() + (e.key === 'ArrowLeft' ? -5000 : 5000);
        if (state.durationMs > 0) target = Math.min(target, state.durationMs - 500);
        state.positionMs = Math.max(0, target);
        state.receivedAt = performance.now();
        post('/api/control', { action: 'seek', positionMs: Math.round(state.positionMs) });
        break;
      case 'n':
      case 'MediaTrackNext':
        post('/api/control', { action: 'next' });
        break;
      case 'p':
      case 'MediaTrackPrevious':
        post('/api/control', { action: 'prev' });
        break;
      case 'f':
      case 'F11':
        toggleFullscreen();
        break;
      case 'l':
        openPicker(!pickerOpen);
        break;
      case 'd':
        if (!webview) { handled = false; break; }
        hostMessage({ type: 'desktopLyrics' });
        break;
      case 'Escape':
        if (pickerOpen) openPicker(false);
        else if (panelOpen) openPanel(false);
        else if (fullscreen) toggleFullscreen();
        else handled = false;
        break;
      default:
        handled = false;
    }
    if (handled) {
      e.preventDefault();
      e.stopPropagation();
      wake();
    }
  }

  function toggleFullscreen() {
    if (webview) {
      hostMessage({ type: 'fullscreen' });
      return;
    }
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else if (document.documentElement.requestFullscreen) {
      document.documentElement.requestFullscreen().catch(function () {});
    }
  }

  function paintSeek() {
    var max = Number(seek.max) || 1;
    seek.style.setProperty('--pct', (100 * Number(seek.value) / max).toFixed(2) + '%');
  }

  function tick() {
    if (!root || !root.classList.contains('fc-show') || seeking) return;
    var pos = livePosition();
    $('fc-pos').textContent = fmt(pos);
    seek.value = String(Math.round(pos));
    paintSeek();
  }

  function render() {
    if (!root) return;
    var notice = $('fc-notice');
    if (!state.connected) {
      notice.textContent = '連不到這台電腦上的 Folia 控制器小程式，重新連線中…';
      notice.classList.add('fc-on');
    } else {
      notice.classList.remove('fc-on');
    }

    $('fc-title').textContent = state.hasSession && state.title ? state.title : '沒有正在播放的音樂';
    $('fc-title').title = state.hasSession && state.app ? '正在 ' + state.app + ' 播放' : '';
    var sub = [];
    if (state.hasSession && state.artist) sub.push(state.artist);
    if (state.hasSession && state.album) sub.push(state.album);
    if (!sub.length && state.hasSession && state.app) sub.push(state.app);
    $('fc-sub').textContent = sub.length ? sub.join(' · ') : '在 Spotify、YouTube Music 等播放器播一首歌';
    $('fc-lyric-status').textContent = state.lyricStatus || '';
    applyCover(state.hasSession ? state.cover : '');
    var desk = $('fc-desktop');
    if (desk) desk.setAttribute('aria-pressed', desktopLyrics ? 'true' : 'false');

    var play = $('fc-play');
    play.innerHTML = icon(state.playing ? 'pause' : 'play');
    play.setAttribute('aria-label', state.playing ? '暫停' : '播放');
    $('fc-full').innerHTML = icon(fullscreen ? 'shrink' : 'expand');
    $('fc-full').setAttribute('aria-label', fullscreen ? '離開全螢幕' : '全螢幕');

    seek.max = String(Math.max(1, state.durationMs));
    seek.disabled = !state.canSeek || state.durationMs <= 0;
    $('fc-dur').textContent = fmt(state.durationMs);
    if (!seeking) tick();
    if (panelOpen) syncPanel();
    updateInset();
  }

  // ---------- settings panel ----------
  function switchRow(id, label, note) {
    return '<div class="fc-row"><label for="' + id + '">' + label + (note ? '<small>' + note + '</small>' : '') +
      '</label><button class="fc-switch" id="' + id + '" role="switch" aria-checked="false"></button></div>';
  }
  function selectRow(id, label, options, note) {
    var html = '<div class="fc-row"><label for="' + id + '">' + label + (note ? '<small>' + note + '</small>' : '') +
      '</label><select id="' + id + '">';
    for (var i = 0; i < options.length; i++) html += '<option value="' + options[i][0] + '">' + options[i][1] + '</option>';
    return html + '</select></div>';
  }

  function renderPanel() {
    var offsetOptions = OFFSETS.map(function (ms) {
      return [String(ms), ms === 0 ? '0 ms（不調整）' : (ms > 0 ? '+' + ms + ' ms（歌詞晚一點）' : ms + ' ms（歌詞早一點）')];
    });
    var qualityOptions = QUALITIES.map(function (q) { return [q.id, q.label + '（' + q.detail + '）']; });
    var panel = $('fc-panel');
    panel.innerHTML =
      '<div class="fc-panel-head"><h2>設定</h2><button class="fc-btn fc-small" id="fc-close" aria-label="關閉">' + icon('close') + '</button></div>' +
      '<div class="fc-section">這台裝置的畫面</div>' +
      selectRow('fc-mode', '歌詞動畫', MODES) +
      '<div class="fc-row"><label>動畫與外觀細節<small id="fc-tuning-note">每種動畫的細部設定、字型、副歌詞區、背景</small></label>' +
        '<button class="fc-text-btn" id="fc-tuning-open">調整…</button></div>' +
      (qualityApplies ? selectRow('fc-quality', '畫質', qualityOptions, '越低越流暢，歌詞文字依然清晰') : '') +
      switchRow('fc-daylight', '日間主題') +
      switchRow('fc-fps-toggle', '顯示 FPS', '左上角顯示每秒畫出幾幀') +
      selectRow('fc-subtitle', '副歌詞', SUBTITLES, '歌詞下方那一行：翻譯或羅馬拼音') +
      '<div class="fc-section">音樂律動（所有裝置共用）</div>' +
      switchRow('fc-audio', '跟著音樂律動', '擷取這台電腦正在播放的聲音，讓有律動效果的動畫跟著節奏動；不錄音、不上傳') +
      '<div class="fc-section">歌詞（所有裝置共用）</div>' +
      switchRow('fc-traditional', '簡體歌詞轉繁體', '日文歌詞不會轉換') +
      selectRow('fc-offset', '歌詞時間偏移', offsetOptions, '戴藍牙耳機時歌詞偏早，可以調成 +250～+500') +
      '<div class="fc-section">其他裝置</div>' +
      '<div class="fc-note" id="fc-lan"></div>' +
      '<div class="fc-section">快捷鍵</div>' +
      '<div class="fc-keys">空白鍵 播放／暫停 · ← → 倒轉／快轉 5 秒 · N 下一首 · P 上一首 · L 換歌詞' + (webview ? ' · D 桌面歌詞' : '') + ' · F 或雙擊 全螢幕 · 點一下歌詞 收起／打開資訊卡 · Esc 離開</div>';

    $('fc-close').onclick = function () { openPanel(false); };
    $('fc-tuning-open').onclick = openTuning;
    $('fc-mode').onchange = function () { store.set('mode', this.value); reloadWithSettings(); };
    if (qualityApplies) {
      $('fc-quality').onchange = function () {
        var before = qualityId();
        store.set('quality', this.value);
        // The window applies resolution through zoom on the fly; phones need the viewport rebuilt,
        // and 流暢's static mode is a page parameter, so those reload.
        if (webview && before !== 'smooth' && this.value !== 'smooth') sendQuality();
        else reloadWithSettings();
      };
    }
    $('fc-daylight').onclick = function () { store.set('daylight', !store.get('daylight', false)); reloadWithSettings(); };
    $('fc-fps-toggle').onclick = function () {
      var on = !store.get('fps', false);
      store.set('fps', on);
      if (on) startFps(); else stopFps();
      syncPanel();
    };
    $('fc-subtitle').onchange = function () { store.set('subtitle', this.value); reloadWithSettings(); };
    $('fc-traditional').onclick = function () { post('/api/settings', { traditional: !state.settings.traditional }); };
    $('fc-audio').onclick = function () { post('/api/settings', { audioReactive: !state.settings.audioReactive }); };
    $('fc-offset').onchange = function () { post('/api/settings', { offsetMs: Number(this.value) }); };
  }

  function setSwitch(id, on) {
    var el = $(id);
    if (el) el.setAttribute('aria-checked', on ? 'true' : 'false');
  }

  function syncPanel() {
    $('fc-mode').value = store.get('mode', 'classic');
    var changed = window.FcTuning ? window.FcTuning.changedCount() : 0;
    $('fc-tuning-note').textContent = changed ? '已調整 ' + changed + ' 項（每種動畫的細部設定、字型、副歌詞區、背景）' : '每種動畫的細部設定、字型、副歌詞區、背景';
    if (qualityApplies) $('fc-quality').value = qualityId();
    setSwitch('fc-daylight', store.get('daylight', false));
    setSwitch('fc-fps-toggle', store.get('fps', false));
    $('fc-subtitle').value = store.get('subtitle', 'translation');
    setSwitch('fc-traditional', state.settings.traditional);
    setSwitch('fc-audio', state.settings.audioReactive !== false);
    $('fc-offset').value = String(state.settings.offsetMs || 0);
    var lan = $('fc-lan');
    if (state.lan.enabled && state.lan.urls.length) {
      lan.innerHTML = '同一個 Wi‑Fi 下的平板或手機，用瀏覽器打開：<br>' +
        state.lan.urls.map(function (u) { return '<code>' + u + '</code>'; }).join('<br>');
    } else if (state.lan.enabled) {
      lan.textContent = '已允許區網連線，但找不到這台電腦的區網位址。';
    } else {
      lan.textContent = '想讓平板也顯示歌詞：在系統匣的 Folia 圖示按右鍵，勾選「允許區網裝置連線」。';
    }
  }

  function openPanel(open) {
    if (open && pickerOpen) openPicker(false);
    panelOpen = open;
    root.classList.toggle('fc-panel-open', open);
    if (open) {
      syncPanel();
      wake();
    }
  }

  // ---------- lyric picker ----------
  var pickerOpen = false;
  var pickerRequest = 0;

  function escapeHtml(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function toast(text) {
    var el = $('fc-toast');
    el.textContent = text;
    el.classList.add('fc-on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { el.classList.remove('fc-on'); }, 3200);
  }

  function openPicker(open) {
    if (open && panelOpen) openPanel(false);
    pickerOpen = open;
    root.classList.toggle('fc-picker-open', open);
    if (open) {
      wake();
      loadCandidates();
    }
  }

  function renderPicker(data, loading) {
    var sheet = $('fc-picker');
    var html = '<div class="fc-panel-head"><h2>換歌詞</h2><button class="fc-btn fc-small" id="fc-picker-close" aria-label="關閉">' + icon('close') + '</button></div>';
    if (data && data.hasSession) {
      html += '<div class="fc-note">' + escapeHtml(data.title) + (data.artist ? '　·　' + escapeHtml(data.artist) : '') +
        (data.durationMs ? '　·　' + fmt(data.durationMs) : '') + '</div>';
    }
    if (loading) {
      html += '<div class="fc-empty">正在搜尋各個歌詞來源…</div>';
    } else if (!data || !data.hasSession) {
      html += '<div class="fc-empty">現在沒有正在播放的歌曲。</div>';
    } else if (!data.candidates.length) {
      html += '<div class="fc-empty">' + (data.timeout ? '搜尋逾時，請稍後再試。' : '每個來源都找不到這首歌。') + '</div>';
    } else {
      html += '<div class="fc-list">';
      data.candidates.forEach(function (c, index) {
        var current = data.current && data.current.source === c.source && data.current.id === c.id;
        var diff = data.durationMs && c.durationMs ? (c.durationMs - data.durationMs) / 1000 : null;
        var diffText = diff == null ? '' : (Math.abs(diff) < 0.5 ? '長度相同' : (diff > 0 ? '+' : '') + diff.toFixed(1) + ' 秒');
        html += '<button class="fc-item' + (current ? ' fc-current' : '') + '" data-index="' + index + '">' +
          '<span class="fc-badge fc-src-' + escapeHtml(c.source) + '">' + escapeHtml(c.sourceName) + '</span>' +
          '<span class="fc-item-main"><span class="fc-item-title">' + escapeHtml(c.title) + '</span>' +
          '<span class="fc-item-sub">' + escapeHtml(c.artist) + (c.durationMs ? '　' + fmt(c.durationMs) : '') +
          (diffText ? '（' + diffText + '）' : '') + '</span></span>' +
          (current ? '<span class="fc-tag">使用中</span>' : c.accepted ? '<span class="fc-tag fc-tag-dim">相符</span>' : '') +
          '</button>';
      });
      html += '</div>';
    }
    if (data && data.hasSession) {
      html += '<div class="fc-actions">' +
        '<button class="fc-text-btn" id="fc-pick-none"' + (data.noLyrics ? ' disabled' : '') + '>這首歌不顯示歌詞</button>' +
        (data.remembered ? '<button class="fc-text-btn" id="fc-pick-reset">恢復自動選擇</button>' : '') +
        '</div><div class="fc-keys">選過的歌詞會記住，下次播這首歌時自動使用。</div>';
    }
    sheet.innerHTML = html;
    $('fc-picker-close').onclick = function () { openPicker(false); };
    sheet.querySelectorAll('.fc-item').forEach(function (button) {
      button.onclick = function () {
        var c = data.candidates[Number(button.getAttribute('data-index'))];
        button.classList.add('fc-busy');
        post('/api/lyrics/choose', c).then(function (r) { return r && r.json(); }).then(function (result) {
          if (result && result.ok) {
            toast('已換成「' + c.sourceName + '」的歌詞，下次也會使用');
            openPicker(false);
          } else {
            button.classList.remove('fc-busy');
            toast('這個來源抓不到歌詞，換一個試試');
          }
        });
      };
    });
    var none = $('fc-pick-none');
    if (none) none.onclick = function () {
      post('/api/lyrics/none').then(function () { toast('這首歌之後不顯示歌詞'); openPicker(false); });
    };
    var reset = $('fc-pick-reset');
    if (reset) reset.onclick = function () {
      post('/api/lyrics/reset').then(function () { toast('已恢復自動選擇，正在重新搜尋'); openPicker(false); });
    };
  }

  function loadCandidates() {
    var request = ++pickerRequest;
    renderPicker(null, true);
    fetch('/api/lyrics/candidates', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (data) { if (request === pickerRequest && pickerOpen) renderPicker(data, false); })
      .catch(function () { if (request === pickerRequest && pickerOpen) renderPicker({ hasSession: true, candidates: [], timeout: true }, false); });
  }

  // ---------- FPS readout ----------
  var fpsRunning = false;
  function startFps() {
    if (fpsRunning) return;
    fpsRunning = true;
    var box = $('fc-fps');
    box.classList.add('fc-on');
    box.textContent = '— FPS';
    var frames = 0, worst = 0, last = 0, windowStart = 0;
    function frame(now) {
      if (!fpsRunning) return;
      if (last) worst = Math.max(worst, now - last); else windowStart = now;
      last = now;
      frames++;
      var elapsed = now - windowStart;
      if (elapsed >= 1000) {
        var fps = Math.round((frames - 1) * 1000 / elapsed);
        box.textContent = fps + ' FPS  最慢 ' + Math.round(worst) + 'ms';
        box.style.color = worst <= 25 ? '#9eff9e' : worst <= 50 ? '#ffe08a' : '#ff8a8a';
        frames = 1;
        worst = 0;
        windowStart = now;
      }
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }
  function stopFps() {
    fpsRunning = false;
    $('fc-fps').classList.remove('fc-on');
  }

  // ---------- connection to the helper program ----------
  var socket = null;
  var retry = 0;
  function connect() {
    var url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/ws/lyric';
    try { socket = new WebSocket(url); } catch (e) { return scheduleReconnect(); }
    socket.onopen = function () { retry = 0; state.connected = true; render(); };
    socket.onmessage = function (event) {
      var msg;
      try { msg = JSON.parse(event.data); } catch (e) { return; }
      if (!msg || msg.event !== 'ControllerState' || !msg.data) return;
      var d = msg.data;
      state.hasSession = !!d.hasSession;
      state.app = d.app || '';
      state.title = d.title || '';
      state.artist = d.artist || '';
      state.album = d.album || '';
      state.cover = d.cover || '';
      state.playing = !!d.playing;
      state.positionMs = Number(d.positionMs) || 0;
      state.durationMs = Number(d.durationMs) || 0;
      state.canSeek = !!d.canSeek;
      state.lyricStatus = d.lyricStatus || '';
      if (d.settings) state.settings = d.settings;
      if (d.lan) state.lan = d.lan;
      state.receivedAt = performance.now();
      render();
    };
    socket.onclose = function () {
      socket = null;
      state.connected = false;
      render();
      scheduleReconnect();
    };
    socket.onerror = function () { /* onclose follows */ };
  }
  function scheduleReconnect() {
    retry = Math.min(retry + 1, 6);
    setTimeout(connect, 500 * retry);
  }
  // After the page was frozen (window hidden to the tray) ask for a full state refresh.
  function resync() {
    if (socket && socket.readyState === 1) socket.send('{"type":"resync"}');
  }

  // ---------- desktop window ----------
  function sendQuality() {
    hostMessage({ type: 'quality', renderScale: qualityScale(qualityId()) });
  }
  if (webview) {
    webview.addEventListener('message', function (event) {
      var m = event.data || {};
      if (m.type === 'host') {
        if (typeof m.zoom === 'number' && m.zoom > 0) uiScale = 1 / m.zoom;
        if (typeof m.fullscreen === 'boolean') fullscreen = m.fullscreen;
        if (typeof m.desktopLyrics === 'boolean') desktopLyrics = m.desktopLyrics;
        applyUiScale();
        render();
      } else if (m.type === 'resync') {
        resync();
      }
    });
    sendQuality();
  }

  function start() {
    build();
    connect();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
