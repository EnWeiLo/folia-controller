// flowrics-engine.js — Flowrics' own lyric animation engine (fx.html), shared by the Android and
// Windows apps. It takes the same feed Folia's Now Playing page does (Track / Lyric / PlayerProgress /
// PlayerPauseState over /api/ws/lyric, audio through window.__foliaAudio) and gives each effect
// (flowrics-fx.js) a canvas, the song, the lyric clock, the cover's colours and the music's energy.
//
// An effect: Flowrics.register(id, { label, desc, wild, subtitle: 'self'|'engine', background: 'self'|'engine',
//   init(S), draw(ctx, S, U) })   — S: state of this frame, U: helpers.
(function () {
  'use strict';

  var effects = {};
  var order = [];
  var Flowrics = window.Flowrics = {
    effects: effects,
    order: order,
    register: function (id, def) {
      effects[id] = def;
      order.push(id);
    },
  };

  // ---------- page parameters ----------
  var params = new URL(location.href).searchParams;
  var cfg = {};
  try { cfg = JSON.parse(params.get('cfg') || '{}'); } catch (e) { cfg = {}; }
  var subtitleMode = cfg.subtitleContentMode || 'translation';
  var host = params.get('host') || location.host;
  var isAndroid = !!window.__FOLIA_ANDROID_CONTROLLER__;
  var isDesktopApp = !!(window.chrome && window.chrome.webview);
  var isMobile = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);

  // ---------- canvas ----------
  var canvas = document.createElement('canvas');
  canvas.id = 'flowrics';
  canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;display:block';
  var ctx = canvas.getContext('2d', { alpha: false });
  // The apps already make 1 CSS px = 1 screen px × quality, so don't multiply again there.
  function pixelRatio() {
    if (isAndroid || isDesktopApp || isMobile) return 1;
    return Math.min(window.devicePixelRatio || 1, 2);
  }

  // ---------- state given to effects ----------
  var S = {
    w: 0, h: 0, scale: 1,            // canvas size; scale = how big 1 "design px" is (short side / 1000)
    t: 0, dt: 16, now: 0,            // lyric clock (ms), frame time, performance.now()
    playing: false,
    title: '', artist: '', album: '', duration: 0,
    cover: null,                     // Image once loaded
    palette: null,                   // { bg, card, hl, hl2, text, hue } (CSS colour strings + numbers)
    lines: [],                       // [{ start, end, text, words:[{start,end,text}], trans, roma, synth, gap }]
    hasLyric: false,
    idx: -1, line: null, next: null, prev: null,
    insetBottom: 0,                  // px kept free for the app's info card
    audio: { power: 0, bass: 0, lowMid: 0, mid: 0, vocal: 0, treble: 0, spectrum: new Uint8Array(0), pulse: 0, live: false },
    subtitleMode: subtitleMode,
    fx: null, fxId: '',
    store: {},                       // the effect's own scratch space (reset on song / effect change)
  };

  // ---------- helpers for effects ----------
  var measureCache = {};
  var U = {
    clamp: function (v, a, b) { return v < a ? a : v > b ? b : v; },
    lerp: function (a, b, t) { return a + (b - a) * t; },
    mix: function (t, a, b) { return a + (b - a) * t; },
    smooth: function (t) { t = U.clamp(t, 0, 1); return t * t * (3 - 2 * t); },
    outCubic: function (t) { t = U.clamp(t, 0, 1); return 1 - Math.pow(1 - t, 3); },
    inCubic: function (t) { t = U.clamp(t, 0, 1); return t * t * t; },
    outBack: function (t, s) { t = U.clamp(t, 0, 1); s = s == null ? 1.7 : s; return 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2); },
    outElastic: function (t) {
      t = U.clamp(t, 0, 1);
      if (t === 0 || t === 1) return t;
      return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI) / 3) + 1;
    },
    // Deterministic randomness: the same line always gets the same layout, even after seeking.
    rand: function (seed) {
      var s = (seed | 0) * 2654435761 >>> 0;
      return function () {
        s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0;
        return (s % 100000) / 100000;
      };
    },
    hash: function (str) {
      var h = 2166136261;
      for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
      return h >>> 0;
    },
    font: function (size, weight, family) {
      var fam = family === 'serif' ? U.SERIF : family === 'mono' ? U.MONO : family === 'round' ? U.ROUND : U.SANS;
      return (weight || 700) + ' ' + Math.max(1, Math.round(size)) + 'px ' + fam;
    },
    SANS: '"PingFang TC","Noto Sans TC","Noto Sans CJK TC","Microsoft JhengHei UI","Microsoft JhengHei","Hiragino Sans","Yu Gothic UI",system-ui,sans-serif',
    SERIF: '"Noto Serif TC","Noto Serif CJK TC","Songti TC","PMingLiU","Yu Mincho","Hiragino Mincho ProN",serif',
    MONO: '"Cascadia Mono","Consolas","SF Mono","Roboto Mono","Noto Sans Mono CJK TC",monospace',
    ROUND: '"Arial Rounded MT Bold","Noto Sans TC","Microsoft JhengHei UI",system-ui,sans-serif',
    measure: function (text, font) {
      var key = font + '|' + text;
      var v = measureCache[key];
      if (v == null) {
        ctx.font = font;
        v = ctx.measureText(text).width;
        if (Object.keys(measureCache).length > 4000) measureCache = {};
        measureCache[key] = v;
      }
      return v;
    },
    progress: function (item, t) { return U.clamp((t - item.start) / Math.max(1, item.end - item.start), 0, 1); },
    hasCJK: function (text) { return /[぀-ヿ㐀-鿿가-힯]/.test(text); },
    // Characters with their own timing (a word's time spread over its characters).
    chars: function (line) {
      if (line._chars) return line._chars;
      var out = [];
      line.words.forEach(function (w, wi) {
        var cs = Array.from(w.text);
        var n = cs.length || 1;
        cs.forEach(function (c, i) {
          out.push({ ch: c, start: w.start + (w.end - w.start) * i / n, end: w.start + (w.end - w.start) * (i + 1) / n, word: wi });
        });
      });
      line._chars = out;
      return out;
    },
    /**
     * Lays a line out in rows no wider than maxW. Returns { rows:[{ y, width, items:[{ ...token, x, w }] }], height }.
     * Tokens are the line's words (Latin) or characters (CJK) so wrapping never cuts a Latin word.
     */
    layout: function (line, font, maxW, lineHeight) {
      var key = font + '|' + maxW + '|' + lineHeight;
      if (line._layout && line._layoutKey === key) return line._layout;
      var tokens = [];
      line.words.forEach(function (w, wi) {
        if (U.hasCJK(w.text)) {
          var cs = Array.from(w.text);
          cs.forEach(function (c, i) {
            tokens.push({ text: c, start: w.start + (w.end - w.start) * i / cs.length, end: w.start + (w.end - w.start) * (i + 1) / cs.length, word: wi });
          });
        } else {
          tokens.push({ text: w.text, start: w.start, end: w.end, word: wi });
        }
      });
      var rows = [];
      var row = { items: [], width: 0 };
      tokens.forEach(function (tk) {
        var tw = U.measure(tk.text, font);
        var isSpace = /^\s+$/.test(tk.text);
        if (row.items.length && row.width + tw > maxW && !isSpace) {
          rows.push(row);
          row = { items: [], width: 0 };
        }
        if (isSpace && !row.items.length) return;
        row.items.push({ text: tk.text, start: tk.start, end: tk.end, word: tk.word, x: row.width, w: tw });
        row.width += tw;
      });
      if (row.items.length) rows.push(row);
      rows.forEach(function (r, i) {
        // trailing spaces don't count for centring
        var last = r.items[r.items.length - 1];
        if (last && /\s+$/.test(last.text)) r.width -= U.measure(last.text, font) - U.measure(last.text.replace(/\s+$/, ''), font);
        r.y = i * lineHeight;
      });
      var out = { rows: rows, height: rows.length * lineHeight };
      line._layout = out;
      line._layoutKey = key;
      return out;
    },
    /** The translation / romaji line for this lyric line, per the 副歌詞 setting. */
    subtitleText: function (line) {
      if (!line) return '';
      var m = S.subtitleMode;
      if (m === 'none') return '';
      if (m === 'romanization') return line.roma || '';
      if (m === 'both') return [line.roma, line.trans].filter(Boolean).join('　');
      return line.trans || '';
    },
    rgba: function (rgb, a) { return 'rgba(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ',' + a + ')'; },
    hsl: function (h, s, l, a) { return 'hsla(' + ((h % 360) + 360) % 360 + ',' + s + '%,' + l + '%,' + (a == null ? 1 : a) + ')'; },
    /** Points inside a character's shape (for particles), relative to its left edge / baseline. */
    glyphPoints: function (ch, font, step) {
      var key = 'g|' + ch + '|' + font + '|' + step;
      if (glyphCache[key]) return glyphCache[key];
      var size = parseInt(/(\d+)px/.exec(font)[1], 10);
      var c = document.createElement('canvas');
      var w = Math.ceil(U.measure(ch, font) + size * 0.4), h = Math.ceil(size * 1.4);
      c.width = w; c.height = h;
      var g = c.getContext('2d');
      g.font = font; g.textBaseline = 'alphabetic'; g.fillStyle = '#fff';
      g.fillText(ch, 0, size * 1.05);
      var data = g.getImageData(0, 0, w, h).data;
      var pts = [];
      for (var y = 0; y < h; y += step) {
        for (var x = 0; x < w; x += step) {
          if (data[(y * w + x) * 4 + 3] > 128) pts.push([x, y - size * 1.05]);
        }
      }
      glyphCache[key] = pts;
      return pts;
    },
    /** Free space for the effect: everything above the app's info card. */
    area: function () { return { x: 0, y: 0, w: S.w, h: Math.max(S.h * 0.5, S.h - S.insetBottom) }; },
  };
  var glyphCache = {};

  /** Font size (px) that fits the line into maxRows rows of maxW; remembered per line. */
  U.fit = function (line, tag, base, weight, family, maxW, maxRows, lineHeight) {
    var key = '_fit_' + tag + '_' + Math.round(base) + '_' + Math.round(maxW);
    if (line[key]) return line[key];
    var size = base;
    for (var k = 0; k < 10; k++) {
      var rows = layoutRows(line, U.font(size, weight, family), maxW);
      if (rows <= maxRows) break;
      size *= 0.88;
    }
    line[key] = size;
    return size;
  };
  function layoutRows(line, font, maxW) {
    // a throwaway layout (doesn't disturb the cached one)
    var saved = line._layout, savedKey = line._layoutKey;
    var n = U.layout(line, font, maxW, 1).rows.length;
    line._layout = saved; line._layoutKey = savedKey;
    return n;
  }
  /**
   * The line's tokens placed on screen: [{ text, start, end, word, x (left), y (middle), w, row, i }].
   * align: 'center' (cx is the middle) | 'left' (cx is the left edge) | 'right' (cx is the right edge).
   */
  U.place = function (line, font, maxW, lineHeight, cx, cy, align) {
    var L = U.layout(line, font, maxW, lineHeight);
    var top = cy - L.height / 2, out = [];
    L.rows.forEach(function (r, ri) {
      var x0 = align === 'left' ? cx : align === 'right' ? cx - r.width : cx - r.width / 2;
      r.items.forEach(function (it) {
        out.push({ text: it.text, start: it.start, end: it.end, word: it.word, x: x0 + it.x, y: top + r.y + lineHeight / 2, w: it.w, row: ri, i: out.length });
      });
    });
    return { tokens: out, height: L.height, rows: L.rows, top: top };
  };
  /** When a line stops being shown: the next line's start, or a while after its last word (long breaks). */
  U.lineEnd = function (line) { return Math.min(line.end, line.sungEnd + 5000); };
  /**
   * Lines on screen now: the current one and the one before while it leaves.
   * [{ line, age (ms since start), exit 0…1, leaving }]
   */
  U.active = function (exitMs) {
    var out = [], t = S.t;
    [S.prev, S.line].forEach(function (l) {
      if (!l || l.gap || !l.text) return;
      var x = (t - U.lineEnd(l)) / exitMs;
      if (t >= l.start && x < 1) out.push({ line: l, age: t - l.start, exit: U.clamp(x, 0, 1), leaving: x > 0 });
    });
    return out;
  };
  /** 0…1: how much the song's title card should show (no lyrics, before the first line, long breaks). */
  U.idle = function (list) {
    var want = list && list.length ? 0 : 1;
    var st = S.store;
    if (st._idle == null) st._idle = want;
    st._idle += (want - st._idle) * (1 - Math.pow(0.02, S.dt / 1000));
    return st._idle;
  };
  /** A palette colour as css, lightness/alpha adjusted. */
  U.hue = function (shift, sat, light, alpha) { return U.hsl(S.palette.hue + (shift || 0), sat, light, alpha); };
  U.offscreen = function (name, w, h) {
    var st = S.store, c = st['_c_' + name];
    if (!c) { c = st['_c_' + name] = document.createElement('canvas'); }
    w = Math.max(1, Math.ceil(w)); h = Math.max(1, Math.ceil(h));
    if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
    return c;
  };
  Flowrics.U = U;

  // ---------- lyrics ----------
  function b64utf8(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder('utf-8').decode(bytes);
  }
  function stampMs(m) { return (+m[1]) * 60000 + (+m[2]) * 1000 + (m[3] ? +((m[3] + '00').slice(0, 3)) : 0); }
  var STAMP = /^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/;

  function parseTrack(text) {
    var out = [];
    (text || '').replace(/\r/g, '').split('\n').forEach(function (raw) {
      var rest = raw.trim(), times = [], m;
      while ((m = STAMP.exec(rest))) { times.push(stampMs(m)); rest = rest.slice(m[0].length); }
      if (!times.length) return;
      times.forEach(function (t) { out.push({ start: t, text: rest.trim() }); });
    });
    out.sort(function (a, b) { return a.start - b.start; });
    return out;
  }

  function parseWords(text) {
    // [mm:ss.mmm]<offset,dur>word<offset,dur>word…
    var map = {};
    (text || '').replace(/\r/g, '').split('\n').forEach(function (raw) {
      var m = STAMP.exec(raw.trim());
      if (!m) return;
      var start = stampMs(m), rest = raw.trim().slice(m[0].length), words = [];
      var re = /<(\d+),(\d+)>([^<]*)/g, w;
      while ((w = re.exec(rest))) words.push({ start: start + (+w[1]), end: start + (+w[1]) + Math.max(1, +w[2]), text: w[3] });
      map[start] = words;
    });
    return map;
  }

  /** Spreads a line-timed line's time over its characters (Latin: words). */
  function synthWords(text, start, end) {
    var units = [];
    if (U.hasCJK(text)) units = Array.from(text);
    else units = text.split(/(\s+)/).filter(function (x) { return x.length; });
    var spoken = units.filter(function (u) { return !/^\s+$/.test(u); }).length || 1;
    var dur = Math.max(600, Math.min(end - start - 150, spoken * 320 + 400));
    var per = dur / spoken, t = start, out = [];
    units.forEach(function (u) {
      if (/^\s+$/.test(u)) { if (out.length) out[out.length - 1].text += u; return; }
      out.push({ start: t, end: t + per, text: u });
      t += per;
    });
    return out;
  }

  function parseLyric(lrc) {
    var main = [], trans = [], roma = [], words = null;
    var m = /^\[awlrc:([\s\S]*)\]\s*$/.exec((lrc || '').trim());
    if (m) {
      m[1].split(',').forEach(function (part) {
        var i = part.indexOf(':');
        if (i < 0) return;
        var key = part.slice(0, i), val;
        try { val = b64utf8(part.slice(i + 1)); } catch (e) { return; }
        if (key === 'lrc') main = parseTrack(val);
        else if (key === 'tlrc') trans = parseTrack(val);
        else if (key === 'rlrc') roma = parseTrack(val);
        else if (key === 'awlrc') words = parseWords(val);
      });
    } else {
      // A local .lrc as is: two lines with one time stamp = lyric + translation.
      var all = parseTrack(lrc);
      for (var i = 0; i < all.length; i++) {
        if (i > 0 && all[i].start === all[i - 1].start && main.length && main[main.length - 1].start === all[i].start) {
          trans.push(all[i]);
        } else {
          main.push(all[i]);
        }
      }
    }
    var lines = main.map(function (ln, i) {
      var next = main[i + 1];
      var end = next ? next.start : ln.start + 6000;
      var ws = words && words[ln.start];
      var synth = !(ws && ws.length);
      if (synth) ws = ln.text ? synthWords(ln.text, ln.start, end) : [];
      var last = ws.length ? ws[ws.length - 1].end : ln.start;
      return {
        index: i,
        start: ln.start,
        end: Math.max(end, last),
        sungEnd: last,
        text: ln.text,
        words: ws,
        synth: synth,
        gap: !ln.text.trim(),
      };
    });
    function attach(track, key) {
      track.forEach(function (tr) {
        var best = null, diff = 1e9;
        lines.forEach(function (l) { var d = Math.abs(l.start - tr.start); if (d < diff && !l[key]) { diff = d; best = l; } });
        if (best && diff <= 1000) best[key] = tr.text;
      });
    }
    attach(trans, 'trans');
    attach(roma, 'roma');
    return lines;
  }

  // ---------- colours from the cover ----------
  function hsvToRgb(h, s, v) {
    var c = v * s, hp = (h % 360) / 60, x = c * (1 - Math.abs(hp % 2 - 1)), m = v - c, r = 0, g = 0, b = 0;
    if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; }
    else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
    return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
  }
  function paletteFrom(img) {
    var hue = 230, sat = 0.4, agreement = 0;
    try {
      var c = document.createElement('canvas');
      c.width = 32; c.height = 32;
      var g = c.getContext('2d', { willReadFrequently: true });
      g.drawImage(img, 0, 0, 32, 32);
      var d = g.getImageData(0, 0, 32, 32).data, x = 0, y = 0, wsum = 0, ssum = 0;
      for (var i = 0; i < d.length; i += 4) {
        var r = d[i] / 255, gg = d[i + 1] / 255, b = d[i + 2] / 255;
        var mx = Math.max(r, gg, b), mn = Math.min(r, gg, b), dd = mx - mn;
        if (mx < 0.15) continue;
        var s = mx ? dd / mx : 0, w = s * s * mx;
        if (w <= 0) continue;
        var h = dd === 0 ? 0 : mx === r ? 60 * (((gg - b) / dd) % 6) : mx === gg ? 60 * ((b - r) / dd + 2) : 60 * ((r - gg) / dd + 4);
        if (h < 0) h += 360;
        x += Math.cos(h * Math.PI / 180) * w; y += Math.sin(h * Math.PI / 180) * w; wsum += w; ssum += s * w;
      }
      if (wsum > 1024 * 0.02) {
        hue = Math.atan2(y, x) * 180 / Math.PI; if (hue < 0) hue += 360;
        sat = ssum / wsum; agreement = Math.sqrt(x * x + y * y) / wsum;
      }
    } catch (e) { /* cross-origin or broken image */ }
    return makePalette(hue, agreement < 0.25 ? 0.15 : sat);
  }
  function makePalette(hue, sat) {
    var hl = hsvToRgb(hue, U.clamp(sat * 0.9, 0.35, 0.65), 0.97);
    var hl2 = hsvToRgb(hue + 40, U.clamp(sat, 0.4, 0.75), 0.95);
    var bg = hsvToRgb(hue, Math.min(0.55, sat * 0.8), 0.14);
    var card = hsvToRgb(hue, Math.min(0.5, sat * 0.7), 0.22);
    return { hue: hue, sat: sat, hl: hl, hl2: hl2, bg: bg, card: card,
      hlCss: U.rgba(hl, 1), hl2Css: U.rgba(hl2, 1), bgCss: U.rgba(bg, 1) };
  }
  S.palette = makePalette(230, 0.35);

  // ---------- background: blurred cover ----------
  var bgCanvas = document.createElement('canvas');
  var bgReady = false;
  function buildBackground() {
    var w = Math.max(64, Math.round(S.w / 4)), h = Math.max(64, Math.round(S.h / 4));
    bgCanvas.width = w; bgCanvas.height = h;
    var g = bgCanvas.getContext('2d');
    var p = S.palette;
    g.fillStyle = p.bgCss;
    g.fillRect(0, 0, w, h);
    if (S.cover) {
      var img = S.cover, sc = Math.max(w / img.width, h / img.height) * 1.25;
      g.save();
      g.filter = 'blur(' + Math.round(Math.max(w, h) / 18) + 'px) saturate(1.3)';
      g.globalAlpha = 0.85;
      g.drawImage(img, (w - img.width * sc) / 2, (h - img.height * sc) / 2, img.width * sc, img.height * sc);
      g.restore();
    }
    var grad = g.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.2, w / 2, h / 2, Math.max(w, h) * 0.75);
    grad.addColorStop(0, 'rgba(0,0,0,0.35)');
    grad.addColorStop(1, 'rgba(0,0,0,0.75)');
    g.fillStyle = grad;
    g.fillRect(0, 0, w, h);
    bgReady = true;
  }
  /** The default background: the blurred cover, darkened, drifting slowly. Effects can call it too. */
  U.drawBackground = function (g, opts) {
    opts = opts || {};
    if (!bgReady) buildBackground();
    var drift = Math.sin(S.now / 9000) * 0.03, sc = 1.08 + drift + (opts.pulse || 0);
    g.save();
    g.globalAlpha = 1;
    g.fillStyle = '#000';
    g.fillRect(0, 0, S.w, S.h);
    g.globalAlpha = opts.alpha == null ? 1 : opts.alpha;
    var w = S.w * sc, h = S.h * sc;
    g.drawImage(bgCanvas, (S.w - w) / 2 + Math.cos(S.now / 11000) * S.w * 0.02, (S.h - h) / 2, w, h);
    g.restore();
  };

  // ---------- subtitles (translation / romaji) drawn by the engine ----------
  function drawEngineSubtitle() {
    var line = S.line;
    var text = U.subtitleText(line);
    if (!text || !line || line.gap) return;
    var a = U.area();
    var size = Math.round(28 * S.scale);
    ctx.save();
    ctx.font = U.font(size, 500);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    var age = S.t - line.start;
    ctx.globalAlpha = 0.85 * U.smooth(age / 300) * (1 - U.smooth((S.t - line.end + 200) / 300));
    ctx.fillStyle = '#fff';
    ctx.shadowColor = 'rgba(0,0,0,0.6)';
    ctx.shadowBlur = 8 * S.scale;
    ctx.fillText(text, S.w / 2, a.h - 48 * S.scale, S.w * 0.9);
    ctx.restore();
  }

  // ---------- idle: title card between / before lines ----------
  U.drawTitleCard = function (alpha) {
    if (alpha <= 0.01 || !S.title) return;
    var a = U.area();
    ctx.save();
    ctx.globalAlpha = alpha;
    ctx.textAlign = 'center';
    ctx.fillStyle = '#fff';
    ctx.font = U.font(64 * S.scale, 800);
    ctx.fillText(S.title, S.w / 2, a.h * 0.46, S.w * 0.86);
    ctx.globalAlpha = alpha * 0.7;
    ctx.font = U.font(30 * S.scale, 500);
    ctx.fillText(S.artist, S.w / 2, a.h * 0.46 + 56 * S.scale, S.w * 0.86);
    ctx.restore();
  };

  // ---------- clock ----------
  var clockBase = 0, clockAt = performance.now();
  function clockNow() {
    var t = clockBase + (S.playing ? performance.now() - clockAt : 0);
    if (S.duration > 0) t = Math.min(t, S.duration);
    return t;
  }

  // ---------- feed ----------
  function onMessage(json) {
    var msg;
    try { msg = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return; }
    if (!msg || !msg.event) return;
    var d = msg.data || {};
    switch (msg.event) {
      case 'Track':
        var key = (d.title || '') + '|' + (d.author || '');
        if (key !== S._trackKey) { S._trackKey = key; S.store = {}; }
        S.title = d.title || ''; S.artist = d.author || ''; S.album = d.album || ''; S.duration = d.duration || 0;
        if (d.cover && d.cover !== S._coverUrl) {
          S._coverUrl = d.cover;
          var img = new Image();
          img.onload = function () {
            if (S._coverUrl !== d.cover) return;
            S.cover = img; S.palette = paletteFrom(img); bgReady = false;
          };
          img.src = d.cover;
        } else if (!d.cover) {
          S.cover = null; S._coverUrl = ''; S.palette = makePalette(230, 0.35); bgReady = false;
        }
        break;
      case 'Lyric':
        S.lines = d.hasLyric && d.lrc ? parseLyric(d.lrc) : [];
        S.hasLyric = S.lines.length > 0;
        S.store = {};
        break;
      case 'PlayerProgress':
        clockBase = +d.progress || 0;
        clockAt = performance.now();
        break;
      case 'PlayerPauseState':
        if (d.isPaused) { clockBase = clockNow(); S.playing = false; }
        else { if (!S.playing) clockAt = performance.now(); S.playing = true; }
        break;
    }
  }

  function connect() {
    var socket, retry = 0;
    function open() {
      try { socket = new WebSocket((location.protocol === 'https:' && !isAndroid ? 'wss://' : 'ws://') + host + '/api/ws/lyric'); }
      catch (e) { setTimeout(open, 2000); return; }
      socket.onopen = function () { retry = 0; };
      socket.onmessage = function (e) { onMessage(e.data); };
      socket.onclose = function () { retry = Math.min(retry + 1, 6); setTimeout(open, 500 * retry); };
    }
    open();
  }

  // Audio from fc-audio.js (power and bands 0…255, spectrum 1024 bytes).
  var bassAvg = 0;
  window.__foliaAudio = function (f) {
    var a = S.audio;
    a.power = f.audioPower / 255;
    a.bass = f.bands.bass / 255; a.lowMid = f.bands.lowMid / 255; a.mid = f.bands.mid / 255;
    a.vocal = f.bands.vocal / 255; a.treble = f.bands.treble / 255;
    a.spectrum = f.spectrum;
    a.live = f.spectrum && f.spectrum.length > 0;
    bassAvg = bassAvg * 0.95 + a.bass * 0.05;
    if (a.live && a.bass > 0.2 && a.bass > bassAvg * 1.25) a.pulse = Math.max(a.pulse, U.clamp((a.bass - bassAvg) * 3, 0.3, 1));
  };

  // ---------- frame loop ----------
  var lastFrame = performance.now();
  function resize() {
    var r = pixelRatio();
    var w = Math.round(window.innerWidth * r), h = Math.round(window.innerHeight * r);
    if (w === canvas.width && h === canvas.height) return;
    canvas.width = w; canvas.height = h;
    // short side / 1000, a little more on tall phones so lyrics aren't tiny
    S.w = w; S.h = h; S.scale = Math.max(Math.min(w, h) / 1000, Math.max(w, h) / 1000 * 0.55);
    bgReady = false;
    measureCache = {};
    S.lines.forEach(function (l) { l._layout = null; });
    if (S.fx && S.fx.resize) S.fx.resize(S, U);
  }

  function insetBottom() {
    var html = document.documentElement;
    if (!html.classList.contains('fc-inset')) return 0;
    var v = parseFloat(getComputedStyle(html).getPropertyValue('--fc-inset')) || 0;
    return v * pixelRatio();
  }

  function frame(now) {
    requestAnimationFrame(frame);
    S.dt = Math.min(100, now - lastFrame);
    lastFrame = now;
    S.now = now;
    resize();
    S.t = clockNow();
    S.insetBottom = insetBottom();
    var a = S.audio;
    a.pulse *= Math.pow(0.0025, S.dt / 1000);
    if (!a.live) { a.power *= 0.9; a.bass *= 0.9; }

    // current line
    var lines = S.lines, idx = -1;
    for (var i = lines.length - 1; i >= 0; i--) { if (lines[i].start <= S.t) { idx = i; break; } }
    S.idx = idx;
    S.line = idx >= 0 ? lines[idx] : null;
    S.prev = idx > 0 ? lines[idx - 1] : null;
    S.next = lines[idx + 1] || null;

    var fx = S.fx;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.filter = 'none';
    if (fx.background !== 'self') U.drawBackground(ctx);
    try {
      fx.draw(ctx, S, U);
    } catch (e) {
      if (!S._errorShown) { S._errorShown = true; console.error('[flowrics] ' + S.fxId, e); }
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.filter = 'none';
    ctx.shadowBlur = 0;
    if (fx.subtitle !== 'self') drawEngineSubtitle();
  }

  Flowrics.start = function () {
    var id = params.get('fx') || 'dive';
    if (!effects[id]) id = order[0];
    S.fxId = id;
    S.fx = effects[id];
    document.body.appendChild(canvas);
    document.documentElement.style.background = '#000';
    document.body.style.cssText += ';margin:0;background:#000;overflow:hidden';
    resize();
    if (S.fx.init) S.fx.init(S, U);
    connect();
    requestAnimationFrame(frame);
  };
  Flowrics.S = S;
  Flowrics.feed = onMessage; // for tests
})();
