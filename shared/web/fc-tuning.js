// fc-tuning.js — Folia 控制器's "動畫與外觀" panel: every option Folia's own settings offer for the
// lyric animations, text, subtitle area and background, shared by the Android app and the Windows app.
//
// Folia's "Now Playing" page takes its look from the `cfg` URL parameter and has no settings UI of
// its own. This script runs before Folia (top of <head>): it merges the options saved here into
// `cfg`, so Folia starts with them. Changing an option saves it and reloads the page.
//
// Saved per device in localStorage "fc.tuning": { g: {cfgKey: value}, m: {mode: {key: value}},
// b: {background: {key: value}} } — only what differs from Folia's defaults.
// Needs fc-tuning-schema.js (generated from Folia's source) loaded first.
(function () {
  'use strict';
  var S = window.FC_FOLIA_SCHEMA;
  if (!S) return;

  var KEY = 'fc.tuning';

  function load() {
    try {
      var v = JSON.parse(localStorage.getItem(KEY) || '{}');
      return { g: v.g || {}, m: v.m || {}, b: v.b || {}, x: v.x || {} };
    } catch (e) {
      return { g: {}, m: {}, b: {}, x: {} };
    }
  }
  function save(o) {
    try { localStorage.setItem(KEY, JSON.stringify(o)); } catch (e) { /* private mode */ }
  }
  function clone(v) { return JSON.parse(JSON.stringify(v)); }
  function isEmpty(o) { for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) return false; return true; }

  // Dotted keys ("geometryVisibility.mode") address nested objects.
  function getPath(obj, key) {
    var parts = key.split('.');
    for (var i = 0; i < parts.length; i++) {
      if (obj == null) return undefined;
      obj = obj[parts[i]];
    }
    return obj;
  }
  function setPath(obj, key, value) {
    var parts = key.split('.');
    for (var i = 0; i < parts.length - 1; i++) {
      if (obj[parts[i]] == null || typeof obj[parts[i]] !== 'object') obj[parts[i]] = {};
      obj = obj[parts[i]];
    }
    obj[parts[parts.length - 1]] = value;
  }

  // Folia uses a mode's tuning object as is (no merging with defaults), so always send it whole.
  function fullTuning(defaults, overrides) {
    var t = clone(defaults || {});
    for (var k in overrides) setPath(t, k, overrides[k]);
    return t;
  }

  function cfgPatch(o) {
    var patch = {};
    for (var g in o.g) patch[g] = o.g[g];
    var vt = {};
    for (var mode in o.m) {
      if (S.modes[mode] && !isEmpty(o.m[mode])) vt[mode] = fullTuning(S.modes[mode].defaults, o.m[mode]);
    }
    if (!isEmpty(vt)) patch.visualizerTunings = vt;
    for (var bg in o.b) {
      var def = S.backgrounds[bg];
      if (def && def.cfgKey && !isEmpty(o.b[bg])) patch[def.cfgKey] = fullTuning(def.defaults, o.b[bg]);
    }
    return patch;
  }

  // ---------- before Folia starts: merge into the page address ----------
  // The app's own cfg is kept in "fcbase" so options reset here really go back to the app's values.
  (function mergeIntoUrl() {
    try {
      var url = new URL(location.href);
      if (url.searchParams.get('obs') !== '1') return;
      var base = url.searchParams.has('fcbase') ? url.searchParams.get('fcbase') : url.searchParams.get('cfg');
      var patch = cfgPatch(load());
      var cfg = {};
      if (base) {
        if (base.charAt(0) !== '{') return; // a Folia share code: leave it alone
        cfg = JSON.parse(base);
      }
      if (isEmpty(patch) && !url.searchParams.has('fcbase')) return;
      for (var k in patch) cfg[k] = patch[k];
      url.searchParams.delete('cfg');
      url.searchParams.delete('fcbase');
      if (base) url.searchParams.set('fcbase', base);
      url.searchParams.set('cfg', JSON.stringify(cfg)); // Folia wants cfg last
      history.replaceState(history.state, '', url.toString());
    } catch (e) {
      // A broken saved value must never stop Folia from starting.
    }
  })();

  // ---------- our own extras (not Folia options) ----------
  // Folia shows the next lyric lines in the bottom subtitle area when a line has no translation.
  var UPCOMING_CSS = 'html.fc-hide-upcoming div.absolute.left-0.right-0.text-center.z-20.pointer-events-none ' +
    'div:has(> div > p.truncate.max-w-2xl){display:none!important}';
  var EXTRAS = [
    { key: 'hideUpcoming', type: 'bool', label: '隱藏「下一句預告」',
      hint: '這一句沒有翻譯時，Folia 會在下方用小字預告接下來的歌詞；打開後只留翻譯或羅馬拼音。' },
  ];
  function applyExtras(x) {
    var html = document.documentElement;
    if (x.hideUpcoming && !document.getElementById('fc-upcoming-style')) {
      var style = document.createElement('style');
      style.id = 'fc-upcoming-style';
      style.textContent = UPCOMING_CSS;
      (document.head || html).appendChild(style);
    }
    html.classList.toggle('fc-hide-upcoming', !!x.hideUpcoming);
  }
  try { applyExtras(load().x); } catch (e) { /* ignore */ }

  // ---------- the panel ----------
  var TABS = [
    { id: 'mode', label: '動畫細節' },
    { id: 'text', label: '文字' },
    { id: 'subtitle', label: '副歌詞區' },
    { id: 'background', label: '背景' },
  ];
  var TEXT_KEYS = ['lyricsFontStyle', 'lyricsCustomFontFamily', 'lyricsFontScale', 'lyricsFontWeight', 'visualizerOpacity'];
  var BG_KEYS = ['visualizerBackgroundMode', 'disableVisualizerVignette', 'disableVisualizerGeometricBackground', 'useCoverColorBg', 'backgroundOpacity'];

  var root = null;
  var state = { tab: 'mode', mode: 'classic', o: null, dirty: false, opts: {} };

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function decimals(step) {
    var s = String(step || 1);
    return s.indexOf('.') < 0 ? 0 : s.length - s.indexOf('.') - 1;
  }
  function currentMode() {
    try {
      var m = new URL(location.href).searchParams.get('visualizer');
      if (m && S.modes[m]) return m;
    } catch (e) { /* ignore */ }
    return 'classic';
  }

  // Where a field's value lives: global, a mode's tuning, or a background's tuning.
  function scopeOf(kind, owner) {
    var o = state.o;
    if (kind === 'g') return { overrides: o.g, defaults: S.globalDefaults };
    if (kind === 'x') return { overrides: o.x, defaults: {}, live: true };
    if (kind === 'm') { o.m[owner] = o.m[owner] || {}; return { overrides: o.m[owner], defaults: S.modes[owner].defaults }; }
    o.b[owner] = o.b[owner] || {};
    return { overrides: o.b[owner], defaults: S.backgrounds[owner].defaults };
  }
  function valueOf(scope, key) {
    if (Object.prototype.hasOwnProperty.call(scope.overrides, key)) return scope.overrides[key];
    var d = getPath(scope.defaults, key);
    return d === undefined ? null : d;
  }
  function setValue(scope, key, value) {
    var d = getPath(scope.defaults, key);
    if (JSON.stringify(d === undefined ? null : d) === JSON.stringify(value)) delete scope.overrides[key];
    else scope.overrides[key] = value;
    cleanup();
    save(state.o);
    // Our own extras apply on the spot; Folia's options need the page reloaded.
    if (scope.live) applyExtras(state.o.x);
    else state.dirty = true;
    render();
  }
  function cleanup() {
    var o = state.o;
    for (var m in o.m) if (isEmpty(o.m[m])) delete o.m[m];
    for (var b in o.b) if (isEmpty(o.b[b])) delete o.b[b];
  }

  function control(field, scope) {
    var value = valueOf(scope, field.key);
    var wrap = el('div', 'fct-control');

    if (field.type === 'bool' && field.options) {
      var seg = el('div', 'fct-seg');
      field.options.forEach(function (opt) {
        var b = el('button', 'fct-seg-btn' + (opt.value === value ? ' fct-on' : ''), opt.label);
        b.type = 'button';
        b.onclick = function () { setValue(scope, field.key, opt.value); };
        seg.appendChild(b);
      });
      wrap.appendChild(seg);
    } else if (field.type === 'bool') {
      var sw = el('button', 'fct-switch');
      sw.type = 'button';
      sw.setAttribute('role', 'switch');
      sw.setAttribute('aria-checked', value ? 'true' : 'false');
      sw.setAttribute('aria-label', field.label);
      sw.onclick = function () { setValue(scope, field.key, !value); };
      wrap.appendChild(sw);
    } else if (field.type === 'enum') {
      var sel = el('select', 'fct-select');
      field.options.forEach(function (opt) {
        var o = el('option', null, opt.label);
        o.value = JSON.stringify(opt.value);
        if (JSON.stringify(opt.value) === JSON.stringify(value)) o.selected = true;
        sel.appendChild(o);
      });
      sel.onchange = function () { setValue(scope, field.key, JSON.parse(sel.value)); };
      wrap.appendChild(sel);
    } else if (field.type === 'number') {
      var auto = field.nullable && value == null;
      var min = field.min != null ? field.min : 0;
      var max = field.max != null ? field.max : 1;
      var step = field.step || (field.integer ? 1 : 0.01);
      var shown = auto ? (field.key.indexOf('Weight') >= 0 ? 400 : min) : value;
      var range = el('input', 'fct-range');
      range.type = 'range';
      range.min = String(min);
      range.max = String(max);
      range.step = String(step);
      range.value = String(shown);
      range.disabled = auto;
      var out = el('span', 'fct-num', auto ? '自動' : Number(shown).toFixed(decimals(step)) + (field.unit ? ' ' + field.unit : ''));
      var paint = function () {
        var pct = (Number(range.value) - min) / ((max - min) || 1) * 100;
        range.style.setProperty('--pct', pct.toFixed(1) + '%');
      };
      paint();
      range.oninput = function () {
        out.textContent = Number(range.value).toFixed(decimals(step)) + (field.unit ? ' ' + field.unit : '');
        paint();
      };
      range.onchange = function () {
        var v = Number(range.value);
        setValue(scope, field.key, field.integer ? Math.round(v) : Number(v.toFixed(decimals(step) + 2)));
      };
      var line = el('div', 'fct-range-line');
      line.appendChild(range);
      line.appendChild(out);
      wrap.appendChild(line);
      if (field.nullable) {
        var chip = el('button', 'fct-chip' + (auto ? ' fct-on' : ''), '自動');
        chip.type = 'button';
        chip.onclick = function () { setValue(scope, field.key, auto ? Number(range.value) : null); };
        wrap.appendChild(chip);
      }
    } else if (field.type === 'color') {
      var color = el('input', 'fct-color');
      color.type = 'color';
      color.value = /^#[0-9a-f]{6}$/i.test(value || '') ? value : '#ffffff';
      color.onchange = function () { setValue(scope, field.key, color.value); };
      wrap.appendChild(color);
    } else {
      var input = el('input', 'fct-text');
      input.type = 'text';
      input.value = value || '';
      input.placeholder = field.key.toLowerCase().indexOf('font') >= 0 ? '例如：Noto Serif TC（裝置上要有這個字型）' : '';
      input.onchange = function () { setValue(scope, field.key, input.value.trim() || null); };
      wrap.appendChild(input);
    }
    return wrap;
  }

  function visible(field, scope) {
    var w = field.showWhen;
    if (!w) return true;
    var v = valueOf(scope, w.key);
    return JSON.stringify(v) === JSON.stringify(w.equals);
  }

  function fieldList(body, fields, kind, owner) {
    var scope = scopeOf(kind, owner);
    var section = null;
    var shown = 0;
    fields.forEach(function (field) {
      if (!visible(field, scope)) return;
      if (field.section && field.section !== section) {
        section = field.section;
        body.appendChild(el('div', 'fct-section', section));
      }
      var row = el('div', 'fct-row fct-kind-' + field.type);
      var label = el('div', 'fct-label');
      var name = el('div', 'fct-name', field.label);
      if (Object.prototype.hasOwnProperty.call(scope.overrides, field.key)) name.appendChild(el('span', 'fct-changed', '已調整'));
      label.appendChild(name);
      if (field.hint) label.appendChild(el('div', 'fct-hint', field.hint));
      row.appendChild(label);
      row.appendChild(control(field, scope));
      body.appendChild(row);
      shown++;
    });
    if (!shown) body.appendChild(el('div', 'fct-empty', '這裡沒有可以調整的項目。'));
  }

  function globalFields(keys) {
    return S.global.filter(function (f) { return keys ? keys.indexOf(f.key) >= 0 : true; });
  }

  function renderBody(body) {
    var o = state.o;
    if (state.tab === 'mode') {
      var pick = el('div', 'fct-row fct-row-pick');
      pick.appendChild(el('div', 'fct-name', '要調整的動畫'));
      var sel = el('select', 'fct-select');
      Object.keys(S.modes).forEach(function (m) {
        var opt = el('option', null, S.modes[m].label + (m === currentMode() ? '（使用中）' : '') + (o.m[m] ? ' · 已調整' : ''));
        opt.value = m;
        if (m === state.mode) opt.selected = true;
        sel.appendChild(opt);
      });
      sel.onchange = function () { state.mode = sel.value; render(); };
      pick.appendChild(sel);
      body.appendChild(pick);
      fieldList(body, S.modes[state.mode].fields, 'm', state.mode);
    } else if (state.tab === 'text') {
      fieldList(body, globalFields(TEXT_KEYS), 'g');
      var note = el('div', 'fct-note', '「商籟」和「凝彩」會自動決定字級，不受「字級」影響；「繪光」的字級只影響副歌詞。');
      body.appendChild(note);
    } else if (state.tab === 'subtitle') {
      fieldList(body, EXTRAS, 'x');
      fieldList(body, S.global.filter(function (f) {
        return TEXT_KEYS.indexOf(f.key) < 0 && BG_KEYS.indexOf(f.key) < 0;
      }), 'g');
      body.appendChild(el('div', 'fct-note', '要顯示翻譯還是羅馬拼音，在設定裡的「副歌詞」選。'));
    } else {
      fieldList(body, globalFields(BG_KEYS), 'g');
      var bgMode = valueOf(scopeOf('g'), 'visualizerBackgroundMode') || 'latent';
      var bg = S.backgrounds[bgMode];
      if (bg && bg.fields.length) {
        body.appendChild(el('div', 'fct-section fct-section-strong', bg.label + '背景的細節'));
        fieldList(body, bg.fields, 'b', bgMode);
      }
    }
  }

  function resetTab() {
    var o = state.o;
    if (state.tab === 'mode') delete o.m[state.mode];
    else if (state.tab === 'background') {
      BG_KEYS.forEach(function (k) { delete o.g[k]; });
      o.b = {};
    } else {
      var keep = state.tab === 'text' ? TEXT_KEYS : null;
      if (!keep) { o.x = {}; applyExtras(o.x); }
      S.global.forEach(function (f) {
        var isText = TEXT_KEYS.indexOf(f.key) >= 0;
        var isBg = BG_KEYS.indexOf(f.key) >= 0;
        if (keep ? isText : (!isText && !isBg)) delete o.g[f.key];
      });
    }
    save(o);
    state.dirty = true;
    render();
  }

  function render() {
    if (!root) return;
    var sheet = root.querySelector('.fct-sheet');
    var scroll = root.querySelector('.fct-body');
    var top = scroll ? scroll.scrollTop : 0;
    sheet.innerHTML = '';

    var head = el('div', 'fct-head');
    head.appendChild(el('h2', null, '動畫與外觀'));
    var close = el('button', 'fct-icon', '✕');
    close.type = 'button';
    close.setAttribute('aria-label', '關閉');
    close.onclick = function () { api.close(); };
    head.appendChild(close);
    sheet.appendChild(head);

    var tabs = el('div', 'fct-tabs');
    TABS.forEach(function (t) {
      var b = el('button', 'fct-tab' + (t.id === state.tab ? ' fct-on' : ''), t.label);
      b.type = 'button';
      b.onclick = function () { state.tab = t.id; render(); var s = root.querySelector('.fct-body'); if (s) s.scrollTop = 0; };
      tabs.appendChild(b);
    });
    sheet.appendChild(tabs);

    var body = el('div', 'fct-body');
    renderBody(body);
    sheet.appendChild(body);

    var foot = el('div', 'fct-foot');
    var reset = el('button', 'fct-text-btn', state.tab === 'mode' ? '這個動畫恢復預設' : '這一頁恢復預設');
    reset.type = 'button';
    reset.onclick = resetTab;
    foot.appendChild(reset);
    var apply = el('button', 'fct-primary' + (state.dirty ? '' : ' fct-quiet'), state.dirty ? '套用（重新載入畫面）' : '完成');
    apply.type = 'button';
    apply.onclick = function () {
      if (state.dirty) location.reload();
      else api.close();
    };
    foot.appendChild(apply);
    sheet.appendChild(foot);

    body.scrollTop = top; // keep the place while editing
  }

  var api = {
    /** opts.u: CSS px per "real" px (screen density × quality); opts.onClose: called after closing. */
    open: function (opts) {
      state.opts = opts || {};
      state.o = load();
      state.mode = currentMode();
      state.tab = 'mode';
      state.dirty = false;
      if (!root) {
        root = el('div', 'fct-root');
        var scrim = el('div', 'fct-scrim');
        scrim.onclick = function () { api.close(); };
        root.appendChild(scrim);
        root.appendChild(el('div', 'fct-sheet'));
        root.setAttribute('role', 'dialog');
        root.setAttribute('aria-label', '動畫與外觀');
        document.body.appendChild(root);
        document.addEventListener('keydown', function (e) {
          if (root && root.classList.contains('fct-open') && e.key === 'Escape') { e.stopPropagation(); api.close(); }
        }, true);
      }
      root.style.setProperty('--fu', (state.opts.u || 1) + 'px');
      root.classList.toggle('fct-narrow', window.innerWidth / (state.opts.u || 1) < 560);
      root.classList.add('fct-open');
      render();
    },
    close: function () {
      if (!root || !root.classList.contains('fct-open')) return;
      root.classList.remove('fct-open');
      if (state.dirty) { location.reload(); return; }
      if (state.opts.onClose) state.opts.onClose();
    },
    isOpen: function () { return !!root && root.classList.contains('fct-open'); },
    /** How many options differ from Folia's defaults (for a hint next to the menu entry). */
    changedCount: function () {
      var o = load(), n = 0, k;
      for (k in o.g) n++;
      for (k in o.m) n += Object.keys(o.m[k]).length;
      for (k in o.b) n += Object.keys(o.b[k]).length;
      for (k in o.x) if (o.x[k]) n++;
      return n;
    },
  };
  window.FcTuning = api;
})();
