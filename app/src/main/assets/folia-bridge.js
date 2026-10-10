// folia-bridge.js
// Injected into Folia's index.html before any other script.
// Folia's "Now Playing" OBS surface opens ws://<host>/api/ws/lyric and listens for
// Track / Lyric / PlayerPauseState / PlayerProgress events. Instead of running a real
// WebSocket server on the phone, this shim hands Folia a fake socket whose messages
// come straight from the Android side (MediaHub -> evaluateJavascript -> __foliaPush).
(function () {
  'use strict';
  var NativeWebSocket = window.WebSocket;
  var sockets = [];

  function BridgeSocket(url) {
    var target = document.createDocumentFragment();
    this._target = target;
    this.url = String(url);
    this.protocol = '';
    this.extensions = '';
    this.binaryType = 'blob';
    this.bufferedAmount = 0;
    this.readyState = 0;
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.onclose = null;
    sockets.push(this);
    var self = this;
    setTimeout(function () {
      if (self.readyState !== 0) return;
      self.readyState = 1;
      self._emit('open', new Event('open'));
      try {
        if (window.FoliaNative && window.FoliaNative.requestSnapshot) window.FoliaNative.requestSnapshot();
      } catch (e) { /* ignore */ }
    }, 0);
  }

  BridgeSocket.CONNECTING = 0;
  BridgeSocket.OPEN = 1;
  BridgeSocket.CLOSING = 2;
  BridgeSocket.CLOSED = 3;
  BridgeSocket.prototype.CONNECTING = 0;
  BridgeSocket.prototype.OPEN = 1;
  BridgeSocket.prototype.CLOSING = 2;
  BridgeSocket.prototype.CLOSED = 3;

  BridgeSocket.prototype._emit = function (type, event) {
    var handler = this['on' + type];
    if (typeof handler === 'function') {
      try { handler.call(this, event); } catch (e) { setTimeout(function () { throw e; }); }
    }
    this._target.dispatchEvent(event);
  };
  BridgeSocket.prototype.addEventListener = function (type, fn, opts) { this._target.addEventListener(type, fn, opts); };
  BridgeSocket.prototype.removeEventListener = function (type, fn, opts) { this._target.removeEventListener(type, fn, opts); };
  BridgeSocket.prototype.send = function () { /* Folia never sends on this channel */ };
  BridgeSocket.prototype.close = function () {
    if (this.readyState === 3) return;
    this.readyState = 3;
    var index = sockets.indexOf(this);
    if (index >= 0) sockets.splice(index, 1);
    var closeEvent;
    try { closeEvent = new CloseEvent('close', { code: 1000, wasClean: true }); } catch (e) { closeEvent = new Event('close'); }
    this._emit('close', closeEvent);
  };

  function PatchedWebSocket(url, protocols) {
    if (String(url).indexOf('/api/ws/lyric') !== -1) return new BridgeSocket(url);
    return protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
  }
  PatchedWebSocket.prototype = NativeWebSocket.prototype;
  PatchedWebSocket.CONNECTING = 0;
  PatchedWebSocket.OPEN = 1;
  PatchedWebSocket.CLOSING = 2;
  PatchedWebSocket.CLOSED = 3;
  window.WebSocket = PatchedWebSocket;

  // Called by Android with one JSON envelope: {"event": "...", "data": {...}}
  window.__foliaPush = function (json) {
    for (var i = 0; i < sockets.length; i++) {
      var socket = sockets[i];
      if (socket.readyState !== 1) continue;
      socket._emit('message', new MessageEvent('message', { data: json }));
    }
  };
  // Folia pins its subtitle line (translation / romaji) to the bottom of the screen, where our
  // info card sits. While the card is open, lift that line above it. Folia sets `bottom` as an
  // inline style from its animation library; a stylesheet rule with !important wins over it.
  window.__fcSetBottomInset = function (px) {
    var id = 'fc-bottom-inset';
    var style = document.getElementById(id);
    if (!style) {
      style = document.createElement('style');
      style.id = id;
      style.textContent =
        'html.fc-inset div.absolute.left-0.right-0.text-center.z-20.pointer-events-none[style*="bottom"]' +
        '{bottom:var(--fc-inset)!important;transition:bottom .3s ease}';
      (document.head || document.documentElement).appendChild(style);
    }
    var root = document.documentElement;
    if (px > 0) {
      root.style.setProperty('--fc-inset', Math.round(px) + 'px');
      root.classList.add('fc-inset');
    } else {
      root.classList.remove('fc-inset');
    }
  };

  window.__FOLIA_ANDROID_CONTROLLER__ = true;
})();
