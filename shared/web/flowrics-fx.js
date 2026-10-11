// flowrics-fx.js — Flowrics' everyday lyric effects. The wild ones are in flowrics-fx-wild.js.
// Each effect gets a canvas already painted with the blurred cover (unless background: 'self'),
// S (song, lyric clock, palette, audio) and U (layout and easing helpers) — see flowrics-engine.js.
(function () {
  'use strict';
  var F = window.Flowrics, U = F.U;

  // ---------- shared bits ----------

  /** Draws a token with its sung part in `lit` and the rest in `dim` (textBaseline middle). */
  function fillToken(ctx, tk, x, y, t, dim, lit, size) {
    var p = U.progress(tk, t);
    if (p <= 0 || p >= 1 || dim === lit) {
      ctx.fillStyle = p >= 1 ? lit : dim;
      ctx.fillText(tk.text, x, y);
      return;
    }
    ctx.fillStyle = dim;
    ctx.fillText(tk.text, x, y);
    ctx.save();
    ctx.beginPath();
    ctx.rect(x - 2, y - size, tk.w * p + 2, size * 2);
    ctx.clip();
    ctx.fillStyle = lit;
    ctx.fillText(tk.text, x, y);
    ctx.restore();
  }
  F.fillToken = fillToken;

  /** The word being sung (or the last one sung) in a line. */
  function currentWord(line, t) {
    var w = null;
    for (var i = 0; i < line.words.length; i++) { if (line.words[i].start <= t) w = line.words[i]; else break; }
    return w;
  }
  F.currentWord = currentWord;

  function roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }
  F.roundRect = roundRect;

  function fmtTime(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    return ('0' + Math.floor(s / 60)).slice(-2) + ':' + ('0' + (s % 60)).slice(-2);
  }

  // =====================================================================
  // 潛入 Dive — the signature: words surface one by one, every line gets its own layout.
  // =====================================================================
  F.register('dive', {
    label: '潛入', desc: '字一個個從深處浮上來，每句換一種排版（流詞招牌）',
    draw: function (ctx, S, U) {
      var a = U.area(), list = U.active(520), pulse = S.audio.pulse;
      U.drawTitleCard(U.idle(list));

      // the word being sung, huge and faint behind everything
      var cur = list.length ? list[list.length - 1] : null;
      if (cur && !cur.leaving) {
        var w = currentWord(cur.line, S.t);
        if (w && w.text.trim()) {
          var txt = w.text.trim();
          var big = Math.min(a.h * 0.62, a.w * 0.95 / Math.max(1, Array.from(txt).length * (U.hasCJK(txt) ? 1 : 0.55)));
          var age = S.t - w.start;
          ctx.save();
          ctx.translate(a.w / 2, a.h * 0.46);
          ctx.rotate(-0.06);
          var sc = 1 + age / 6000 + pulse * 0.04;
          ctx.scale(sc, sc);
          ctx.globalAlpha = 0.07 * U.smooth(age / 250) + pulse * 0.05;
          ctx.font = U.font(big, 900);
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.lineWidth = Math.max(1, 3 * S.scale);
          ctx.strokeStyle = S.palette.hlCss;
          ctx.strokeText(txt, 0, 0);
          ctx.restore();
        }
      }

      list.forEach(function (p) {
        var l = p.line, r = U.rand(U.hash(l.text) + l.start);
        var style = Math.floor(r() * 4);
        var weight = r() < 0.6 ? 900 : 800;
        var fam = r() < 0.22 ? 'serif' : 'sans';
        var maxW = a.w * (0.7 + r() * 0.16);
        var size = U.fit(l, 'dive', (74 + r() * 34) * S.scale, weight, fam, maxW, 3, 1.12);
        var font = U.font(size, weight, fam);
        var align = style === 1 ? 'left' : style === 2 ? 'right' : 'center';
        var cx = align === 'left' ? (a.w - maxW) / 2 : align === 'right' ? (a.w + maxW) / 2 : a.w / 2;
        var cy = a.h * (0.44 + (r() - 0.5) * 0.12);
        var tilt = (r() - 0.5) * 0.07;
        var P = U.place(l, font, maxW, size * 1.12, cx, cy, align);
        var ex = U.inCubic(p.exit);
        ctx.save();
        ctx.translate(a.w / 2, cy);
        ctx.rotate(tilt * (1 - ex));
        ctx.scale(1 + ex * 1.8, 1 + ex * 1.8);
        ctx.translate(-a.w / 2, -cy);
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        P.tokens.forEach(function (tk) {
          var appear = (S.t - tk.start + 170) / 420;
          if (appear <= 0) return;
          var e = U.outCubic(appear);
          var sc = 0.45 + 0.55 * U.outBack(Math.min(1, appear), 1.3);
          var sung = U.progress(tk, S.t);
          // style 3: rows step in from alternating sides
          var dx = style === 3 ? (tk.row % 2 ? 1 : -1) * (1 - e) * size * 1.2 : 0;
          ctx.save();
          ctx.translate(tk.x + tk.w / 2 + dx, tk.y + (1 - e) * size * 0.6);
          ctx.scale(sc, sc);
          ctx.globalAlpha = (1 - ex) * U.clamp(appear * 1.6, 0, 1);
          var singing = sung > 0 && sung < 1;
          if (singing) {
            ctx.shadowColor = S.palette.hlCss;
            ctx.shadowBlur = size * 0.3 * (0.7 + pulse);
            ctx.fillStyle = S.palette.hlCss;
          } else {
            ctx.fillStyle = sung >= 1 ? '#fff' : 'rgba(255,255,255,0.6)';
          }
          ctx.fillText(tk.text, -tk.w / 2, 0);
          ctx.restore();
        });
        ctx.restore();
      });
    },
  });

  // =====================================================================
  // 極簡 Minimal — a calm scrolling list, the line being sung fills in word by word.
  // =====================================================================
  F.register('minimal', {
    label: '極簡', desc: '安靜的滾動歌詞，唱到哪亮到哪', subtitle: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, lines = S.lines;
      U.drawTitleCard(S.hasLyric ? 0 : 1);
      if (!lines.length) return;
      var size = Math.round(52 * S.scale), lh = size * 1.2, tsize = Math.round(size * 0.52);
      var maxW = Math.min(a.w * 0.84, 1500 * S.scale), left = (a.w - maxW) / 2;
      var font = U.font(size, 800), tfont = U.font(tsize, 500);
      var key = S.w + 'x' + S.h + '|' + lines.length + '|' + S.subtitleMode;
      if (st.key !== key) {
        st.key = key;
        st.tops = []; st.hs = [];
        var y = 0;
        lines.forEach(function (l, i) {
          var h = l.gap || !l.text ? size * 0.9 : U.layout(l, font, maxW, lh).height;
          if (U.subtitleText(l) && !l.gap) h += tsize * 1.5;
          st.tops[i] = y; st.hs[i] = h;
          y += h + size * 0.75;
        });
        st.pos = null;
      }
      var idx = Math.max(0, S.idx);
      var target = st.tops[idx] + st.hs[idx] / 2 + (S.idx < 0 ? -size * 2 : 0);
      if (st.pos == null) st.pos = target;
      st.pos += (target - st.pos) * (1 - Math.pow(0.004, S.dt / 1000));
      var mid = a.h * 0.42;
      ctx.save();
      ctx.textBaseline = 'middle';
      ctx.textAlign = 'left';
      for (var i = 0; i < lines.length; i++) {
        var top = mid + st.tops[i] - st.pos;
        if (top > a.h + 10 || top + st.hs[i] < -10) continue;
        var l = lines[i], d = Math.abs(i - S.idx);
        var isCur = i === S.idx && S.t < U.lineEnd(l);
        // fade towards the edges
        var edge = U.smooth(Math.min(top + st.hs[i] / 2, a.h - top - st.hs[i] / 2) / (a.h * 0.18));
        var alpha = (isCur ? 1 : Math.max(0.14, 0.42 - (d - 1) * 0.09)) * edge;
        ctx.globalAlpha = alpha;
        if (l.gap || !l.text) {
          // instrumental break: three dots filling up
          var gp = isCur ? U.clamp((S.t - l.start) / Math.max(1, (S.next ? S.next.start : l.end) - l.start), 0, 1) : 0;
          for (var k = 0; k < 3; k++) {
            var on = U.clamp(gp * 3 - k, 0, 1);
            ctx.fillStyle = 'rgba(255,255,255,' + (0.3 + on * 0.7) + ')';
            ctx.beginPath();
            ctx.arc(left + size * 0.3 + k * size * 0.55, top + size * 0.45, size * 0.13 * (1 + (isCur ? Math.sin(S.now / 300 + k) * 0.15 : 0)), 0, Math.PI * 2);
            ctx.fill();
          }
          continue;
        }
        ctx.font = font;
        var L = U.layout(l, font, maxW, lh);
        var P = U.place(l, font, maxW, lh, left, top + L.height / 2, 'left');
        P.tokens.forEach(function (tk) {
          if (isCur) fillToken(ctx, tk, tk.x, tk.y, S.t, 'rgba(255,255,255,0.36)', '#fff', size);
          else { ctx.fillStyle = '#fff'; ctx.fillText(tk.text, tk.x, tk.y); }
        });
        var sub = U.subtitleText(l);
        if (sub) {
          ctx.font = tfont;
          ctx.fillStyle = 'rgba(255,255,255,0.75)';
          ctx.fillText(sub, left, top + L.height + tsize * 0.8, maxW);
        }
      }
      ctx.restore();
    },
  });

  // =====================================================================
  // 卡拉OK Karaoke — two staggered lines, the sung part changes colour.
  // =====================================================================
  F.register('karaoke', {
    label: '卡拉OK', desc: 'KTV 雙行字幕，唱過的字變色，前奏有倒數',
    draw: function (ctx, S, U) {
      var a = U.area(), t = S.t;
      U.drawTitleCard(S.hasLyric ? 0 : 1);
      var maxW = a.w * 0.8;
      var slots = [
        { x: (a.w - maxW) / 2, y: a.h * 0.56, align: 'left' },
        { x: (a.w + maxW) / 2, y: a.h * 0.56 + 110 * S.scale, align: 'right' },
      ];
      var sungCol = U.hue(0, 95, 62);
      [0, 1].forEach(function (slot) {
        var pick = null, alpha = 1;
        [S.line, S.next, S.prev].some(function (l, k) {
          if (!l || l.gap || !l.text || l.index % 2 !== slot) return false;
          if (k === 0 && t < U.lineEnd(l) + 400) { pick = l; alpha = 1 - U.smooth((t - U.lineEnd(l)) / 400); return true; }
          if (k === 1 && l.start - t < 7000) { pick = l; alpha = U.smooth((7000 - (l.start - t)) / 400); return true; }
          if (k === 2 && t - U.lineEnd(l) < 500) { pick = l; alpha = 1 - U.smooth((t - U.lineEnd(l)) / 500); return true; }
          return false;
        });
        if (!pick || alpha <= 0) return;
        var s = slots[slot];
        var size = U.fit(pick, 'kara', 66 * S.scale, 900, 'round', maxW, 1, 1.2);
        var font = U.font(size, 900, 'round');
        var P = U.place(pick, font, maxW, size * 1.2, s.x, s.y, s.align);
        ctx.save();
        ctx.globalAlpha = alpha;
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        ctx.lineJoin = 'round';
        ctx.lineWidth = size * 0.16;
        ctx.strokeStyle = 'rgba(10,10,30,0.9)';
        ctx.fillStyle = '#fff';
        P.tokens.forEach(function (tk) { ctx.strokeText(tk.text, tk.x, tk.y); ctx.fillText(tk.text, tk.x, tk.y); });
        // the sung part
        ctx.beginPath();
        var any = false;
        P.tokens.forEach(function (tk) {
          var p = U.progress(tk, t);
          if (p > 0) { any = true; ctx.rect(tk.x - size * 0.1, tk.y - size, tk.w * p + (p >= 1 ? size * 0.1 : 0), size * 2); }
        });
        if (any) {
          ctx.clip();
          ctx.strokeStyle = '#fff';
          ctx.fillStyle = sungCol;
          P.tokens.forEach(function (tk) { ctx.strokeText(tk.text, tk.x, tk.y); ctx.fillText(tk.text, tk.x, tk.y); });
        }
        ctx.restore();
        // countdown dots before a line that comes after a long break
        var before = pick.start - t;
        var prevEnd = pick.index > 0 ? S.lines[pick.index - 1].sungEnd : 0;
        if (before > 0 && before < 3000 && pick.start - prevEnd > 4000) {
          var n = Math.ceil(before / 750);
          var x0 = s.align === 'left' ? s.x : s.x - P.rows[0].width;
          ctx.save();
          ctx.fillStyle = sungCol;
          ctx.strokeStyle = 'rgba(10,10,30,0.9)';
          ctx.lineWidth = size * 0.06;
          for (var k = 0; k < n; k++) {
            ctx.beginPath();
            ctx.arc(x0 + size * 0.25 + k * size * 0.5, s.y - size * 0.95, size * 0.15, 0, Math.PI * 2);
            ctx.fill(); ctx.stroke();
          }
          ctx.restore();
        }
      });
    },
  });

  // =====================================================================
  // 電影字幕 Cinema — letterbox, serif subtitles, a title sequence between lines.
  // =====================================================================
  F.register('cinema', {
    label: '電影字幕', desc: '寬銀幕黑邊、襯線字幕，像在看一部電影', subtitle: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      st.bars = (st.bars || 0) + (1 - (st.bars || 0)) * (1 - Math.pow(0.05, S.dt / 1000));
      ctx.save();
      // darken and vignette the picture
      var g = ctx.createRadialGradient(S.w / 2, S.h / 2, Math.min(S.w, S.h) * 0.25, S.w / 2, S.h / 2, Math.max(S.w, S.h) * 0.7);
      g.addColorStop(0, 'rgba(0,0,0,0.15)');
      g.addColorStop(1, 'rgba(0,0,0,0.65)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, S.w, S.h);
      var barH = U.clamp((S.h - S.w / 2.39) / 2, S.h * 0.07, S.h * 0.13) * st.bars;
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, S.w, barH);
      ctx.fillRect(0, Math.min(S.h, a.h + 1) - barH, S.w, S.h);
      var picBottom = a.h - barH;
      var list = U.active(140);
      var idle = U.idle(list);
      if (idle > 0.01 && S.title) {
        ctx.globalAlpha = idle;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = '#f4efe6';
        ctx.font = U.font(58 * S.scale, 400, 'serif');
        try { ctx.letterSpacing = Math.round(10 * S.scale) + 'px'; } catch (e) { /* older engines */ }
        ctx.fillText(S.title, S.w / 2, a.h * 0.45, S.w * 0.8);
        ctx.font = U.font(24 * S.scale, 400, 'serif');
        try { ctx.letterSpacing = Math.round(6 * S.scale) + 'px'; } catch (e) { }
        ctx.globalAlpha = idle * 0.7;
        ctx.fillText('— ' + S.artist + ' —', S.w / 2, a.h * 0.45 + 70 * S.scale, S.w * 0.8);
        try { ctx.letterSpacing = '0px'; } catch (e) { }
      }
      var size = Math.round(44 * S.scale), tsize = Math.round(30 * S.scale);
      list.forEach(function (p) {
        var l = p.line, sub = U.subtitleText(l);
        var alpha = U.smooth((p.age - 120) / 200) * (1 - p.exit);
        if (alpha <= 0) return;
        var maxW = a.w * 0.84;
        var fs = U.fit(l, 'cine', size, 500, 'serif', maxW, 2, 1.25);
        var font = U.font(fs, 500, 'serif');
        var L = U.layout(l, font, maxW, fs * 1.25);
        var subH = sub ? tsize * 1.5 : 0;
        var cy = picBottom - 40 * S.scale - subH - L.height / 2;
        var P = U.place(l, font, maxW, fs * 1.25, a.w / 2, cy, 'center');
        ctx.globalAlpha = alpha;
        ctx.font = font;
        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';
        ctx.shadowColor = 'rgba(0,0,0,0.9)';
        ctx.shadowBlur = 6 * S.scale;
        ctx.fillStyle = '#fbf8f1';
        P.tokens.forEach(function (tk) { ctx.fillText(tk.text, tk.x, tk.y); });
        if (sub) {
          ctx.font = U.font(tsize, 400, 'serif');
          ctx.textAlign = 'center';
          ctx.fillStyle = '#f1dc9a';
          ctx.fillText(sub, a.w / 2, cy + L.height / 2 + tsize, maxW);
        }
        ctx.shadowBlur = 0;
      });
      ctx.restore();
    },
  });

  // =====================================================================
  // 墨染 Ink — ink bleeding into paper. CJK lyrics are written in vertical columns that run from
  // right to left like a book: 翻頁 fills a page and turns it, 捲軸 unrolls like a hand scroll.
  // Five colour schemes (配色).
  // =====================================================================
  function inkPaperBase(g, S, U, top, bottom, coverAlpha, coverFilter) {
    var lg = g.createLinearGradient(0, 0, 0, S.h);
    lg.addColorStop(0, top);
    lg.addColorStop(1, bottom);
    g.fillStyle = lg;
    g.fillRect(0, 0, S.w, S.h);
    if (S.cover && coverAlpha > 0) {
      g.save();
      g.globalAlpha = coverAlpha;
      g.filter = coverFilter;
      var sc = Math.max(S.w / S.cover.width, S.h / S.cover.height) * 1.1;
      g.drawImage(S.cover, (S.w - S.cover.width * sc) / 2, (S.h - S.cover.height * sc) / 2, S.cover.width * sc, S.cover.height * sc);
      g.restore();
    }
  }
  function inkFibres(g, S, U, color, n) {
    var r = U.rand(7);
    g.strokeStyle = color;
    g.lineWidth = Math.max(1, S.scale);
    for (var i = 0; i < n; i++) {
      var x = r() * S.w, y = r() * S.h, ang = r() * Math.PI;
      g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.cos(ang) * 30 * S.scale, y + Math.sin(ang) * 30 * S.scale); g.stroke();
    }
  }
  function inkVignette(g, S, rgb, alpha) {
    var vg = g.createRadialGradient(S.w / 2, S.h / 2, Math.min(S.w, S.h) * 0.3, S.w / 2, S.h / 2, Math.max(S.w, S.h) * 0.75);
    vg.addColorStop(0, 'rgba(' + rgb + ',0)');
    vg.addColorStop(1, 'rgba(' + rgb + ',' + alpha + ')');
    g.fillStyle = vg;
    g.fillRect(0, 0, S.w, S.h);
  }
  var INK_THEMES = {
    // 宣紙: warm rice paper, black ink, a red seal
    paper: {
      paint: function (g, S, U) {
        inkPaperBase(g, S, U, U.hsl(S.palette.hue, 18, 93), U.hsl(S.palette.hue, 16, 89), 0.16, 'blur(' + Math.round(S.w / 30) + 'px) grayscale(0.4)');
        inkFibres(g, S, U, 'rgba(90,70,40,0.05)', 260);
        inkVignette(g, S, '60,40,20', 0.22);
      },
      ink: function () { return '#1b1814'; },
      blot: '20,16,12', blotAlpha: 0.16, sub: 'rgba(40,32,24,0.72)',
      seal: 'rgba(178,34,34,0.86)', sealText: '#f3ece0',
    },
    // 夜金: near-black lacquer with gold, pearly lettering that shimmers slowly
    gold: {
      paint: function (g, S, U) {
        inkPaperBase(g, S, U, '#14110d', '#080706', 0.1, 'blur(' + Math.round(S.w / 24) + 'px) grayscale(1) brightness(0.6)');
        var r = U.rand(19);
        for (var i = 0; i < 220; i++) {
          g.fillStyle = 'rgba(222,186,96,' + (0.04 + r() * 0.14) + ')';
          var s = (0.6 + r() * 1.8) * S.scale;
          g.fillRect(r() * S.w, r() * S.h, s, s);
        }
        inkFibres(g, S, U, 'rgba(230,200,140,0.025)', 160);
        inkVignette(g, S, '0,0,0', 0.55);
      },
      ink: function (ctx, S) {
        var sh = Math.sin(S.now / 5200) * S.w * 0.35;
        var gr = ctx.createLinearGradient(sh - S.w * 0.2, 0, sh + S.w * 1.1, S.h);
        gr.addColorStop(0, '#a87a24');
        gr.addColorStop(0.18, '#e9c46a');
        gr.addColorStop(0.32, '#fff3d2');
        gr.addColorStop(0.4, '#f4dcef');
        gr.addColorStop(0.48, '#f1d488');
        gr.addColorStop(0.64, '#c9973a');
        gr.addColorStop(0.8, '#f8e6b0');
        gr.addColorStop(0.9, '#e4f0e6');
        gr.addColorStop(1, '#b5852b');
        return gr;
      },
      glow: 'rgba(233,196,106,0.4)', blot: '233,196,106', blotAlpha: 0.12, sub: 'rgba(236,218,170,0.78)',
      seal: '#c9a14a', sealText: '#14110d',
    },
    // 封面: the album cover, blurred and dimmed, with warm white ink
    cover: {
      paint: function (g, S, U) {
        inkPaperBase(g, S, U, U.hsl(S.palette.hue, 30, 16), U.hsl(S.palette.hue, 30, 8), 0.9, 'blur(' + Math.round(S.w / 22) + 'px) saturate(1.25)');
        g.fillStyle = 'rgba(0,0,0,0.42)';
        g.fillRect(0, 0, S.w, S.h);
        inkFibres(g, S, U, 'rgba(255,255,255,0.025)', 160);
        inkVignette(g, S, '0,0,0', 0.5);
      },
      ink: function () { return '#f8f4ea'; },
      glow: 'rgba(0,0,0,0.55)', blot: '255,255,255', blotAlpha: 0.1, sub: 'rgba(255,255,255,0.78)',
      seal: null, sealText: '#fff',
    },
    // 青瓷: pale celadon glaze, blue-grey ink, vermilion seal
    celadon: {
      paint: function (g, S, U) {
        inkPaperBase(g, S, U, '#d9e6de', '#c3d6cc', 0.1, 'blur(' + Math.round(S.w / 30) + 'px) grayscale(0.7)');
        // crackle glaze
        var r = U.rand(23);
        g.strokeStyle = 'rgba(60,90,80,0.07)';
        g.lineWidth = Math.max(1, S.scale);
        for (var i = 0; i < 70; i++) {
          var x = r() * S.w, y = r() * S.h;
          g.beginPath(); g.moveTo(x, y);
          for (var k = 0; k < 4; k++) { x += (r() - 0.5) * 90 * S.scale; y += (r() - 0.5) * 90 * S.scale; g.lineTo(x, y); }
          g.stroke();
        }
        inkVignette(g, S, '30,60,55', 0.2);
      },
      ink: function () { return '#1d3140'; },
      blot: '29,49,64', blotAlpha: 0.14, sub: 'rgba(29,49,64,0.72)',
      seal: 'rgba(196,58,36,0.88)', sealText: '#e6efe9',
    },
    // 靛夜: deep indigo night, moonlight, silver lettering
    indigo: {
      paint: function (g, S, U) {
        inkPaperBase(g, S, U, '#121c38', '#060a17', 0.08, 'blur(' + Math.round(S.w / 24) + 'px) grayscale(1)');
        var mg = g.createRadialGradient(S.w * 0.18, S.h * 0.14, 0, S.w * 0.18, S.h * 0.14, Math.max(S.w, S.h) * 0.45);
        mg.addColorStop(0, 'rgba(220,230,255,0.13)');
        mg.addColorStop(1, 'rgba(220,230,255,0)');
        g.fillStyle = mg;
        g.fillRect(0, 0, S.w, S.h);
        inkFibres(g, S, U, 'rgba(200,215,255,0.03)', 160);
        inkVignette(g, S, '0,0,10', 0.45);
      },
      ink: function (ctx, S) {
        var gr = ctx.createLinearGradient(0, 0, 0, S.h);
        gr.addColorStop(0, '#f4f7ff');
        gr.addColorStop(0.5, '#cdd7ea');
        gr.addColorStop(1, '#eef2fb');
        return gr;
      },
      glow: 'rgba(170,195,255,0.35)', blot: '200,215,255', blotAlpha: 0.1, sub: 'rgba(214,224,244,0.75)',
      seal: null, sealOutline: '#c9d4ea', sealText: '#c9d4ea',
    },
  };

  /** Every lyric line cut into columns (a line always starts a new column), for the vertical layouts. */
  function inkColumns(S, U, size, colH, font, fontKey) {
    var st = S.store, key = S.lines.length + '|' + Math.round(size) + '|' + Math.round(colH) + '|' + S.w + '|' + fontKey;
    if (st.inkKey === key) return st.inkCols;
    var cols = [];
    S.lines.forEach(function (l, li) {
      if (!l || l.gap || !l.text || !l.text.trim()) return;
      var col = null, run = 0;
      U.chars(l).forEach(function (c) {
        var side = !U.hasCJK(c.ch);
        var adv = side ? U.measure(c.ch, font) : size * 1.08;
        if (!col || (run + adv > colH && run > 0)) {
          if (!c.ch.trim() && col) return; // no column starting with a space
          col = { line: li, start: c.start, chars: [] };
          cols.push(col);
          run = 0;
        }
        col.chars.push({ ch: c.ch, start: c.start, side: side, pos: run + adv / 2 });
        run += adv;
      });
    });
    st.inkKey = key;
    st.inkCols = cols;
    st.inkPages = null;
    return cols;
  }

  // 墨染's fonts: Google Fonts, or slices built by tools/build-webfonts.py on the `fonts` branch (jsDelivr).
  var GF = 'https://fonts.googleapis.com/css2?display=swap&family=';
  var JSD = 'https://cdn.jsdelivr.net/gh/EnWeiLo/folia-controller@fonts/';
  var INK_FONTS_ZH = {
    wenkai: { family: '"LXGW WenKai TC"', weight: 400, bold: 700, css: GF + 'LXGW+WenKai+TC:wght@400;700' },
    notoserif: { family: '"Noto Serif TC"', weight: 600, bold: 800, css: GF + 'Noto+Serif+TC:wght@400;600;800' },
    genryu: { family: '"GenRyuMin2 TW"', weight: 400, bold: 400, css: JSD + 'genryumin/font.css' },
    twkai: { family: '"TW-Kai"', weight: 400, bold: 400, css: JSD + 'twkai/font.css' },
    iansui: { family: '"Iansui"', weight: 400, bold: 400, css: GF + 'Iansui' },
  };
  var INK_FONTS_JA = {
    shippori: { family: '"Shippori Mincho"', weight: 600, bold: 800, css: GF + 'Shippori+Mincho:wght@400;600;800' },
    zenold: { family: '"Zen Old Mincho"', weight: 600, bold: 900, css: GF + 'Zen+Old+Mincho:wght@400;600;900' },
    notojp: { family: '"Noto Serif JP"', weight: 600, bold: 800, css: GF + 'Noto+Serif+JP:wght@400;600;800' },
    // Windows has 游明朝; elsewhere しっぽり明朝 stands in
    yumincho: { family: '"Yu Mincho","YuMincho","Shippori Mincho"', weight: 600, bold: 700, css: GF + 'Shippori+Mincho:wght@400;600;800' },
    klee: { family: '"Klee One"', weight: 600, bold: 600, css: GF + 'Klee+One:wght@400;600' },
  };
  /**
   * The font maker for this song: Japanese lyrics (any kana) get the 日文字體, everything else the
   * 中文字體; translations under the lyrics always use the 中文字體. Starts the web font download for
   * the song's characters.
   */
  function inkFontFor(S, U) {
    var st = S.store;
    var key = S.opts.zhFont + '|' + S.opts.jaFont + '|' + S.lines.length + '|' + (S.lines[0] && S.lines[0].start) + '|' + S.title;
    if (st.inkFontKey !== key) {
      st.inkFontKey = key;
      var all = S.lines.map(function (l) { return l.text; }).join('');
      var subs = S.lines.map(function (l) { return (l.trans || '') + (l.roma || ''); }).join('');
      var ja = /[\u3040-\u30ff]/.test(all);
      var zh = INK_FONTS_ZH[S.opts.zhFont] || INK_FONTS_ZH.wenkai;
      var main = ja ? (INK_FONTS_JA[S.opts.jaFont] || INK_FONTS_JA.shippori) : zh;
      U.useFontCss(main.css);
      if (zh !== main) U.useFontCss(zh.css);
      var title = (S.title || '') + (S.artist || '') + '流夜';
      U.loadFontFor(main.weight + ' 40px ' + main.family, all + title);
      U.loadFontFor(main.bold + ' 40px ' + main.family, title);
      if (subs) U.loadFontFor(zh.weight + ' 40px ' + zh.family, subs);
      st.inkFonts = { main: main, zh: zh, id: (ja ? 'ja-' : 'zh-') + (ja ? S.opts.jaFont : S.opts.zhFont) };
    }
    var f = st.inkFonts;
    var make = function (size, bold, sub) {
      var d = sub ? f.zh : f.main;
      return (bold ? d.bold : d.weight) + ' ' + Math.max(1, Math.round(size)) + 'px ' + d.family + ',' + U.SERIF;
    };
    make.id = f.id;
    return make;
  }

  F.register('ink', {
    label: '墨染', desc: '墨字在紙上慢慢暈開，中文直書', background: 'self', subtitle: 'self',
    options: [
      { id: 'layout', label: '排版', def: 'page', choices: [
        ['page', '翻頁（由右往左寫，寫滿一頁就翻頁）'],
        ['scroll', '捲軸（由右往左寫，舊的字往右滑走）'],
        ['ltr', '左到右（由左往右寫，舊的字往左滑走）'],
      ] },
      { id: 'theme', label: '配色', def: 'paper', choices: [
        ['paper', '宣紙（米白紙、黑墨）'],
        ['gold', '夜金（暗色底、金色珠光字）'],
        ['cover', '封面（專輯封面模糊當背景）'],
        ['celadon', '青瓷（淡青釉色、藍灰墨）'],
        ['indigo', '靛夜（深藍夜色、銀白字）'],
      ] },
      { id: 'zhFont', label: '中文字體', def: 'wenkai', choices: [
        ['wenkai', '楷書（霞鶩文楷）'],
        ['notoserif', '明體（思源宋體）'],
        ['genryu', '復古明體（源流明體）'],
        ['twkai', '標準楷書（全字庫正楷體）'],
        ['iansui', '手寫（芫荽）'],
      ] },
      { id: 'jaFont', label: '日文字體', def: 'shippori', choices: [
        ['shippori', '小說明朝（しっぽり明朝）'],
        ['zenold', '古典明朝（Zen Old Mincho）'],
        ['notojp', '標準明朝（Noto Serif JP）'],
        ['yumincho', '游明朝（Windows 內建，其他裝置用小說明朝）'],
        ['klee', '手寫（Klee One）'],
      ] },
    ],
    draw: function (ctx, S, U) {
      var inkFont = inkFontFor(S, U);
      var a = U.area(), st = S.store, t = S.t, pal = S.palette;
      var theme = INK_THEMES[S.opts.theme] || INK_THEMES.paper;
      var themeId = INK_THEMES[S.opts.theme] ? S.opts.theme : 'paper';
      // background
      var paper = U.offscreen('paper', S.w, S.h);
      var pk = themeId + '|' + S.w + 'x' + S.h + '|' + (S.cover ? S._coverUrl : '') + '|' + Math.round(pal.hue);
      if (st.paperKey !== pk) {
        st.paperKey = pk;
        var pg = paper.getContext('2d');
        pg.save();
        theme.paint(pg, S, U);
        pg.restore();
      }
      ctx.drawImage(paper, 0, 0);
      var ink = theme.ink(ctx, S);
      var sealFill = theme.seal === null && !theme.sealOutline ? U.hsl(pal.hue, 55, 42, 0.9) : theme.seal;

      var list = U.active(900);
      var idle = U.idle(list);
      // seal, bottom-left
      if (S.title) {
        var ss = 64 * S.scale;
        ctx.save();
        ctx.translate(ss * 0.9, a.h - ss * 1.9);
        ctx.rotate(-0.04);
        if (theme.sealOutline) {
          ctx.strokeStyle = theme.sealOutline;
          ctx.lineWidth = Math.max(1.5, 3 * S.scale);
          roundRect(ctx, 0, 0, ss, ss, ss * 0.12);
          ctx.stroke();
        } else {
          ctx.fillStyle = sealFill;
          roundRect(ctx, 0, 0, ss, ss, ss * 0.12);
          ctx.fill();
        }
        ctx.fillStyle = theme.sealText;
        ctx.font = inkFont(ss * 0.62, true);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(Array.from(S.title)[0] || '流', ss / 2, ss / 2 + ss * 0.03);
        ctx.restore();
      }
      if (idle > 0.01 && S.title) {
        ctx.save();
        ctx.globalAlpha = idle;
        ctx.fillStyle = ink;
        if (theme.glow) { ctx.shadowColor = theme.glow; ctx.shadowBlur = 18 * S.scale; }
        ctx.textAlign = 'center';
        ctx.font = inkFont(64 * S.scale, true);
        ctx.fillText(S.title, S.w / 2, a.h * 0.46, S.w * 0.8);
        ctx.globalAlpha = idle * 0.6;
        ctx.font = inkFont(28 * S.scale);
        ctx.fillText(S.artist, S.w / 2, a.h * 0.46 + 56 * S.scale, S.w * 0.8);
        ctx.restore();
      }

      // one written character, with ink spreading under it while it's fresh
      function writeChar(ch, side, x, y, start, size, alpha) {
        var since = t - start + 120;
        if (since <= 0 || alpha <= 0.01) return;
        var e = U.clamp(since / 700, 0, 1);
        if (e < 1) {
          var sb = ctx.shadowBlur;
          ctx.shadowBlur = 0;
          var rr = size * (0.3 + e * 0.5);
          var bg = ctx.createRadialGradient(x, y, 0, x, y, rr);
          bg.addColorStop(0, 'rgba(' + theme.blot + ',' + (theme.blotAlpha * (1 - e) * alpha) + ')');
          bg.addColorStop(1, 'rgba(' + theme.blot + ',0)');
          ctx.fillStyle = bg;
          ctx.fillRect(x - rr, y - rr, rr * 2, rr * 2);
          ctx.shadowBlur = sb;
        }
        ctx.globalAlpha = alpha * U.outCubic(e);
        if (e < 1) ctx.filter = 'blur(' + ((1 - e) * size * 0.12).toFixed(1) + 'px)';
        ctx.fillStyle = ink;
        if (side) { ctx.save(); ctx.translate(x, y); ctx.rotate(Math.PI / 2); ctx.fillText(ch, 0, 0); ctx.restore(); }
        else ctx.fillText(ch, x, y);
        ctx.filter = 'none';
        ctx.globalAlpha = 1;
      }

      var vertical = S.lines.length && U.hasCJK(S.lines.map(function (l) { return l.text; }).join('').slice(0, 200));
      ctx.save();
      ctx.textBaseline = 'middle';
      ctx.textAlign = 'center';
      if (theme.glow) { ctx.shadowColor = theme.glow; ctx.shadowBlur = 0; }
      if (vertical) {
        var size = Math.round(Math.min(66 * S.scale, a.w / 9));
        var font = inkFont(size);
        var perCol = Math.max(4, Math.floor(a.h * 0.72 / (size * 1.08)));
        var colH = perCol * size * 1.08, colGap = size * 1.65;
        var topY = a.h * 0.12;
        ctx.font = font;
        var cols = inkColumns(S, U, size, colH, font, inkFont.id + '|' + S.fontEpoch);
        // the column being written: the newest one with a character already on the paper
        var cur = -1;
        for (var ci = 0; ci < cols.length; ci++) { if (cols[ci].start - 120 <= t) cur = ci; else break; }
        var fade = 1 - idle;
        if (theme.glow) ctx.shadowBlur = size * 0.22;
        var drawCol = function (c, x, alpha) {
          c.chars.forEach(function (ch) {
            if (ch.ch.trim()) writeChar(ch.ch, ch.side, x, topY + ch.pos, ch.start, size, alpha);
          });
        };
        if (cur >= 0) {
          if (S.opts.layout === 'scroll' || S.opts.layout === 'ltr') {
            // 捲軸 (right to left): the column being written sits left of centre, older ones slide right.
            // 左到右: mirrored — written left to right, the newest right of centre, older ones slide left.
            var dir = S.opts.layout === 'ltr' ? -1 : 1;
            var anchor = dir > 0 ? a.w * 0.36 : a.w * 0.64;
            st.scroll = st.scroll == null || Math.abs(st.scroll - cur) > 6 ? cur : st.scroll + (cur - st.scroll) * (1 - Math.pow(0.03, S.dt / 1000));
            for (var k = cur; k >= 0; k--) {
              var x = anchor + dir * (st.scroll - k) * colGap;
              if (x > a.w + colGap || x < -colGap) break;
              var edge = U.smooth((dir > 0 ? a.w - x : x) / (a.w * 0.3));
              drawCol(cols[k], x, fade * edge);
            }
          } else {
            // 翻頁: right to left across the page; a full page fades away and the next starts on the right
            if (!st.inkPages) {
              var cap = Math.max(2, Math.floor(a.w * 0.84 / colGap)), pages = [], page = 0, used = 0;
              cols.forEach(function (c, i) {
                var lineCols = 0;
                for (var j = i; j < cols.length && cols[j].line === c.line; j++) lineCols++;
                var firstOfLine = i === 0 || cols[i - 1].line !== c.line;
                // keep a line on one page when it fits
                if (used > 0 && (used >= cap || (firstOfLine && lineCols <= cap && used + lineCols > cap))) { page++; used = 0; }
                pages[i] = { page: page, slot: used };
                used++;
              });
              st.inkPages = { cap: cap, at: pages };
            }
            var P = st.inkPages, curPage = P.at[cur].page;
            if (st.page !== curPage) { st.prevPage = st.page; st.page = curPage; st.turnAt = S.now; }
            var right = a.w / 2 + (P.cap - 1) * colGap / 2;
            var turn = U.clamp((S.now - (st.turnAt || 0)) / 900, 0, 1);
            for (var k2 = 0; k2 <= cur; k2++) {
              var pa = P.at[k2];
              var alpha = 0;
              if (pa.page === curPage) alpha = fade;
              else if (pa.page === st.prevPage && turn < 1) alpha = fade * (1 - U.smooth(turn));
              if (alpha <= 0.01) continue;
              drawCol(cols[k2], right - pa.slot * colGap, alpha);
            }
          }
        }
        ctx.shadowBlur = 0;
        // translation at the bottom
        var line = S.line && !S.line.gap ? S.line : null;
        var sub = line ? U.subtitleText(line) : '';
        if (sub) {
          ctx.font = inkFont(24 * S.scale, false, true);
          ctx.fillStyle = theme.sub;
          ctx.globalAlpha = U.smooth((t - line.start) / 600) * fade;
          ctx.fillText(sub, a.w / 2, a.h - 44 * S.scale, a.w * 0.9);
          ctx.globalAlpha = 1;
        }
      } else {
        list.forEach(function (p) {
          var l = p.line;
          var size = U.fit(l, 'ink' + inkFont.id, 62 * S.scale, 600, 'serif', a.w * 0.8, 3, 1.3);
          var font = inkFont(size);
          var P = U.place(l, font, a.w * 0.8, size * 1.3, a.w / 2, a.h * 0.44 - p.exit * 40 * S.scale, 'center');
          ctx.font = font;
          ctx.textAlign = 'left';
          if (theme.glow) ctx.shadowBlur = size * 0.22;
          P.tokens.forEach(function (tk) {
            var e = U.clamp((t - tk.start + 120) / 700, 0, 1);
            if (e <= 0) return;
            ctx.globalAlpha = U.outCubic(e) * (1 - p.exit);
            var blur = (1 - e) * size * 0.12 + p.exit * size * 0.2;
            if (blur > 0.3) ctx.filter = 'blur(' + blur.toFixed(1) + 'px)';
            ctx.fillStyle = ink;
            ctx.fillText(tk.text, tk.x, tk.y);
            ctx.filter = 'none';
          });
          ctx.shadowBlur = 0;
          var sub = U.subtitleText(l);
          if (sub) {
            ctx.globalAlpha = (1 - p.exit) * U.smooth(p.age / 600);
            ctx.textAlign = 'center';
            ctx.font = inkFont(26 * S.scale, false, true);
            ctx.fillStyle = theme.sub;
            ctx.fillText(sub, a.w / 2, P.top + P.height + 40 * S.scale, a.w * 0.9);
          }
        });
      }
      ctx.restore();
    },
  });

  // =====================================================================
  // 液面 Ripple — lyrics rise out of water, their reflection wobbles, beats ripple the surface.
  // =====================================================================
  F.register('ripple', {
    label: '液面', desc: '歌詞從水面浮起，倒影隨音樂起漣漪',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse;
      var surf = a.h * 0.58;
      st.rings = st.rings || [];
      if (pulse > 0.55 && S.now - (st.lastRing || 0) > 350) {
        st.lastRing = S.now;
        st.rings.push({ x: a.w * (0.3 + Math.random() * 0.4), t0: S.now, k: 1 });
      }
      // water body
      var wg = ctx.createLinearGradient(0, surf, 0, a.h);
      wg.addColorStop(0, 'rgba(0,0,0,0.15)');
      wg.addColorStop(1, 'rgba(0,0,0,0.6)');
      ctx.fillStyle = wg;
      ctx.fillRect(0, surf, S.w, a.h - surf);
      var list = U.active(500);
      U.drawTitleCard(U.idle(list));
      list.forEach(function (p, li) {
        var l = p.line;
        var size = U.fit(l, 'rip', 68 * S.scale, 800, 'sans', a.w * 0.84, 2, 1.15);
        var font = U.font(size, 800);
        var L = U.layout(l, font, a.w * 0.84, size * 1.15);
        var oh = Math.ceil(L.height + size * 0.5);
        var off = U.offscreen('rip' + (l.index % 2), a.w, oh);
        var g = off.getContext('2d');
        g.clearRect(0, 0, off.width, off.height);
        g.font = font;
        g.textBaseline = 'middle';
        g.textAlign = 'left';
        var P = U.place(l, font, a.w * 0.84, size * 1.15, a.w / 2, oh / 2, 'center');
        P.tokens.forEach(function (tk) { fillToken(g, tk, tk.x, tk.y, t, 'rgba(255,255,255,0.5)', '#fff', size); });
        // new words drop a small ring
        P.tokens.forEach(function (tk) {
          var k = l.start + ':' + tk.i;
          if (t >= tk.start && t - tk.start < 200 && st.lastTok !== k) { st.lastTok = k; st.rings.push({ x: tk.x + tk.w / 2, t0: S.now, k: 0.5 }); }
        });
        var rise = 1 - U.outCubic((p.age - 250) / 650);
        var sink = U.inCubic(p.exit);
        var dy = (rise + sink) * oh * 0.9;
        var y0 = surf - oh + dy;
        // above the surface
        ctx.save();
        ctx.beginPath(); ctx.rect(0, 0, S.w, surf); ctx.clip();
        ctx.globalAlpha = 1 - sink * 0.5;
        ctx.drawImage(off, 0, y0);
        ctx.restore();
        // reflection, row by row with a wobble
        var visible = oh - dy;
        if (visible > 0) {
          ctx.save();
          var amp = (4 + pulse * 14 + S.audio.power * 6) * S.scale;
          for (var y = 0; y < visible; y += 2) {
            var srcY = visible - y - 2;
            if (srcY < 0) break;
            var fall = y / Math.max(1, visible);
            ctx.globalAlpha = 0.42 * (1 - fall) * (1 - sink);
            var dx = Math.sin(y * 0.09 - S.now * 0.004) * amp * (0.3 + fall);
            ctx.drawImage(off, 0, srcY, off.width, 2, dx, surf + y, off.width, 2);
          }
          ctx.restore();
        }
      });
      // surface glint
      var sg = ctx.createLinearGradient(0, 0, S.w, 0);
      sg.addColorStop(0, 'rgba(255,255,255,0)');
      sg.addColorStop(0.5, U.rgba(S.palette.hl, 0.5 + pulse * 0.3));
      sg.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = sg;
      ctx.fillRect(0, surf, S.w, Math.max(1, 1.5 * S.scale));
      // rings
      ctx.save();
      st.rings = st.rings.filter(function (r) { return S.now - r.t0 < 1800; });
      st.rings.forEach(function (r) {
        var age = (S.now - r.t0) / 1800;
        var rx = (40 + age * 520) * S.scale * r.k;
        ctx.globalAlpha = (1 - age) * 0.5 * r.k;
        ctx.strokeStyle = U.rgba(S.palette.hl, 1);
        ctx.lineWidth = 1.5 * S.scale;
        ctx.beginPath();
        ctx.ellipse(r.x, surf + 6 * S.scale + age * 30 * S.scale, rx, rx * 0.12, 0, 0, Math.PI * 2);
        ctx.stroke();
      });
      ctx.restore();
    },
  });

  // =====================================================================
  // 星塵 Stardust — glowing words that shed sparkles and dissolve into dust.
  // =====================================================================
  F.register('stardust', {
    label: '星塵', desc: '發光的字灑下星點，換句時化成星塵飄走',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse;
      // night overlay and stars
      ctx.fillStyle = 'rgba(2,4,16,0.45)';
      ctx.fillRect(0, 0, S.w, S.h);
      if (!st.stars || st.starsKey !== S.w + 'x' + S.h) {
        st.starsKey = S.w + 'x' + S.h;
        var r = U.rand(11);
        st.stars = [];
        for (var i = 0; i < 160; i++) st.stars.push({ x: r() * S.w, y: r() * S.h, s: (0.6 + r() * 1.6) * S.scale * 1.6, ph: r() * 6.28, sp: 0.5 + r() * 2 });
      }
      ctx.save();
      ctx.fillStyle = '#fff';
      st.stars.forEach(function (s) {
        ctx.globalAlpha = 0.25 + 0.35 * Math.sin(S.now / 1000 * s.sp + s.ph) + pulse * 0.3;
        ctx.fillRect(s.x, s.y, s.s, s.s);
      });
      ctx.restore();
      var list = U.active(900);
      U.drawTitleCard(U.idle(list));
      st.parts = st.parts || [];
      st.done = st.done || {};
      var parts = st.parts;
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'dust', 64 * S.scale, 700, 'sans', a.w * 0.82, 3, 1.2);
        var font = U.font(size, 700);
        var P = U.place(l, font, a.w * 0.82, size * 1.2, a.w / 2, a.h * 0.45, 'center');
        // dissolve: once, turn the glyphs into dust
        if (p.leaving && !st.done['x' + l.start]) {
          st.done['x' + l.start] = 1;
          var step = Math.max(3, Math.round(size / 16));
          P.tokens.forEach(function (tk) {
            if (t < tk.start - 150) return;
            U.glyphPoints(tk.text, font, step).forEach(function (pt) {
              if (parts.length > 2600 || Math.random() < 0.35) return;
              parts.push({ x: tk.x + pt[0], y: tk.y + pt[1] + size * 0.35, vx: (Math.random() - 0.3) * 0.05 * S.scale, vy: -(0.02 + Math.random() * 0.08) * S.scale, life: 900 + Math.random() * 1400, age: 0, s: (1 + Math.random() * 2) * S.scale });
            });
          });
        }
        ctx.save();
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        ctx.shadowColor = S.palette.hlCss;
        P.tokens.forEach(function (tk) {
          var e = U.clamp((t - tk.start + 200) / 500, 0, 1);
          if (e <= 0) return;
          var sung = U.progress(tk, t);
          // sparkles when a word starts
          var key = l.start + ':' + tk.i;
          if (t >= tk.start && !st.done[key] && t - tk.start < 400) {
            st.done[key] = 1;
            for (var k = 0; k < 10; k++) {
              parts.push({ x: tk.x + Math.random() * tk.w, y: tk.y + (Math.random() - 0.5) * size, vx: (Math.random() - 0.5) * 0.06 * S.scale, vy: (0.01 + Math.random() * 0.06) * S.scale, life: 700 + Math.random() * 900, age: 0, s: (1.5 + Math.random() * 2.5) * S.scale });
            }
          }
          ctx.globalAlpha = U.outCubic(e) * (1 - Math.min(1, p.exit * 5));
          ctx.shadowBlur = size * (sung > 0 && sung < 1 ? 0.5 + pulse * 0.4 : 0.2);
          ctx.fillStyle = sung > 0 ? '#fff' : 'rgba(255,255,255,0.55)';
          ctx.fillText(tk.text, tk.x, tk.y - (1 - e) * size * 0.2);
        });
        ctx.restore();
      });
      // particles
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      var hl = S.palette.hl;
      for (var i = parts.length - 1; i >= 0; i--) {
        var q = parts[i];
        q.age += S.dt;
        if (q.age > q.life) { parts.splice(i, 1); continue; }
        q.x += q.vx * S.dt; q.y += q.vy * S.dt;
        q.vy -= 0.00002 * S.dt * S.scale;
        var f = 1 - q.age / q.life;
        ctx.globalAlpha = f * (0.6 + 0.4 * Math.sin(q.age / 60 + i));
        ctx.fillStyle = i % 3 ? '#fff' : U.rgba(hl, 1);
        ctx.fillRect(q.x, q.y, q.s, q.s);
      }
      ctx.restore();
    },
  });

  // =====================================================================
  // 霓虹 Neon — neon tubes that flicker on as they're sung.
  // =====================================================================
  F.register('neon', {
    label: '霓虹', desc: '唱到的字像霓虹燈管一樣閃爍點亮', background: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse, power = S.audio.power;
      var wall = U.offscreen('wall', S.w, S.h);
      if (st.wallKey !== S.w + 'x' + S.h) {
        st.wallKey = S.w + 'x' + S.h;
        var g = wall.getContext('2d');
        g.fillStyle = '#08070c';
        g.fillRect(0, 0, S.w, S.h);
        var bh = 44 * S.scale, bw = bh * 2.6;
        g.strokeStyle = 'rgba(255,255,255,0.035)';
        g.lineWidth = Math.max(1, 2 * S.scale);
        for (var row = 0, y = 0; y < S.h; row++, y += bh) {
          for (var x = (row % 2) * -bw / 2; x < S.w; x += bw) g.strokeRect(x, y, bw, bh);
        }
      }
      ctx.drawImage(wall, 0, 0);
      var c1 = U.hsl(S.palette.hue, 100, 62), c2 = U.hsl(S.palette.hue + 160, 100, 64);
      var glow = ctx.createRadialGradient(a.w / 2, a.h * 0.45, 0, a.w / 2, a.h * 0.45, Math.max(a.w, a.h) * 0.6);
      glow.addColorStop(0, U.hsl(S.palette.hue, 100, 50, 0.16 + power * 0.12));
      glow.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, S.w, S.h);
      var list = U.active(220);
      var idle = U.idle(list);
      ctx.save();
      ctx.textBaseline = 'middle';
      ctx.lineJoin = 'round';
      if (idle > 0.01 && S.title) {
        ctx.globalAlpha = idle;
        ctx.textAlign = 'center';
        ctx.font = U.font(70 * S.scale, 500, 'round');
        ctx.shadowColor = c2; ctx.shadowBlur = 30 * S.scale;
        ctx.strokeStyle = c2; ctx.lineWidth = 4 * S.scale;
        ctx.strokeText(S.title, S.w / 2, a.h * 0.44, S.w * 0.86);
        ctx.shadowBlur = 0; ctx.strokeStyle = '#fff'; ctx.lineWidth = 1.2 * S.scale;
        ctx.strokeText(S.title, S.w / 2, a.h * 0.44, S.w * 0.86);
        ctx.font = U.font(30 * S.scale, 500, 'round');
        ctx.fillStyle = c1;
        ctx.fillText(S.artist, S.w / 2, a.h * 0.44 + 70 * S.scale, S.w * 0.86);
      }
      ctx.textAlign = 'left';
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'neon', 80 * S.scale, 500, 'round', a.w * 0.84, 3, 1.25);
        var font = U.font(size, 500, 'round');
        var P = U.place(l, font, a.w * 0.84, size * 1.25, a.w / 2, a.h * 0.45, 'center');
        ctx.font = font;
        P.tokens.forEach(function (tk) {
          var col = tk.row % 2 ? c2 : c1;
          var since = t - tk.start + 60;
          // flicker on: a fixed stutter pattern per token
          var on = 0;
          if (since > 0) {
            if (since > 420) on = 1;
            else { var h = U.hash(tk.text + l.start + tk.i); on = ((h >> Math.floor(since / 50)) & 1) ? 1 : 0.15; }
          }
          // a rare buzz after lighting
          if (on === 1 && (U.hash(Math.floor(S.now / 80) + ':' + tk.i + l.start) % 600) === 0) on = 0.3;
          on *= 1 - p.exit;
          ctx.globalAlpha = 1 - p.exit;
          ctx.shadowBlur = 0;
          ctx.strokeStyle = 'rgba(255,255,255,0.09)';
          ctx.lineWidth = size * 0.05;
          ctx.strokeText(tk.text, tk.x, tk.y);
          if (on > 0) {
            ctx.globalAlpha = on;
            ctx.shadowColor = col;
            ctx.shadowBlur = size * (0.35 + pulse * 0.35);
            ctx.strokeStyle = col;
            ctx.lineWidth = size * 0.065;
            ctx.strokeText(tk.text, tk.x, tk.y);
            ctx.shadowBlur = 0;
            ctx.strokeStyle = 'rgba(255,255,255,0.9)';
            ctx.lineWidth = size * 0.02;
            ctx.strokeText(tk.text, tk.x, tk.y);
          }
        });
      });
      ctx.restore();
    },
  });

  // =====================================================================
  // 黑膠 Vinyl — a spinning record with the cover as its label, lyrics beside it.
  // =====================================================================
  function drawRecord(S, U, R) {
    var c = U.offscreen('record', R * 2 + 4, R * 2 + 4);
    var key = Math.round(R) + '|' + S._coverUrl + '|' + !!S.cover;
    if (S.store.recKey === key) return c;
    S.store.recKey = key;
    var g = c.getContext('2d');
    var cx = R + 2, cy = R + 2;
    g.clearRect(0, 0, c.width, c.height);
    g.fillStyle = '#0b0b0d';
    g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.fill();
    for (var r = R * 0.42; r < R * 0.97; r += Math.max(1.5, R / 90)) {
      g.strokeStyle = 'rgba(255,255,255,' + (0.025 + 0.02 * Math.sin(r * 0.7)) + ')';
      g.lineWidth = 1;
      g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke();
    }
    var lr = R * 0.36;
    g.save();
    g.beginPath(); g.arc(cx, cy, lr, 0, Math.PI * 2); g.clip();
    if (S.cover) {
      var sc = (lr * 2) / Math.min(S.cover.width, S.cover.height);
      g.drawImage(S.cover, cx - S.cover.width * sc / 2, cy - S.cover.height * sc / 2, S.cover.width * sc, S.cover.height * sc);
    } else {
      g.fillStyle = S.palette.hlCss; g.fillRect(0, 0, c.width, c.height);
    }
    g.restore();
    g.fillStyle = '#ddd';
    g.beginPath(); g.arc(cx, cy, R * 0.025, 0, Math.PI * 2); g.fill();
    return c;
  }
  F.register('vinyl', {
    label: '黑膠', desc: '封面變成唱片標籤在唱盤上轉，歌詞在一旁',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      var wide = a.w > a.h * 1.15;
      var R = wide ? Math.min(a.h * 0.36, a.w * 0.22) : Math.min(a.w * 0.34, a.h * 0.22);
      var rc = wide ? { x: a.w * 0.29, y: a.h * 0.5 } : { x: a.w / 2, y: a.h * 0.07 + R * 1.1 };
      st.spin = (st.spin || 0) + (S.playing ? 1 : 0) * S.dt / 1000 * (33.3 / 60) * Math.PI * 2;
      st.arm = (st.arm == null ? 0 : st.arm) + ((S.playing ? 1 : 0) - (st.arm || 0)) * (1 - Math.pow(0.05, S.dt / 1000));
      var rec = drawRecord(S, U, Math.round(R));
      ctx.save();
      // shadow
      ctx.fillStyle = 'rgba(0,0,0,0.45)';
      ctx.beginPath(); ctx.arc(rc.x + R * 0.04, rc.y + R * 0.06, R * 1.02, 0, Math.PI * 2); ctx.fill();
      ctx.translate(rc.x, rc.y);
      var bump = 1 + S.audio.bass * 0.015;
      ctx.scale(bump, bump);
      ctx.rotate(st.spin);
      ctx.drawImage(rec, -rec.width / 2, -rec.height / 2);
      ctx.rotate(-st.spin);
      // sheen
      ctx.globalCompositeOperation = 'lighter';
      var sh = ctx.createLinearGradient(-R, -R, R, R);
      sh.addColorStop(0.3, 'rgba(255,255,255,0)');
      sh.addColorStop(0.48, 'rgba(255,255,255,0.09)');
      sh.addColorStop(0.52, 'rgba(255,255,255,0.09)');
      sh.addColorStop(0.7, 'rgba(255,255,255,0)');
      ctx.fillStyle = sh;
      ctx.beginPath(); ctx.arc(0, 0, R * 0.98, 0, Math.PI * 2); ctx.fill();
      ctx.globalCompositeOperation = 'source-over';
      // tone arm
      var px = R * 1.08, py = -R * 0.95;
      var ang = 0.08 + st.arm * 0.36;
      ctx.translate(px, py);
      ctx.rotate(ang);
      ctx.strokeStyle = '#c9c9cf';
      ctx.lineCap = 'round';
      ctx.lineWidth = R * 0.035;
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(-R * 0.05, R * 0.95); ctx.lineTo(-R * 0.2, R * 1.12); ctx.stroke();
      ctx.fillStyle = '#9a9aa2';
      ctx.fillRect(-R * 0.27, R * 1.08, R * 0.14, R * 0.1);
      ctx.fillStyle = '#e3e3e8';
      ctx.beginPath(); ctx.arc(0, 0, R * 0.08, 0, Math.PI * 2); ctx.fill();
      ctx.restore();

      // lyrics
      var tx = wide ? a.w * 0.56 : a.w * 0.07;
      var tw = wide ? a.w * 0.38 : a.w * 0.86;
      var ty = wide ? a.h * 0.46 : rc.y + R * 1.25 + (a.h - rc.y - R * 1.25) * 0.4;
      var list = U.active(260);
      var idle = U.idle(list);
      ctx.save();
      ctx.textBaseline = 'middle';
      ctx.textAlign = 'left';
      if (idle > 0.01 && S.title) {
        ctx.globalAlpha = idle;
        ctx.fillStyle = '#fff';
        ctx.font = U.font(52 * S.scale, 800);
        ctx.fillText(S.title, tx, ty - 20 * S.scale, tw);
        ctx.globalAlpha = idle * 0.65;
        ctx.font = U.font(26 * S.scale, 500);
        ctx.fillText(S.artist + (S.album ? ' · ' + S.album : ''), tx, ty + 30 * S.scale, tw);
      }
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'vinyl', 54 * S.scale, 800, 'sans', tw, 3, 1.2);
        var font = U.font(size, 800);
        var e = U.outCubic((p.age - 180) / 450);
        if (e <= 0) return;
        var P = U.place(l, font, tw, size * 1.2, tx, ty + (1 - e) * 30 * S.scale - p.exit * 40 * S.scale, 'left');
        ctx.globalAlpha = e * (1 - p.exit);
        ctx.font = font;
        P.tokens.forEach(function (tk) { fillToken(ctx, tk, tk.x, tk.y, t, 'rgba(255,255,255,0.4)', '#fff', size); });
        if (!p.leaving && S.next && !S.next.gap && S.next.text) {
          ctx.globalAlpha = 0.35 * e;
          ctx.font = U.font(size * 0.55, 600);
          ctx.fillStyle = '#fff';
          ctx.fillText(S.next.text, tx, P.top + P.height + size * 0.6, tw);
        }
      });
      ctx.restore();
    },
  });

  // =====================================================================
  // 漫畫對白 Comic — speech bubbles pop up over a halftone page; big beats get sound effects.
  // =====================================================================
  var SFX = ['BAM!', 'POW!', 'BOOM!', 'WHAM!', 'ZAP!', '咚！', '鏘！', '轟！'];
  function bubblePath(ctx, x, y, w, h, spiky, seed) {
    if (!spiky) {
      roundRect(ctx, x, y, w, h, Math.min(w, h) * 0.45);
      return;
    }
    var r = U.rand(seed), cx = x + w / 2, cy = y + h / 2, n = 22;
    ctx.beginPath();
    for (var i = 0; i <= n * 2; i++) {
      var ang = i / (n * 2) * Math.PI * 2;
      var k = i % 2 ? 0.86 : 1.12 + r() * 0.14;
      var px = cx + Math.cos(ang) * w / 2 * k, py = cy + Math.sin(ang) * h / 2 * k;
      if (i) ctx.lineTo(px, py); else ctx.moveTo(px, py);
    }
    ctx.closePath();
  }
  F.register('comic', {
    label: '漫畫對白', desc: '歌詞變成漫畫對話框，重拍會跳出狀聲詞', background: 'self', subtitle: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pal = S.palette;
      // halftone page
      var page = U.offscreen('page', S.w, S.h);
      var pk = S.w + 'x' + S.h + '|' + Math.round(pal.hue);
      if (st.pageKey !== pk) {
        st.pageKey = pk;
        var g = page.getContext('2d');
        g.fillStyle = U.hsl(pal.hue, 70, 80);
        g.fillRect(0, 0, S.w, S.h);
        g.fillStyle = U.hsl(pal.hue, 65, 66);
        var step = 22 * S.scale;
        for (var y = 0; y < S.h + step; y += step) {
          for (var x = ((y / step) % 2) * step / 2; x < S.w + step; x += step) {
            var d = Math.hypot(x - S.w, y - S.h) / Math.hypot(S.w, S.h);
            var rad = step * 0.42 * (1 - d);
            if (rad > 0.5) { g.beginPath(); g.arc(x, y, rad, 0, Math.PI * 2); g.fill(); }
          }
        }
      }
      ctx.drawImage(page, 0, 0);
      // speed lines on beats
      var pulse = S.audio.pulse;
      if (pulse > 0.15) {
        ctx.save();
        ctx.globalAlpha = pulse * 0.5;
        ctx.strokeStyle = '#fff';
        var rr = U.rand(Math.floor(S.now / 120));
        for (var i = 0; i < 40; i++) {
          var ang = rr() * Math.PI * 2, r0 = Math.max(S.w, S.h) * (0.35 + rr() * 0.15);
          ctx.lineWidth = (2 + rr() * 6) * S.scale;
          ctx.beginPath();
          ctx.moveTo(S.w / 2 + Math.cos(ang) * r0, S.h / 2 + Math.sin(ang) * r0);
          ctx.lineTo(S.w / 2 + Math.cos(ang) * S.w, S.h / 2 + Math.sin(ang) * S.w);
          ctx.stroke();
        }
        ctx.restore();
      }
      // title panel when idle
      var list = U.active(400);
      var idle = U.idle(list);
      if (idle > 0.01 && S.title) {
        ctx.save();
        ctx.globalAlpha = idle;
        var tw = Math.min(a.w * 0.8, U.measure(S.title, U.font(60 * S.scale, 900, 'round')) + 80 * S.scale);
        var th = 150 * S.scale, tx = (a.w - tw) / 2, ty = a.h * 0.4 - th / 2;
        ctx.fillStyle = '#111';
        ctx.fillRect(tx + 10 * S.scale, ty + 10 * S.scale, tw, th);
        ctx.fillStyle = '#ffe14d';
        ctx.fillRect(tx, ty, tw, th);
        ctx.lineWidth = 5 * S.scale; ctx.strokeStyle = '#111'; ctx.strokeRect(tx, ty, tw, th);
        ctx.fillStyle = '#111'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.font = U.font(60 * S.scale, 900, 'round');
        ctx.fillText(S.title, a.w / 2, ty + th * 0.4, tw - 40 * S.scale);
        ctx.font = U.font(28 * S.scale, 700, 'round');
        ctx.fillText(S.artist, a.w / 2, ty + th * 0.76, tw - 40 * S.scale);
        ctx.restore();
      }
      // the last few lines as bubbles, newest at the bottom
      var shown = [];
      for (var li = S.idx; li >= 0 && shown.length < 3; li--) {
        var l = S.lines[li];
        if (l && !l.gap && l.text) shown.push(l);
      }
      if (idle > 0.98) shown = [];
      st.ys = st.ys || {};
      st.shout = st.shout || {};
      var y = a.h * 0.7;
      shown.forEach(function (l, k) {
        if (st.shout[l.start] == null) st.shout[l.start] = /[!！]/.test(l.text) || (S.audio.pulse > 0.8 && U.hash(l.text) % 3 === 0);
        var spiky = st.shout[l.start];
        var maxW = Math.min(a.w * 0.64, 820 * S.scale);
        var size = U.fit(l, 'comic', (spiky ? 60 : 54) * S.scale, 800, 'round', maxW, 4, 1.22);
        var font = U.font(size, 800, 'round');
        var L = U.layout(l, font, maxW, size * 1.22);
        var sub = U.subtitleText(l), ss = size * 0.52;
        var pad = size * (spiky ? 1.2 : 0.75);
        var tw = 0; L.rows.forEach(function (r) { tw = Math.max(tw, r.width); });
        if (sub) tw = Math.max(tw, Math.min(maxW, U.measure(sub, U.font(ss, 600, 'round'))));
        var bw = tw + pad * 2, bh = L.height + (sub ? ss * 1.5 : 0) + pad * 1.4;
        var targetBottom = y;
        y -= bh * (spiky ? 1.3 : 1) + 56 * S.scale;
        var cur = st.ys[l.start];
        if (cur == null) cur = targetBottom;
        cur += (targetBottom - cur) * (1 - Math.pow(0.004, S.dt / 1000));
        st.ys[l.start] = cur;
        var side = l.index % 2 ? 1 : -1;
        var cx = U.clamp(a.w / 2 + side * a.w * 0.12, bw / 2 + 20 * S.scale, a.w - bw / 2 - 20 * S.scale);
        if (spiky) cur -= bh * 0.13;
        var bx = cx - bw / 2, by = cur - bh;
        var pop = U.outBack(U.clamp((t - l.start + 120) / 320, 0, 1), 2);
        var fade = (k === 2 ? 0.55 : k === 1 ? 0.85 : 1) * (1 - idle);
        if (pop <= 0) return;
        ctx.save();
        ctx.globalAlpha = fade;
        ctx.translate(cx, cur);
        ctx.scale(pop, pop);
        ctx.translate(-cx, -cur);
        // tail
        var tailX = cx - side * bw * 0.2;
        ctx.fillStyle = '#111';
        ctx.beginPath();
        ctx.moveTo(tailX - 22 * S.scale + 8 * S.scale, by + bh - 6 * S.scale + 8 * S.scale);
        ctx.lineTo(tailX - side * 70 * S.scale + 8 * S.scale, by + bh + 46 * S.scale + 8 * S.scale);
        ctx.lineTo(tailX + 22 * S.scale + 8 * S.scale, by + bh - 6 * S.scale + 8 * S.scale);
        ctx.fill();
        // hard shadow + bubble
        ctx.save();
        ctx.translate(8 * S.scale, 8 * S.scale);
        bubblePath(ctx, bx, by, bw, bh, spiky, l.start);
        ctx.fill();
        ctx.restore();
        ctx.fillStyle = spiky ? '#fff36b' : '#fff';
        ctx.strokeStyle = '#111';
        ctx.lineWidth = 5 * S.scale;
        bubblePath(ctx, bx, by, bw, bh, spiky, l.start);
        ctx.fill(); ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(tailX - 22 * S.scale, by + bh - 6 * S.scale);
        ctx.lineTo(tailX - side * 70 * S.scale, by + bh + 46 * S.scale);
        ctx.lineTo(tailX + 22 * S.scale, by + bh - 6 * S.scale);
        ctx.fill(); ctx.stroke();
        // cover the tail's top edge
        ctx.fillRect(tailX - 20 * S.scale, by + bh - 12 * S.scale, 40 * S.scale, 10 * S.scale);
        // text
        var P = U.place(l, font, maxW, size * 1.22, cx, by + pad * 0.7 + L.height / 2, 'center');
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        ctx.fillStyle = '#111';
        P.tokens.forEach(function (tk) { if (k > 0 || t >= tk.start - 60) ctx.fillText(tk.text, tk.x, tk.y); });
        if (sub) {
          ctx.font = U.font(ss, 600, 'round');
          ctx.textAlign = 'center';
          ctx.fillStyle = 'rgba(17,17,17,0.6)';
          ctx.fillText(sub, cx, by + pad * 0.7 + L.height + ss * 0.9, maxW);
        }
        ctx.restore();
      });
      // sound effects
      st.sfx = st.sfx || [];
      if (pulse > 0.85 && S.now - (st.lastSfx || 0) > 1800 && S.playing) {
        st.lastSfx = S.now;
        st.sfx.push({ w: SFX[Math.floor(Math.random() * SFX.length)], x: a.w * (0.15 + Math.random() * 0.7), y: a.h * (0.12 + Math.random() * 0.22), t0: S.now, rot: (Math.random() - 0.5) * 0.5 });
      }
      st.sfx = st.sfx.filter(function (s) { return S.now - s.t0 < 900; });
      st.sfx.forEach(function (s) {
        var age = (S.now - s.t0) / 900;
        var sc = U.outBack(Math.min(1, age * 4), 2.5) * (1 + age * 0.1);
        var size = 70 * S.scale;
        ctx.save();
        ctx.globalAlpha = 1 - U.smooth((age - 0.7) / 0.3);
        ctx.translate(s.x, s.y);
        ctx.rotate(s.rot);
        ctx.scale(sc, sc);
        ctx.fillStyle = '#ffeb3b';
        ctx.strokeStyle = '#111';
        ctx.lineWidth = 4 * S.scale;
        bubblePath(ctx, -size * 1.6, -size, size * 3.2, size * 2, true, s.t0 | 0);
        ctx.fill(); ctx.stroke();
        ctx.font = U.font(size * 0.8, 900, 'round');
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.lineWidth = 6 * S.scale;
        ctx.strokeText(s.w, 0, 0);
        ctx.fillStyle = '#e53935';
        ctx.fillText(s.w, 0, 0);
        ctx.restore();
      });
    },
  });

  // =====================================================================
  // 終端 Terminal — the lyrics typed into a green-screen console.
  // =====================================================================
  function wrapText(text, font, maxW) {
    var rows = [], row = '';
    Array.from(text).forEach(function (ch) {
      if (U.measure(row + ch, font) > maxW && row) { rows.push(row); row = ''; }
      row += ch;
    });
    rows.push(row);
    return rows;
  }
  F.register('terminal', {
    label: '終端', desc: '綠色終端機，一字一字打出歌詞', background: 'self', subtitle: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      ctx.fillStyle = '#030803';
      ctx.fillRect(0, 0, S.w, S.h);
      var size = Math.round(Math.max(14, 34 * S.scale)), lh = size * 1.45;
      var font = U.font(size, 500, 'mono');
      var left = Math.max(16, 40 * S.scale), maxW = a.w - left * 2;
      var green = '#3dff7f', dim = '#1f9b4a', faint = '#6d9a7c';
      var rows = [];
      function push(text, color) { wrapText(text, font, maxW).forEach(function (r) { rows.push({ text: r, color: color }); }); }
      push('flowrics@now-playing:~$ play "' + (S.title || '…') + '"' + (S.artist ? ' --by "' + S.artist + '"' : ''), green);
      push(S.hasLyric ? '> lyrics loaded (' + S.lines.filter(function (l) { return !l.gap && l.text; }).length + ' lines)' : '> waiting for lyrics…', faint);
      var cursorRow = null;
      for (var i = Math.max(0, S.idx - 40); i <= S.idx; i++) {
        var l = S.lines[i];
        if (!l || l.gap || !l.text) continue;
        var cur = i === S.idx;
        var text = l.text;
        if (cur) {
          var n = U.chars(l).filter(function (c) { return c.start <= t; }).length;
          text = U.chars(l).slice(0, n).map(function (c) { return c.ch; }).join('');
        }
        push('[' + fmtTime(l.start) + '] ' + text, cur ? green : dim);
        if (cur) cursorRow = rows.length - 1;
        var sub = U.subtitleText(l);
        if (sub && (!cur || t > l.sungEnd)) push('           # ' + sub, faint);
      }
      if (cursorRow == null) { push('flowrics@now-playing:~$ ', green); cursorRow = rows.length - 1; }
      // fills from the top, then scrolls
      var topY = 56 * S.scale + lh / 2, avail = a.h - topY - 60 * S.scale;
      var want = Math.max(0, rows.length * lh - avail);
      st.scroll = st.scroll == null ? want : st.scroll + (want - st.scroll) * (1 - Math.pow(0.0005, S.dt / 1000));
      var jitter = S.audio.pulse > 0.6 ? (Math.random() - 0.5) * 6 * S.scale : 0;
      ctx.save();
      ctx.font = font;
      ctx.textBaseline = 'middle';
      ctx.textAlign = 'left';
      ctx.shadowBlur = 8 * S.scale;
      rows.forEach(function (r, k) {
        var y = topY + k * lh - st.scroll;
        if (y < -lh || y > a.h + lh) return;
        ctx.fillStyle = r.color;
        ctx.shadowColor = r.color;
        ctx.fillText(r.text, left + jitter, y);
        if (k === cursorRow && (Math.floor(S.now / 530) % 2 === 0 || S.playing && S.line && t < S.line.sungEnd)) {
          var cx = left + jitter + U.measure(r.text, font);
          ctx.fillRect(cx + 2, y - size * 0.55, size * 0.6, size * 1.1);
        }
      });
      ctx.restore();
      // scanlines and glass
      var scan = U.offscreen('scan', 4, 6);
      if (!st.scanPat) {
        var g = scan.getContext('2d');
        g.clearRect(0, 0, 4, 6);
        g.fillStyle = 'rgba(0,0,0,0.35)';
        g.fillRect(0, 0, 4, 2);
        st.scanPat = ctx.createPattern(scan, 'repeat');
      }
      ctx.fillStyle = st.scanPat;
      ctx.fillRect(0, 0, S.w, S.h);
      var vg = ctx.createRadialGradient(S.w / 2, S.h / 2, Math.min(S.w, S.h) * 0.35, S.w / 2, S.h / 2, Math.max(S.w, S.h) * 0.75);
      vg.addColorStop(0, 'rgba(0,0,0,0)');
      vg.addColorStop(1, 'rgba(0,0,0,0.6)');
      ctx.fillStyle = vg;
      ctx.fillRect(0, 0, S.w, S.h);
    },
  });

  // =====================================================================
  // 彈幕 Danmaku — the lyrics fly across as bullet comments, with an audience reacting.
  // =====================================================================
  var REACTIONS = ['好聽', '♪♪♪', '前奏神', '這段好讚', '+1', '副歌來了', '重複播放中', '淚目', '好喜歡這句', '🔥🔥🔥', '單曲循環', '哇', '好有感覺', '完美', '起雞皮疙瘩', '耳朵懷孕', '神曲', '再來一次', '跟著唱', '♡♡♡', '這聲音', '好chill'];
  F.register('danmaku', {
    label: '彈幕', desc: '歌詞變成飛過去的彈幕，還有觀眾一起刷', subtitle: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      ctx.fillStyle = 'rgba(0,0,0,0.25)';
      ctx.fillRect(0, 0, S.w, S.h);
      st.items = st.items || [];
      st.clock = (st.clock || 0) + (S.playing ? S.dt : 0);
      var laneH = 62 * S.scale, top = a.h * 0.2, lanes = Math.max(3, Math.floor((a.h * 0.86 - top) / laneH));
      function spawn(text, size, color, dur, weight) {
        var font = U.font(size, weight || 700);
        var w = U.measure(text, font);
        // the lane whose last comment is furthest along
        var best = 0, bestTail = Infinity, r = U.rand(st.clock | 0);
        for (var k = 0; k < lanes; k++) {
          var lane = (k + Math.floor(r() * lanes)) % lanes, tail = -Infinity;
          st.items.forEach(function (it) { if (it.lane === lane) tail = Math.max(tail, it.x0 - (st.clock - it.t0) * it.v + it.w); });
          if (tail < S.w - 60 * S.scale) { best = lane; bestTail = tail; break; }
          if (tail < bestTail) { bestTail = tail; best = lane; }
        }
        st.items.push({ text: text, font: font, size: size, color: color, w: w, lane: best, t0: st.clock, x0: S.w, v: (S.w + w) / dur });
      }
      if (S.idx !== st.lastIdx) {
        var fresh = S.line && !S.line.gap && S.line.text && t - S.line.start < 1500 && st.lastIdx != null;
        st.lastIdx = S.idx;
        if (fresh) {
          spawn(S.line.text, 46 * S.scale, '#fff', 8000, 800);
          var sub = U.subtitleText(S.line);
          if (sub) spawn(sub, 34 * S.scale, '#ffe066', 9000, 600);
          var words = S.line.text.split(/\s+/).filter(Boolean);
          if (Math.random() < 0.5 && words.length) {
            var wd = words[Math.floor(Math.random() * words.length)];
            spawn(U.hasCJK(wd) ? Array.from(wd).slice(-2).join('').repeat(3) : (wd + ' ').repeat(3), 30 * S.scale, U.hue(0, 90, 72), 7000, 600);
          }
        }
      }
      if (S.playing && st.clock > (st.nextReact || 0)) {
        st.nextReact = st.clock + 900 + Math.random() * 1600 * (1.2 - S.audio.power);
        var colors = ['#fff', '#fff', '#fff', U.hue(0, 90, 72), U.hue(120, 90, 72), '#ff8fb1', '#8fd4ff'];
        spawn(REACTIONS[Math.floor(Math.random() * REACTIONS.length)], (26 + Math.random() * 14) * S.scale, colors[Math.floor(Math.random() * colors.length)], 6000 + Math.random() * 4000, 600);
      }
      ctx.save();
      ctx.textBaseline = 'middle';
      ctx.textAlign = 'left';
      ctx.lineJoin = 'round';
      st.items = st.items.filter(function (it) { return it.x0 - (st.clock - it.t0) * it.v + it.w > 0; });
      st.items.forEach(function (it) {
        var x = it.x0 - (st.clock - it.t0) * it.v;
        var y = top + it.lane * laneH + laneH / 2;
        ctx.font = it.font;
        ctx.lineWidth = it.size * 0.14;
        ctx.strokeStyle = 'rgba(0,0,0,0.75)';
        ctx.strokeText(it.text, x, y);
        ctx.fillStyle = it.color;
        ctx.fillText(it.text, x, y);
      });
      // the current line pinned at the top
      var list = U.active(300);
      U.drawTitleCard(U.idle(list) * (st.items.length ? 0.6 : 1));
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'dm', 50 * S.scale, 900, 'sans', a.w * 0.9, 2, 1.2);
        var font = U.font(size, 900);
        var P = U.place(l, font, a.w * 0.9, size * 1.2, a.w / 2, a.h * 0.1, 'center');
        ctx.globalAlpha = U.smooth(p.age / 200) * (1 - p.exit);
        ctx.font = font;
        ctx.lineWidth = size * 0.14;
        P.tokens.forEach(function (tk) {
          ctx.strokeStyle = 'rgba(0,0,0,0.8)';
          ctx.strokeText(tk.text, tk.x, tk.y);
          fillToken(ctx, tk, tk.x, tk.y, t, '#fff', U.hue(0, 95, 66), size);
        });
      });
      ctx.restore();
    },
  });

  // =====================================================================
  // LED 看板 — a dot-matrix sign that scrolls along with the singing.
  // =====================================================================
  F.register('led', {
    label: 'LED 看板', desc: '點陣跑馬燈，跟著唱到的地方捲動', background: 'self', subtitle: 'self',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      ctx.fillStyle = '#050505';
      ctx.fillRect(0, 0, S.w, S.h);
      var ROWS = 16, FONT = U.font(15, 700);
      var pitch = Math.min(a.h * 0.3 / ROWS, a.w * 0.94 / 48);
      var cols = Math.floor(a.w * 0.94 / pitch);
      var boardW = cols * pitch, boardH = ROWS * pitch;
      var bx = (a.w - boardW) / 2, by = a.h * 0.42 - boardH / 2;
      var src = U.offscreen('ledsrc', cols, ROWS);
      var g = src.getContext('2d', { willReadFrequently: true });
      g.clearRect(0, 0, cols, ROWS);
      g.font = FONT;
      g.textBaseline = 'middle';
      g.textAlign = 'left';
      var l = S.line && !S.line.gap && S.line.text && t < U.lineEnd(S.line) ? S.line : null;
      if (l) {
        var full = l.text;
        var tw = U.measure(full, FONT);
        // where the singing is, in dots
        var sungX = 0;
        var x = 0;
        l.words.forEach(function (w) {
          var ww = U.measure(w.text, FONT);
          if (t >= w.start) sungX = x + ww * U.progress(w, t);
          x += ww;
        });
        if (st.ledLine !== l.start) { st.ledLine = l.start; st.off = -cols; }
        var target = tw <= cols ? -(cols - tw) / 2 : U.clamp(sungX - cols * 0.55, 0, tw - cols);
        st.off += (target - st.off) * (1 - Math.pow(0.02, S.dt / 1000));
        var xx = -st.off;
        l.words.forEach(function (w) {
          var ww = U.measure(w.text, FONT);
          var p = U.progress(w, t);
          g.fillStyle = p > 0 ? '#ffb02e' : '#7a4a10';
          g.fillText(w.text, xx, ROWS / 2 + 1);
          xx += ww;
        });
      } else {
        var msg = S.title ? '♪ ' + S.title + (S.artist ? ' — ' + S.artist : '') + ' ♪' : '♪ FLOWRICS ♪';
        var mw = U.measure(msg, FONT) + cols * 0.4;
        var sx = cols - ((S.now / 1000 * 18) % (mw + cols));
        g.fillStyle = '#ffb02e';
        g.fillText(msg, sx, ROWS / 2 + 1);
      }
      var data = g.getImageData(0, 0, cols, ROWS).data;
      // frame
      ctx.fillStyle = '#1b1b1d';
      roundRect(ctx, bx - pitch * 1.4, by - pitch * 1.4, boardW + pitch * 2.8, boardH + pitch * 2.8, pitch);
      ctx.fill();
      ctx.fillStyle = '#0b0705';
      ctx.fillRect(bx - pitch * 0.4, by - pitch * 0.4, boardW + pitch * 0.8, boardH + pitch * 0.8);
      var off = new Path2D(), dimP = new Path2D(), lit = new Path2D();
      var rad = pitch * 0.36;
      for (var r = 0; r < ROWS; r++) {
        for (var c = 0; c < cols; c++) {
          var i = (r * cols + c) * 4, al = data[i + 3];
          var px = bx + c * pitch + pitch / 2, py = by + r * pitch + pitch / 2;
          var path = al > 90 ? (data[i] > 200 ? lit : dimP) : off;
          path.moveTo(px + rad, py);
          path.arc(px, py, rad, 0, Math.PI * 2);
        }
      }
      ctx.fillStyle = '#1e1208';
      ctx.fill(off);
      ctx.fillStyle = '#8a5212';
      ctx.fill(dimP);
      ctx.save();
      ctx.shadowColor = '#ff9a1f';
      ctx.shadowBlur = pitch * (0.8 + S.audio.pulse);
      ctx.fillStyle = '#ffc04a';
      ctx.fill(lit);
      ctx.restore();
      // translation on a small green board below
      var sub = l ? U.subtitleText(l) : '';
      if (sub) {
        var R2 = 12, F2 = U.font(11, 600), p2 = pitch * 0.62;
        var cols2 = Math.floor(a.w * 0.94 / p2);
        var src2 = U.offscreen('ledsub', cols2, R2);
        var g2 = src2.getContext('2d', { willReadFrequently: true });
        g2.clearRect(0, 0, cols2, R2);
        g2.font = F2; g2.textBaseline = 'middle'; g2.fillStyle = '#5cff8a';
        var sw = U.measure(sub, F2);
        var sx2 = sw <= cols2 ? (cols2 - sw) / 2 : cols2 - ((S.now / 1000 * 14) % (sw + cols2));
        g2.fillText(sub, sx2, R2 / 2 + 1);
        var d2 = g2.getImageData(0, 0, cols2, R2).data;
        var b2x = (a.w - cols2 * p2) / 2, b2y = by + boardH + pitch * 3;
        ctx.fillStyle = '#0a0f0b';
        ctx.fillRect(b2x - p2 * 0.5, b2y - p2 * 0.5, cols2 * p2 + p2, R2 * p2 + p2);
        var on2 = new Path2D(), off2 = new Path2D(), rad2 = p2 * 0.36;
        for (var r2 = 0; r2 < R2; r2++) {
          for (var c2 = 0; c2 < cols2; c2++) {
            var px2 = b2x + c2 * p2 + p2 / 2, py2 = b2y + r2 * p2 + p2 / 2;
            var pth = d2[(r2 * cols2 + c2) * 4 + 3] > 90 ? on2 : off2;
            pth.moveTo(px2 + rad2, py2);
            pth.arc(px2, py2, rad2, 0, Math.PI * 2);
          }
        }
        ctx.fillStyle = '#0f2415'; ctx.fill(off2);
        ctx.save(); ctx.shadowColor = '#3dff7f'; ctx.shadowBlur = p2; ctx.fillStyle = '#7dffa3'; ctx.fill(on2); ctx.restore();
      }
    },
  });
})();
