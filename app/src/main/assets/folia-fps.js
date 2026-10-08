// folia-fps.js
// Small frame-rate readout, injected only when "顯示 FPS" is on.
// It starts after the page has loaded, so it goes through Folia's own frame-rate limiter and
// counts the frames Folia actually draws: what you see, not what the screen could show.
(function () {
  'use strict';
  var config = window.__FOLIA_ANDROID_CONFIG__ || {};
  var displayHz = Number(config.displayHz) || 0;
  window.__foliaSetDisplayHz = function (hz) { displayHz = Number(hz) || displayHz; };

  function start() {
    var u = Number(config.cssPerDp) || 1; // CSS px per Android dp
    var box = document.createElement('div');
    box.setAttribute('aria-hidden', 'true');
    box.style.cssText = [
      'position:fixed', 'top:' + 10 * u + 'px', 'left:' + 10 * u + 'px', 'z-index:2147483647',
      'pointer-events:none', 'font:600 ' + 12 * u + 'px/1.35 ui-monospace,Menlo,Consolas,monospace',
      'color:#fff', 'background:rgba(0,0,0,.55)', 'padding:' + 4 * u + 'px ' + 8 * u + 'px',
      'border-radius:' + 6 * u + 'px', 'white-space:pre',
    ].join(';');
    document.body.appendChild(box);

    var frames = 0;
    var worst = 0;
    var last = 0;
    var windowStart = 0;
    function tick(now) {
      if (last) {
        var gap = now - last;
        if (gap > worst) worst = gap;
      } else {
        windowStart = now;
      }
      last = now;
      frames++;
      var elapsed = now - windowStart;
      if (elapsed >= 1000) {
        var fps = Math.round((frames - 1) * 1000 / elapsed);
        var cap = config.frameCap && config.frameCap !== 'off' ? ' · 上限 ' + config.frameCap : '';
        box.textContent = fps + ' FPS  最慢 ' + Math.round(worst) + 'ms\n螢幕 ' + Math.round(displayHz) + 'Hz' + cap;
        box.style.color = fps >= Math.min(displayHz || 60, Number(config.frameCap) || 999) * 0.9 ? '#9eff9e' : fps >= 45 ? '#ffe08a' : '#ff8a8a';
        frames = 1;
        worst = 0;
        windowStart = now;
      }
      window.requestAnimationFrame(tick);
    }
    window.requestAnimationFrame(tick);
  }

  if (document.readyState === 'complete') setTimeout(start, 0);
  else window.addEventListener('load', function () { setTimeout(start, 0); });
})();
