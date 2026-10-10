// flowrics-fx-wild.js — Flowrics' over-the-top lyric effects (爆破、隕石、故障、漩渦、煙火、果凍、曲速、碎玻璃).
(function () {
  'use strict';
  var F = window.Flowrics, U = F.U, fillToken = F.fillToken;

  function decay(v, perSecond, dt) { return v * Math.pow(perSecond, dt / 1000); }
  /** Exit progress for one token, so a line leaves letter by letter. */
  function stagger(exit, i, n, spread) { spread = spread == null ? 0.5 : spread; return U.clamp(exit * (1 + spread) - (n > 1 ? i / (n - 1) : 0) * spread, 0, 1); }

  // =====================================================================
  // 爆破 Boom — every word slams down, the screen shakes, shards fly; lines leave in an explosion.
  // =====================================================================
  F.register('boom', {
    label: '爆破', wild: true, background: 'self', desc: '每個字重重砸下、畫面震動碎片四射，換句時整句炸開',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse;
      st.shake = decay(st.shake || 0, 0.003, S.dt);
      st.flash = Math.max(decay(st.flash || 0, 0.002, S.dt), pulse > 0.8 ? 0.18 : 0);
      st.hit = st.hit || {}; st.parts = st.parts || []; st.rings = st.rings || [];
      var mag = Math.min(1.6, st.shake) * 26 * S.scale;
      ctx.save();
      ctx.translate((Math.random() - 0.5) * mag, (Math.random() - 0.5) * mag);
      U.drawBackground(ctx, { pulse: pulse * 0.04 });
      ctx.fillStyle = 'rgba(0,0,0,0.35)';
      ctx.fillRect(-60, -60, S.w + 120, S.h + 120);
      var list = U.active(800);
      U.drawTitleCard(U.idle(list));
      var cy = a.h * 0.45;
      function burst(x, y, n, power) {
        for (var k = 0; k < n; k++) {
          var ang = Math.random() * Math.PI * 2, sp = (0.3 + Math.random() * 1.1) * power * S.scale;
          st.parts.push({ x: x, y: y, vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp - 0.4 * S.scale, r: Math.random() * 6, vr: (Math.random() - 0.5) * 0.02, s: (6 + Math.random() * 16) * S.scale, age: 0, life: 700 + Math.random() * 700, c: Math.random() < 0.5 ? '#fff' : S.palette.hlCss });
        }
        st.rings.push({ x: x, y: y, t0: S.now, big: power > 1.2 });
      }
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'boom', 96 * S.scale, 900, 'sans', a.w * 0.86, 3, 1.1);
        var font = U.font(size, 900);
        var P = U.place(l, font, a.w * 0.86, size * 1.1, a.w / 2, cy, 'center');
        if (p.leaving && !st.hit['x' + l.start]) {
          st.hit['x' + l.start] = 1;
          if (p.exit < 0.3) { st.shake += 1.2; st.flash = 0.5; burst(a.w / 2, cy, 40, 1.8); }
        }
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        ctx.lineJoin = 'round';
        var n = P.tokens.length;
        P.tokens.forEach(function (tk) {
          var r = U.rand(l.start + tk.i * 31);
          var rot = (r() - 0.5) * 0.14;
          var IN = 130, since = t - tk.start + 90;
          if (since <= 0) return;
          var e = Math.min(1, since / IN);
          var sc = 1 + (1 - U.inCubic(e)) * 3.4;
          var key = l.start + ':' + tk.i;
          if (since >= IN && !st.hit[key]) {
            st.hit[key] = 1;
            if (since < IN + 300) { st.shake = Math.min(1.6, st.shake + 0.55); burst(tk.x + tk.w / 2, tk.y, 12, 0.9); }
          }
          var dx = 0, dy = 0, rotE = 0, alpha = Math.min(1, e * 2.5);
          if (p.exit > 0) {
            var ex = U.outCubic(p.exit);
            var ang = Math.atan2(tk.y - cy + (r() - 0.5) * size * 2, tk.x + tk.w / 2 - a.w / 2 + (r() - 0.5) * size * 2);
            var sp = (0.5 + r() * 0.9) * a.w * 0.8;
            dx = Math.cos(ang) * sp * ex;
            dy = Math.sin(ang) * sp * ex + p.exit * p.exit * a.h * 0.7;
            rotE = (r() - 0.5) * 9 * p.exit;
            alpha *= 1 - U.smooth((p.exit - 0.35) / 0.65);
          }
          ctx.save();
          ctx.translate(tk.x + tk.w / 2 + dx, tk.y + dy);
          ctx.rotate(rot + rotE);
          ctx.scale(sc, sc);
          ctx.globalAlpha = alpha;
          ctx.lineWidth = size * 0.12;
          ctx.strokeStyle = 'rgba(0,0,0,0.85)';
          ctx.strokeText(tk.text, -tk.w / 2, 0);
          var sung = U.progress(tk, t);
          ctx.fillStyle = since < IN + 110 ? '#fff' : sung < 1 ? U.hue(0, 100, 68) : '#fff';
          ctx.fillText(tk.text, -tk.w / 2, 0);
          ctx.restore();
        });
      });
      // shockwaves
      st.rings = st.rings.filter(function (r) { return S.now - r.t0 < 600; });
      st.rings.forEach(function (r) {
        var k = (S.now - r.t0) / 600;
        ctx.globalAlpha = (1 - k) * 0.7;
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = (r.big ? 14 : 6) * S.scale * (1 - k);
        ctx.beginPath();
        ctx.arc(r.x, r.y, U.outCubic(k) * (r.big ? 600 : 160) * S.scale, 0, Math.PI * 2);
        ctx.stroke();
      });
      // shards
      ctx.globalAlpha = 1;
      for (var i = st.parts.length - 1; i >= 0; i--) {
        var q = st.parts[i];
        q.age += S.dt;
        if (q.age > q.life) { st.parts.splice(i, 1); continue; }
        q.vy += 0.0025 * S.scale * S.dt;
        q.x += q.vx * S.dt; q.y += q.vy * S.dt; q.r += q.vr * S.dt;
        ctx.save();
        ctx.globalAlpha = 1 - q.age / q.life;
        ctx.translate(q.x, q.y);
        ctx.rotate(q.r);
        ctx.fillStyle = q.c;
        ctx.beginPath();
        ctx.moveTo(-q.s / 2, -q.s / 3); ctx.lineTo(q.s / 2, 0); ctx.lineTo(-q.s / 4, q.s / 2);
        ctx.closePath(); ctx.fill();
        ctx.restore();
      }
      if (st.parts.length > 600) st.parts.splice(0, st.parts.length - 600);
      ctx.restore();
      if (st.flash > 0.01) {
        ctx.globalAlpha = st.flash;
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, S.w, S.h);
        ctx.globalAlpha = 1;
      }
    },
  });

  // =====================================================================
  // 隕石 Meteor — every character falls from the sky in flames and lands red-hot.
  // =====================================================================
  F.register('meteor', {
    label: '隕石', wild: true, desc: '每個字拖著火焰從天而降，落地燒紅再冷卻',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      st.shake = decay(st.shake || 0, 0.004, S.dt);
      st.hit = st.hit || {}; st.dust = st.dust || [];
      ctx.fillStyle = 'rgba(10,2,0,0.35)';
      ctx.fillRect(0, 0, S.w, S.h);
      var mag = Math.min(1.2, st.shake) * 14 * S.scale;
      ctx.save();
      ctx.translate((Math.random() - 0.5) * mag, (Math.random() - 0.5) * mag);
      var list = U.active(900);
      U.drawTitleCard(U.idle(list));
      var FALL = 520;
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'meteor', 84 * S.scale, 900, 'sans', a.w * 0.84, 3, 1.15);
        var font = U.font(size, 900);
        var P = U.place(l, font, a.w * 0.84, size * 1.15, a.w / 2, a.h * 0.46, 'center');
        var n = P.tokens.length;
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        P.tokens.forEach(function (tk) {
          if (!tk.text.trim()) return;
          var since = t - (tk.start - FALL);
          if (since <= 0) return;
          var r = U.rand(l.start + tk.i * 17);
          var tx = tk.x + tk.w / 2, ty = tk.y;
          var sx = tx + a.h * (0.45 + r() * 0.35), sy = ty - a.h * 1.1;
          var key = l.start + ':' + tk.i;
          if (since < FALL) {
            var k = Math.pow(since / FALL, 1.7);
            var x = U.lerp(sx, tx, k), y = U.lerp(sy, ty, k);
            var dx = sx - tx, dy = sy - ty, dl = Math.hypot(dx, dy);
            dx /= dl; dy /= dl;
            var len = size * (2 + k * 4);
            var nx = -dy, ny = dx, wd = size * 0.32;
            var g = ctx.createLinearGradient(x, y, x + dx * len, y + dy * len);
            g.addColorStop(0, 'rgba(255,240,200,0.95)');
            g.addColorStop(0.25, 'rgba(255,140,40,0.7)');
            g.addColorStop(1, 'rgba(120,20,0,0)');
            ctx.fillStyle = g;
            ctx.beginPath();
            ctx.moveTo(x + nx * wd, y + ny * wd);
            ctx.lineTo(x + dx * len, y + dy * len);
            ctx.lineTo(x - nx * wd, y - ny * wd);
            ctx.closePath();
            ctx.fill();
            ctx.save();
            ctx.shadowColor = '#ff7a1a';
            ctx.shadowBlur = size * 0.6;
            ctx.fillStyle = '#ffd38a';
            ctx.fillText(tk.text, x - tk.w / 2, y);
            ctx.restore();
            return;
          }
          if (!st.hit[key]) {
            st.hit[key] = 1;
            if (since < FALL + 300) {
              st.shake = Math.min(1.4, st.shake + 0.35);
              for (var d = 0; d < 14; d++) {
                var ang = -Math.PI * (0.1 + Math.random() * 0.8), sp = (0.1 + Math.random() * 0.5) * S.scale;
                st.dust.push({ x: tx, y: ty + size * 0.45, vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp, age: 0, life: 600 + Math.random() * 600, s: (3 + Math.random() * 6) * S.scale });
              }
            }
          }
          var heat = 1 - U.clamp((since - FALL) / 900, 0, 1);
          var ex = stagger(p.exit, tk.i, n, 0.6);
          var fy = U.inCubic(ex) * a.h * 0.8, rot = ex * (r() - 0.5) * 3;
          ctx.save();
          ctx.translate(tx, ty + fy);
          ctx.rotate(rot);
          ctx.globalAlpha = 1 - ex;
          if (heat > 0) { ctx.shadowColor = '#ff5a00'; ctx.shadowBlur = size * 0.5 * heat; }
          ctx.fillStyle = 'rgb(255,' + Math.round(255 - heat * 120) + ',' + Math.round(255 - heat * 200) + ')';
          ctx.fillText(tk.text, -tk.w / 2, 0);
          ctx.restore();
          // crater glow
          if (heat > 0.2 && ex === 0) {
            ctx.globalAlpha = heat * 0.5;
            var cg = ctx.createRadialGradient(tx, ty + size * 0.5, 0, tx, ty + size * 0.5, size * 0.8);
            cg.addColorStop(0, 'rgba(255,120,30,0.8)');
            cg.addColorStop(1, 'rgba(255,60,0,0)');
            ctx.fillStyle = cg;
            ctx.fillRect(tx - size, ty, size * 2, size);
            ctx.globalAlpha = 1;
          }
        });
      });
      for (var i = st.dust.length - 1; i >= 0; i--) {
        var q = st.dust[i];
        q.age += S.dt;
        if (q.age > q.life) { st.dust.splice(i, 1); continue; }
        q.vy += 0.0012 * S.scale * S.dt;
        q.x += q.vx * S.dt; q.y += q.vy * S.dt;
        ctx.globalAlpha = (1 - q.age / q.life) * 0.8;
        ctx.fillStyle = i % 2 ? '#ffb15c' : '#a58f80';
        ctx.fillRect(q.x, q.y, q.s, q.s);
      }
      ctx.restore();
    },
  });

  // =====================================================================
  // 故障 Glitch — scrambled text that decodes as it's sung, RGB split, torn slices.
  // =====================================================================
  var SCRAMBLE = Array.from('アイウエオカキクケコサシスセソｱｲｳｴｵ01#%&@$*+=/<>?ΣΔΨΩ▓▒░█');
  F.register('glitch', {
    label: '故障', wild: true, desc: '亂碼解碼成歌詞、RGB 分離、畫面撕裂',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse;
      ctx.fillStyle = 'rgba(0,0,0,0.4)';
      ctx.fillRect(0, 0, S.w, S.h);
      if ((pulse > 0.8 || Math.random() < S.dt / 4000) && S.now > (st.burstEnd || 0) + 1400) st.burstEnd = S.now + 120 + Math.random() * 160;
      var bursting = S.now < (st.burstEnd || 0);
      // tear the background
      if (bursting) {
        for (var k = 0; k < 4; k++) {
          var ty = Math.random() * S.h, th = (10 + Math.random() * 60) * S.scale;
          ctx.drawImage(ctx.canvas, 0, ty, S.w, th, (Math.random() - 0.5) * 80 * S.scale, ty, S.w, th);
        }
      }
      var list = U.active(380);
      var idle = U.idle(list);
      if (idle > 0.01) {
        ctx.save();
        if (bursting) ctx.translate((Math.random() - 0.5) * 20 * S.scale, 0);
        U.drawTitleCard(idle);
        ctx.restore();
      }
      var seq = Math.floor(S.now / 55);
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'glitch', 84 * S.scale, 900, 'sans', a.w * 0.86, 3, 1.15);
        var font = U.font(size, 900);
        var cy = a.h * 0.45;
        var P = U.place(l, font, a.w * 0.86, size * 1.15, a.w / 2, cy, 'center');
        var shown = [];
        P.tokens.forEach(function (tk) {
          var since = t - tk.start + 260;
          if (since <= 0) return;
          var txt = tk.text;
          if (since < 320) {
            txt = Array.from(tk.text).map(function (ch, ci) {
              if (!ch.trim()) return ch;
              return SCRAMBLE[U.hash(ch + seq + ci + tk.i) % SCRAMBLE.length];
            }).join('');
          }
          shown.push({ tk: tk, text: txt, sung: U.progress(tk, t) });
        });
        var bands = bursting || p.exit > 0 ? 8 : 1;
        var top = P.top - size * 0.3, hgt = P.height + size * 0.6;
        var sep = (3 + pulse * 16 + (bursting ? 12 : 0)) * S.scale;
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        for (var b = 0; b < bands; b++) {
          var y0 = top + hgt * b / bands, bh = hgt / bands + 1;
          var dx = bursting ? (U.hash(seq + ':' + b) % 1000 / 1000 - 0.5) * size * 1.4 : 0;
          dx += (b % 2 ? 1 : -1) * U.inCubic(p.exit) * a.w * 0.7;
          ctx.save();
          if (bands > 1) { ctx.beginPath(); ctx.rect(0, y0, S.w, bh); ctx.clip(); }
          ctx.translate(dx, 0);
          ctx.globalAlpha = 1 - p.exit;
          ctx.globalCompositeOperation = 'lighter';
          shown.forEach(function (s) {
            ctx.fillStyle = 'rgba(255,0,70,0.85)';
            ctx.fillText(s.text, s.tk.x - sep, s.tk.y);
            ctx.fillStyle = 'rgba(0,220,255,0.85)';
            ctx.fillText(s.text, s.tk.x + sep, s.tk.y);
          });
          ctx.globalCompositeOperation = 'source-over';
          shown.forEach(function (s) {
            ctx.fillStyle = s.sung > 0 && s.sung < 1 ? U.hue(0, 100, 75) : s.sung >= 1 ? '#fff' : 'rgba(255,255,255,0.7)';
            ctx.fillText(s.text, s.tk.x, s.tk.y);
          });
          ctx.restore();
        }
        if (bursting) {
          for (var k2 = 0; k2 < 7; k2++) {
            var rx = a.w * Math.random(), ry = top + hgt * Math.random();
            ctx.fillStyle = ['#fff', '#000', S.palette.hlCss, '#ff0046', '#00dcff'][k2 % 5];
            ctx.globalAlpha = 0.7;
            ctx.fillRect(rx, ry, (20 + Math.random() * 160) * S.scale, (4 + Math.random() * 18) * S.scale);
          }
          ctx.globalAlpha = 1;
        }
      });
      // rolling scanline
      var sy = (S.now / 6) % S.h;
      ctx.fillStyle = 'rgba(255,255,255,0.05)';
      ctx.fillRect(0, sy, S.w, 40 * S.scale);
    },
  });

  // =====================================================================
  // 漩渦 Vortex — characters spiral out of a black hole and get sucked back in.
  // =====================================================================
  F.register('vortex', {
    label: '漩渦', wild: true, desc: '字從黑洞旋轉甩出來，換句時被吸回去',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse;
      var cx = a.w / 2, cy = a.h * 0.45;
      ctx.fillStyle = 'rgba(0,0,0,0.4)';
      ctx.fillRect(0, 0, S.w, S.h);
      st.rot = (st.rot || 0) + S.dt * (0.0004 + S.audio.power * 0.002);
      var maxR = Math.hypot(a.w, a.h) * 0.6;
      ctx.save();
      ctx.lineCap = 'round';
      for (var arm = 0; arm < 6; arm++) {
        ctx.beginPath();
        for (var i = 0; i <= 50; i++) {
          var rr = Math.pow(i / 50, 1.6) * maxR;
          var ang = st.rot + arm * Math.PI / 3 + Math.log(1 + rr / (40 * S.scale)) * 1.4;
          var x = cx + Math.cos(ang) * rr, y = cy + Math.sin(ang) * rr * 0.9;
          if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y);
        }
        ctx.strokeStyle = U.hue(arm * 20, 90, 65, 0.12 + pulse * 0.1);
        ctx.lineWidth = (10 + arm % 2 * 14) * S.scale;
        ctx.stroke();
      }
      // the hole
      var hr = 46 * S.scale * (1 + pulse * 0.25);
      var hg = ctx.createRadialGradient(cx, cy, hr * 0.6, cx, cy, hr * 2.4);
      hg.addColorStop(0, 'rgba(0,0,0,1)');
      hg.addColorStop(0.35, U.hue(0, 100, 70, 0.55));
      hg.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = hg;
      ctx.beginPath(); ctx.arc(cx, cy, hr * 2.4, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = '#000';
      ctx.beginPath(); ctx.arc(cx, cy, hr * 0.7, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
      var list = U.active(900);
      U.drawTitleCard(U.idle(list));
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'vortex', 80 * S.scale, 900, 'sans', a.w * 0.84, 3, 1.15);
        var font = U.font(size, 900);
        var P = U.place(l, font, a.w * 0.84, size * 1.15, cx, cy, 'center');
        var n = P.tokens.length;
        ctx.save();
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        P.tokens.forEach(function (tk) {
          var e = U.outCubic(U.clamp((t - tk.start + 520) / 760, 0, 1));
          if (e <= 0) return;
          var vx = tk.x + tk.w / 2 - cx, vy = tk.y - cy;
          var ang = -(1 - e) * Math.PI * 2.4, m = e, sc = 0.2 + 0.8 * e, alpha = e;
          if (p.exit > 0) {
            var x = U.inCubic(stagger(p.exit, n - 1 - tk.i, n, 0.5));
            ang += x * Math.PI * 3;
            m *= 1 - x; sc *= 1 - x; alpha *= 1 - x;
          }
          if (alpha <= 0.01) return;
          var c = Math.cos(ang), s = Math.sin(ang);
          ctx.save();
          ctx.translate(cx + (vx * c - vy * s) * m, cy + (vx * s + vy * c) * m);
          ctx.rotate(ang);
          ctx.scale(sc, sc);
          ctx.globalAlpha = alpha;
          var sung = U.progress(tk, t);
          ctx.shadowColor = S.palette.hlCss;
          ctx.shadowBlur = sung > 0 && sung < 1 ? size * 0.5 : 0;
          ctx.fillStyle = sung > 0 ? '#fff' : 'rgba(255,255,255,0.6)';
          ctx.fillText(tk.text, -tk.w / 2, 0);
          ctx.restore();
        });
        ctx.restore();
      });
    },
  });

  // =====================================================================
  // 煙火 Fireworks — a rocket for every word; its sparks gather into the characters.
  // =====================================================================
  F.register('fireworks', {
    label: '煙火', wild: true, background: 'self', desc: '每個字一發煙火，火花在空中聚成歌詞',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      U.drawBackground(ctx);
      ctx.fillStyle = 'rgba(0,0,10,0.62)';
      ctx.fillRect(0, 0, S.w, S.h);
      st.fw = st.fw || {};
      var list = U.active(1300);
      U.drawTitleCard(U.idle(list));
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'fw', 96 * S.scale, 800, 'sans', a.w * 0.84, 3, 1.18);
        var font = U.font(size, 800);
        var P = U.place(l, font, a.w * 0.84, size * 1.18, a.w / 2, a.h * 0.42, 'center');
        var step = Math.max(3, Math.round(size / 15));
        var ps = Math.max(1.5, step * 0.6);
        var ex = p.exit;
        ctx.save();
        ctx.globalCompositeOperation = 'lighter';
        P.tokens.forEach(function (tk) {
          if (!tk.text.trim()) return;
          var launch = tk.start - 650, burstT = tk.start - 200;
          if (t < launch) return;
          var key = l.start + ':' + tk.i + ':' + size;
          var d = st.fw[key];
          if (!d) {
            var r = U.rand(l.start + tk.i * 977);
            var pts = U.glyphPoints(tk.text, font, step);
            var dirs = new Float32Array(pts.length * 3);
            for (var j = 0; j < pts.length; j++) { dirs[j * 3] = r() * Math.PI * 2; dirs[j * 3 + 1] = 0.5 + r() * 1.3; dirs[j * 3 + 2] = r(); }
            var sparks = [];
            for (var k = 0; k < 26; k++) { var ang = r() * Math.PI * 2, sp = 0.15 + r() * 0.35; sparks.push([Math.cos(ang) * sp, Math.sin(ang) * sp]); }
            d = st.fw[key] = { pts: pts, dirs: dirs, sparks: sparks, hue: S.palette.hue + (r() - 0.5) * 140, x0: (r() - 0.5) * a.w * 0.25 };
          }
          var cx = tk.x + tk.w / 2, cy = tk.y;
          var col = U.hsl(d.hue, 100, 66);
          if (t < burstT) {
            // the rocket going up
            var k2 = U.outCubic((t - launch) / 450);
            ctx.fillStyle = col;
            for (var j2 = 0; j2 < 8; j2++) {
              var kk = Math.max(0, k2 - j2 * 0.025);
              ctx.globalAlpha = 1 - j2 / 8;
              var rx = U.lerp(cx + d.x0, cx, kk), ry = U.lerp(a.h + 20, cy, kk);
              ctx.fillRect(rx - 2 * S.scale, ry, 4 * S.scale, 4 * S.scale);
            }
            return;
          }
          var b = t - burstT;
          // flash
          if (b < 300) {
            ctx.globalAlpha = (1 - b / 300) * 0.8;
            var fg = ctx.createRadialGradient(cx, cy, 0, cx, cy, size * 1.6);
            fg.addColorStop(0, '#fff'); fg.addColorStop(0.3, col); fg.addColorStop(1, 'rgba(0,0,0,0)');
            ctx.fillStyle = fg;
            ctx.fillRect(cx - size * 1.6, cy - size * 1.6, size * 3.2, size * 3.2);
          }
          // falling sparks
          if (b < 1500) {
            ctx.fillStyle = col;
            d.sparks.forEach(function (v) {
              var sx = cx + v[0] * b * S.scale * 1.6, sy = cy + v[1] * b * S.scale * 1.6 + 0.0004 * b * b * S.scale;
              ctx.globalAlpha = (1 - b / 1500) * (1 - ex);
              ctx.fillRect(sx, sy, 3 * S.scale, 3 * S.scale);
            });
          }
          // the glyph sparks
          var pts = d.pts, dirs = d.dirs;
          ctx.fillStyle = col;
          var base = tk.y + size * 0.35;
          for (var j3 = 0; j3 < pts.length; j3++) {
            var ang3 = dirs[j3 * 3], rad = dirs[j3 * 3 + 1] * size, rnd = dirs[j3 * 3 + 2];
            var spx = cx + Math.cos(ang3) * rad, spy = cy + Math.sin(ang3) * rad;
            var tx = tk.x + pts[j3][0], ty = base + pts[j3][1];
            var x, y;
            if (b < 260) { var k3 = U.outCubic(b / 260); x = U.lerp(cx, spx, k3); y = U.lerp(cy, spy, k3); }
            else { var k4 = U.smooth((b - 260) / (700 + rnd * 300)); x = U.lerp(spx, tx, k4); y = U.lerp(spy, ty, k4); }
            if (ex > 0) { y += ex * ex * a.h * (0.3 + rnd * 0.5); x += (rnd - 0.5) * ex * size * 2; }
            ctx.globalAlpha = (0.55 + 0.45 * Math.sin(S.now / 70 + j3)) * (1 - ex);
            ctx.fillRect(x, y, ps, ps);
          }
          // the crisp character settles in
          if (b > 900 && ex < 1) {
            ctx.globalCompositeOperation = 'source-over';
            ctx.globalAlpha = U.smooth((b - 900) / 500) * (1 - Math.min(1, ex * 3)) * 0.92;
            ctx.font = font;
            ctx.textBaseline = 'middle';
            ctx.textAlign = 'left';
            ctx.shadowColor = col;
            ctx.shadowBlur = size * 0.4;
            ctx.fillStyle = '#fff';
            ctx.fillText(tk.text, tk.x, tk.y);
            ctx.shadowBlur = 0;
            ctx.globalCompositeOperation = 'lighter';
          }
        });
        ctx.restore();
      });
      // forget old lines' particle data
      var keys = Object.keys(st.fw);
      if (keys.length > 120) keys.slice(0, keys.length - 60).forEach(function (k) { delete st.fw[k]; });
    },
  });

  // =====================================================================
  // 果凍 Jelly — candy-coloured letters drop in, squash, wobble to the beat and hop away.
  // =====================================================================
  F.register('jelly', {
    label: '果凍', wild: true, background: 'self', desc: '糖果色的字彈跳落下、跟著節拍抖動，換句時跳走',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t, pulse = S.audio.pulse, hue = S.palette.hue;
      var bg = ctx.createLinearGradient(0, 0, 0, S.h);
      bg.addColorStop(0, U.hsl(hue, 75, 84));
      bg.addColorStop(1, U.hsl(hue + 50, 75, 72));
      ctx.fillStyle = bg;
      ctx.fillRect(0, 0, S.w, S.h);
      // soft blobs
      for (var k = 0; k < 7; k++) {
        var bx = S.w * (0.5 + 0.45 * Math.sin(S.now / (5000 + k * 900) + k * 2));
        var by = S.h * (0.5 + 0.4 * Math.cos(S.now / (6000 + k * 700) + k));
        var br = Math.min(S.w, S.h) * (0.18 + 0.05 * Math.sin(k)) * (1 + pulse * 0.08);
        ctx.fillStyle = U.hsl(hue + k * 45, 85, 78, 0.55);
        ctx.beginPath(); ctx.arc(bx, by, br, 0, Math.PI * 2); ctx.fill();
      }
      var list = U.active(900);
      var idle = U.idle(list);
      if (idle > 0.01 && S.title) {
        ctx.save();
        ctx.globalAlpha = idle;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.lineJoin = 'round';
        ctx.font = U.font(72 * S.scale, 900, 'round');
        ctx.lineWidth = 12 * S.scale; ctx.strokeStyle = '#fff';
        var wob = 1 + Math.sin(S.now / 300) * 0.03;
        ctx.translate(S.w / 2, a.h * 0.44); ctx.scale(1 / wob, wob);
        ctx.strokeText(S.title, 0, 0, S.w * 0.84);
        ctx.fillStyle = U.hsl(hue + 180, 80, 55);
        ctx.fillText(S.title, 0, 0, S.w * 0.84);
        ctx.restore();
      }
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'jelly', 92 * S.scale, 900, 'round', a.w * 0.84, 3, 1.2);
        var font = U.font(size, 900, 'round');
        var P = U.place(l, font, a.w * 0.84, size * 1.2, a.w / 2, a.h * 0.45, 'center');
        var n = P.tokens.length;
        ctx.save();
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        ctx.lineJoin = 'round';
        P.tokens.forEach(function (tk) {
          if (!tk.text.trim()) return;
          var age = t - tk.start + 150;
          if (age <= 0) return;
          var y = -size * 3 * Math.exp(-age / 120) * Math.cos(age / 75);
          var sq = 0.38 * Math.exp(-age / 170) * Math.sin(age / 48 + 1);
          var sy = 1 - sq, sx = 1 + sq * 0.8;
          var wob = pulse * 0.22 * Math.sin(S.now / 55 + tk.i * 1.3);
          sy *= 1 + wob; sx *= 1 - wob * 0.6;
          var rot = Math.sin(S.now / 700 + tk.i) * 0.05;
          var e2 = stagger(p.exit, tk.i, n, 0.6);
          if (e2 > 0) { y += -Math.sin(e2 * Math.PI) * size * 1.4 + e2 * e2 * a.h * 0.9; rot += e2 * (tk.i % 2 ? 3 : -3); }
          var foot = size * 0.45;
          ctx.save();
          ctx.translate(tk.x + tk.w / 2, tk.y + foot + y);
          ctx.rotate(rot);
          ctx.scale(sx, sy);
          ctx.translate(0, -foot);
          ctx.globalAlpha = Math.min(1, age / 80) * (1 - U.smooth((e2 - 0.7) / 0.3));
          var c = U.hsl(hue + tk.i * 28, 85, 62);
          ctx.fillStyle = U.hsl(hue + tk.i * 28, 70, 40, 0.45);
          ctx.fillText(tk.text, -tk.w / 2, size * 0.08);
          ctx.lineWidth = size * 0.16;
          ctx.strokeStyle = '#fff';
          ctx.strokeText(tk.text, -tk.w / 2, 0);
          ctx.fillStyle = c;
          ctx.fillText(tk.text, -tk.w / 2, 0);
          // shine
          ctx.save();
          ctx.beginPath();
          ctx.rect(-tk.w / 2, -size * 0.6, tk.w, size * 0.38);
          ctx.clip();
          ctx.fillStyle = 'rgba(255,255,255,0.35)';
          ctx.fillText(tk.text, -tk.w / 2, 0);
          ctx.restore();
          ctx.restore();
        });
        ctx.restore();
      });
      // subtitles in dark text, since the background is light
      var sub = S.line && !S.line.gap ? U.subtitleText(S.line) : '';
      if (sub) {
        ctx.save();
        ctx.font = U.font(28 * S.scale, 700, 'round');
        ctx.textAlign = 'center';
        ctx.fillStyle = U.hsl(hue + 200, 50, 25, 0.85);
        ctx.globalAlpha = U.smooth((t - S.line.start) / 300) * (1 - U.smooth((t - U.lineEnd(S.line)) / 300));
        ctx.fillText(sub, S.w / 2, a.h - 48 * S.scale, S.w * 0.9);
        ctx.restore();
      }
    },
    subtitle: 'self',
  });

  // =====================================================================
  // 曲速 Warp — a jump to hyperspace on every line; words fly in from the vanishing point.
  // =====================================================================
  F.register('warp', {
    label: '曲速', wild: true, background: 'self', desc: '每句都是一次曲速跳躍，字從星空深處飛到眼前',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      var cx = a.w / 2, cy = a.h * 0.45;
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, S.w, S.h);
      U.drawBackground(ctx, { alpha: 0.22 });
      if (!st.stars) {
        st.stars = [];
        for (var i = 0; i < 380; i++) st.stars.push({ x: Math.random() * 2 - 1, y: Math.random() * 2 - 1, z: Math.random() });
      }
      if (S.idx !== st.lastIdx) { if (st.lastIdx != null && S.line && !S.line.gap) st.boost = 1; st.lastIdx = S.idx; }
      st.boost = decay(st.boost || 0, 0.08, S.dt);
      var speed = (0.12 + S.audio.power * 0.5 + st.boost * 2.4) * (S.playing ? 1 : 0.15) * S.dt / 1000;
      var fov = Math.max(a.w, a.h) * 0.5;
      ctx.save();
      ctx.lineCap = 'round';
      var tint = S.palette.hl;
      st.stars.forEach(function (s) {
        var pz = s.z;
        s.z -= speed;
        if (s.z <= 0.02) { s.x = Math.random() * 2 - 1; s.y = Math.random() * 2 - 1; s.z = 1; pz = 1; return; }
        var x1 = cx + s.x / s.z * fov * 0.4, y1 = cy + s.y / s.z * fov * 0.4;
        var tail = Math.min(1, pz + speed * 3 + 0.004);
        var x0 = cx + s.x / tail * fov * 0.4, y0 = cy + s.y / tail * fov * 0.4;
        var br = 1 - s.z;
        ctx.strokeStyle = 'rgba(' + Math.round(200 + tint[0] * 0.2) + ',' + Math.round(200 + tint[1] * 0.2) + ',255,' + br.toFixed(2) + ')';
        ctx.lineWidth = Math.max(1, br * 3 * S.scale);
        ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke();
      });
      ctx.restore();
      // a flash at the jump
      if (st.boost > 0.6) {
        ctx.globalAlpha = (st.boost - 0.6) * 0.6;
        ctx.fillStyle = '#cfe3ff';
        ctx.fillRect(0, 0, S.w, S.h);
        ctx.globalAlpha = 1;
      }
      var list = U.active(500);
      U.drawTitleCard(U.idle(list));
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'warp', 84 * S.scale, 900, 'sans', a.w * 0.84, 3, 1.15);
        var font = U.font(size, 900);
        var P = U.place(l, font, a.w * 0.84, size * 1.15, cx, cy, 'center');
        ctx.save();
        ctx.font = font;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';
        P.tokens.forEach(function (tk) {
          var arr = t - (tk.start - 700);
          if (arr <= 0) return;
          var k = U.outCubic(arr / 700);
          var z = 1 + 9 * (1 - k);
          var alpha = k;
          if (p.exit > 0) { var x = U.inCubic(p.exit); z = Math.max(0.12, 1 - 0.88 * x); alpha *= 1 - U.smooth((p.exit - 0.3) / 0.7); }
          var sc = 1 / z;
          ctx.save();
          ctx.translate(cx + (tk.x + tk.w / 2 - cx) * sc, cy + (tk.y - cy) * sc);
          ctx.scale(sc, sc);
          ctx.globalAlpha = alpha;
          var sung = U.progress(tk, t);
          ctx.shadowColor = '#7fb2ff';
          ctx.shadowBlur = size * (sung > 0 && sung < 1 ? 0.6 : 0.25);
          ctx.fillStyle = sung > 0 ? '#fff' : 'rgba(220,235,255,0.7)';
          ctx.fillText(tk.text, -tk.w / 2, 0);
          ctx.restore();
        });
        ctx.restore();
      });
    },
  });

  // =====================================================================
  // 碎玻璃 Shatter — each line is a pane of glass: it assembles, then smashes and falls.
  // =====================================================================
  function pieces(st, key, x0, x1, h, size, seed) {
    st.pc = st.pc || {};
    if (st.pc[key]) return st.pc[key];
    var r = U.rand(seed);
    var cols = U.clamp(Math.round((x1 - x0) / (size * 0.8)), 3, 16), rows = 3;
    var cw = (x1 - x0) / cols, rh = h / rows, v = [];
    for (var i = 0; i <= cols; i++) {
      v[i] = [];
      for (var j = 0; j <= rows; j++) {
        var jx = i > 0 && i < cols ? (r() - 0.5) * cw * 0.7 : 0;
        var jy = j > 0 && j < rows ? (r() - 0.5) * rh * 0.7 : 0;
        v[i][j] = [x0 + i * cw + jx, j * rh + jy];
      }
    }
    var ix = x0 + (x1 - x0) * (0.3 + r() * 0.4), iy = h * (0.3 + r() * 0.4);
    var out = [];
    for (var i2 = 0; i2 < cols; i2++) {
      for (var j2 = 0; j2 < rows; j2++) {
        var A = v[i2][j2], B = v[i2 + 1][j2], C = v[i2 + 1][j2 + 1], D = v[i2][j2 + 1];
        var tris = r() < 0.5 ? [[A, B, C], [A, C, D]] : [[A, B, D], [B, C, D]];
        tris.forEach(function (tr) {
          var cx = (tr[0][0] + tr[1][0] + tr[2][0]) / 3, cy = (tr[0][1] + tr[1][1] + tr[2][1]) / 3;
          var dx = cx - ix, dy = cy - iy, dl = Math.hypot(dx, dy) || 1;
          var sp = (300 + r() * 900) * U.clamp(size / 80, 0.4, 2);
          out.push({ p: tr, cx: cx, cy: cy, vx: dx / dl * sp, vy: dy / dl * sp - 350 * size / 80, vr: (r() - 0.5) * 9 });
        });
      }
    }
    st.pc[key] = { tris: out, ix: ix, iy: iy };
    return st.pc[key];
  }
  F.register('shatter', {
    label: '碎玻璃', wild: true, desc: '每句是一片玻璃，碎片拼起來，換句時砸碎落下',
    draw: function (ctx, S, U) {
      var a = U.area(), st = S.store, t = S.t;
      ctx.fillStyle = 'rgba(0,0,0,0.3)';
      ctx.fillRect(0, 0, S.w, S.h);
      var list = U.active(1100);
      U.drawTitleCard(U.idle(list));
      list.forEach(function (p) {
        var l = p.line;
        var size = U.fit(l, 'shatter', 84 * S.scale, 900, 'sans', a.w * 0.84, 3, 1.15);
        var font = U.font(size, 900);
        var L = U.layout(l, font, a.w * 0.84, size * 1.15);
        var oh = Math.ceil(L.height + size * 0.6);
        var off = U.offscreen('sh' + (l.index % 2), a.w, oh);
        var g = off.getContext('2d');
        g.clearRect(0, 0, off.width, oh);
        var P = U.place(l, font, a.w * 0.84, size * 1.15, a.w / 2, oh / 2, 'center');
        var minX = a.w, maxX = 0;
        P.tokens.forEach(function (tk) { minX = Math.min(minX, tk.x); maxX = Math.max(maxX, tk.x + tk.w); });
        // glass pane
        var gx = minX - size * 0.35, gw = maxX - minX + size * 0.7;
        var gg = g.createLinearGradient(gx, 0, gx + gw, oh);
        gg.addColorStop(0, 'rgba(255,255,255,0.10)');
        gg.addColorStop(0.45, 'rgba(255,255,255,0.03)');
        gg.addColorStop(0.5, 'rgba(255,255,255,0.14)');
        gg.addColorStop(1, 'rgba(255,255,255,0.04)');
        g.fillStyle = gg;
        g.fillRect(gx, 0, gw, oh);
        g.strokeStyle = 'rgba(255,255,255,0.35)';
        g.lineWidth = Math.max(1, 1.5 * S.scale);
        g.strokeRect(gx + 0.5, 0.5, gw - 1, oh - 1);
        g.font = font;
        g.textBaseline = 'middle';
        g.textAlign = 'left';
        P.tokens.forEach(function (tk) { fillToken(g, tk, tk.x, tk.y, t, 'rgba(255,255,255,0.45)', '#fff', size); });
        var y0 = a.h * 0.45 - oh / 2;
        var pc = pieces(st, l.start + ':' + Math.round(size) + ':' + a.w, gx, gx + gw, oh, size, l.start + 3);
        var enter = U.clamp((p.age - 380) / 600, 0, 1);
        if (enter <= 0) return;
        if (enter >= 1 && !p.leaving) { ctx.drawImage(off, 0, y0); return; }
        var sec, gravity = 2600 * S.scale, alpha = 1, crack = 0;
        if (p.leaving) {
          crack = U.clamp(p.exit / 0.12, 0, 1);
          sec = Math.max(0, (p.exit - 0.12) / 0.88) * 1.1;
          alpha = 1 - U.smooth((p.exit - 0.55) / 0.45);
        } else {
          sec = (1 - U.outCubic(enter)) * 0.25;
          gravity = 0;
          alpha = U.smooth(enter * 2);
        }
        ctx.save();
        ctx.globalAlpha = alpha;
        pc.tris.forEach(function (tr) {
          var dx = tr.vx * sec, dy = tr.vy * sec + 0.5 * gravity * sec * sec;
          ctx.save();
          ctx.translate(tr.cx + dx, y0 + tr.cy + dy);
          ctx.rotate(tr.vr * sec);
          ctx.translate(-tr.cx, -tr.cy);
          ctx.beginPath();
          ctx.moveTo(tr.p[0][0], tr.p[0][1]); ctx.lineTo(tr.p[1][0], tr.p[1][1]); ctx.lineTo(tr.p[2][0], tr.p[2][1]);
          ctx.closePath();
          ctx.save();
          ctx.clip();
          ctx.drawImage(off, 0, 0);
          ctx.restore();
          if (crack > 0 || sec > 0) {
            ctx.strokeStyle = 'rgba(255,255,255,' + (0.6 * Math.max(crack, 0.4)) + ')';
            ctx.lineWidth = Math.max(1, S.scale);
            ctx.stroke();
          }
          ctx.restore();
        });
        // impact flash
        if (p.leaving && p.exit < 0.2) {
          ctx.globalAlpha = 1 - p.exit / 0.2;
          var fg = ctx.createRadialGradient(pc.ix, y0 + pc.iy, 0, pc.ix, y0 + pc.iy, size * 2);
          fg.addColorStop(0, 'rgba(255,255,255,0.9)');
          fg.addColorStop(1, 'rgba(255,255,255,0)');
          ctx.fillStyle = fg;
          ctx.fillRect(pc.ix - size * 2, y0 + pc.iy - size * 2, size * 4, size * 4);
        }
        ctx.restore();
      });
      var keys = Object.keys(st.pc || {});
      if (keys.length > 20) keys.slice(0, keys.length - 6).forEach(function (k) { delete st.pc[k]; });
    },
  });
})();
