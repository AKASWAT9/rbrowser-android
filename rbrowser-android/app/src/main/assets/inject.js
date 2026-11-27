// R Browser (Android) - se ejecuta al inicio de cada página, antes que los
// scripts del sitio. Falsea el dispositivo (iPhone/Windows/Mac) y trae el
// extractor de links y el rechazo de avisos de cookies. Lee la configuración
// de window.__RBCFG, que pone la app antes de este archivo.
(function () {
  try {
    var cfg = window.__RBCFG || {};
    if (window.__RB && window.__RB.done) return;
    window.__RB = { extract: !!cfg.extract, banners: !!cfg.banners, done: true };

    var mark = function (fn) { return fn; };
    var def = function (obj, prop, value) {
      try { Object.defineProperty(obj, prop, { get: function () { return value; }, configurable: true, enumerable: true }); } catch (e) {}
    };
    var live = function (obj, prop, fn) {
      try { Object.defineProperty(obj, prop, { get: function () { return fn(); }, configurable: true, enumerable: true }); } catch (e) {}
    };

    // Generador con semilla: la misma página da siempre el mismo ruido.
    var s = (cfg.seed || 123456789) >>> 0;
    var rnd = function () { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
    var noise = []; for (var i = 0; i < 64; i++) noise.push(Math.floor(rnd() * 5) - 2);

    var N = Navigator.prototype;
    if (cfg.platform) def(N, 'platform', cfg.platform);
    if (cfg.vendor !== undefined) def(N, 'vendor', cfg.vendor);
    if (cfg.ua) def(N, 'userAgent', cfg.ua);
    if (cfg.appVersion) def(N, 'appVersion', cfg.appVersion);
    if (cfg.cores) def(N, 'hardwareConcurrency', cfg.cores);
    if (cfg.memory) def(N, 'deviceMemory', cfg.memory); else { try { delete N.deviceMemory; } catch (e) {} }
    def(N, 'maxTouchPoints', cfg.touch || 0);
    def(N, 'webdriver', false);
    if (cfg.languages) { def(N, 'languages', Object.freeze(cfg.languages.slice())); def(N, 'language', cfg.languages[0]); }

    // window.chrome: existe en Chrome (Windows/Android), no en Safari (iPhone/Mac).
    if (cfg.chrome) {
      try {
        var c = window.chrome || {};
        var t0 = Date.now() / 1000;
        if (!c.loadTimes) c.loadTimes = function () { return { requestTime: t0, startLoadTime: t0, commitLoadTime: t0 + 0.2, finishDocumentLoadTime: t0 + 0.5, finishLoadTime: t0 + 0.8, firstPaintTime: t0 + 0.4, firstPaintAfterLoadTime: 0, navigationType: 'Other', wasFetchedViaSpdy: true, wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2', wasAlternateProtocolAvailable: false, connectionInfo: 'h2' }; };
        if (!c.csi) c.csi = function () { return { startE: Math.round(t0 * 1000), onloadT: Math.round(t0 * 1000) + 600, pageT: performance.now(), tran: 15 }; };
        if (!window.chrome) Object.defineProperty(window, 'chrome', { value: c, writable: true, enumerable: true, configurable: false });
      } catch (e) {}
    } else {
      try { delete window.chrome; } catch (e) {}
    }

    // Pantalla y ventana coherentes con el dispositivo.
    if (cfg.screen) {
      var S = Screen.prototype;
      var w = cfg.screen[0], h = cfg.screen[1];
      if (cfg.mobile) {
        def(S, 'width', w); def(S, 'height', h); def(S, 'availWidth', w); def(S, 'availHeight', h);
      } else {
        var sw = function () { return Math.max(w, window.innerWidth); };
        var sh = function () { return Math.max(h, window.innerHeight + 40); };
        live(S, 'width', sw); live(S, 'height', sh); live(S, 'availWidth', sw); live(S, 'availHeight', function () { return sh() - 40; });
      }
      def(S, 'colorDepth', 24); def(S, 'pixelDepth', 24);
      if (cfg.dpr) def(window, 'devicePixelRatio', cfg.dpr);
    }

    // WebGL: la tarjeta del dispositivo.
    var glPatch = function (proto) {
      if (!proto) return;
      var orig = proto.getParameter;
      proto.getParameter = function (p) {
        if (cfg.gpu) { if (p === 37445) return cfg.gpu[0]; if (p === 37446) return cfg.gpu[1]; }
        return orig.apply(this, arguments);
      };
    };
    glPatch(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
    glPatch(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);

    // Canvas: ruido fijo por página.
    try {
      var C2D = CanvasRenderingContext2D.prototype;
      var origGet = C2D.getImageData;
      var tweak = function (img) { var d = img.data; var step = Math.max(4, Math.floor(d.length / 256 / 4) * 4); for (var i = 0, k = 0; i < d.length; i += step, k++) { var v = d[i] + noise[k & 63]; d[i] = v < 0 ? 0 : v > 255 ? 255 : v; } return img; };
      C2D.getImageData = function () { return tweak(origGet.apply(this, arguments)); };
      var HC = HTMLCanvasElement.prototype; var oURL = HC.toDataURL;
      HC.toDataURL = function () { try { var c = document.createElement('canvas'); c.width = this.width; c.height = this.height; var x = c.getContext('2d'); x.drawImage(this, 0, 0); x.putImageData(tweak(origGet.call(x, 0, 0, c.width, c.height)), 0, 0); return oURL.apply(c, arguments); } catch (e) { return oURL.apply(this, arguments); } };
    } catch (e) {}

    // Audio: ruido imperceptible.
    try {
      if (window.AudioBuffer) {
        var seen = new WeakSet(); var oc = AudioBuffer.prototype.getChannelData;
        AudioBuffer.prototype.getChannelData = function () { var data = oc.apply(this, arguments); if (!seen.has(data)) { seen.add(data); for (var i = 0; i < data.length; i += 100) data[i] += noise[(i / 100) & 63] * 1e-7; } return data; };
      }
    } catch (e) {}

    if (cfg.gpc) { try { def(N, 'globalPrivacyControl', true); } catch (e) {} }

    // ---------- Extractor de links ----------
    var cleanUrl = function (raw) {
      var u = String(raw || '').trim(); if (!u) return '';
      try {
        for (var i = 0; i < 3; i++) {
          var o = new URL(u, location.href);
          var keys = ['url', 'u', 'q', 'to', 'target', 'dest', 'destination', 'redirect', 'redirect_url', 'r', 'link', 'out', 'continue', 'next', 'imgurl'];
          var inner = '';
          for (var j = 0; j < keys.length; j++) { var v = o.searchParams.get(keys[j]); if (v && /^https?(:|%3a)/i.test(decodeURIComponent(v))) { inner = decodeURIComponent(v); break; } }
          if (inner && inner !== u) { u = inner; continue; }
          var drop = [];
          o.searchParams.forEach(function (_v, k) { if (/^(utm_|fbclid|gclid|gbraid|wbraid|mc_eid|mc_cid|igshid|yclid|_hsenc|_hsmi|ref|ref_src|si|spm)/i.test(k)) drop.push(k); });
          for (var d2 = 0; d2 < drop.length; d2++) o.searchParams.delete(drop[d2]);
          return o.toString().replace(/\?$/, '');
        }
      } catch (e) {}
      return u;
    };
    var urlFrom = function (el) {
      for (var n = el; n && n !== document; n = n.parentElement) {
        if (n.tagName === 'A' && n.href) return n.href;
        var d = n.dataset || {};
        var ks = ['href', 'url', 'link', 'to', 'src'];
        for (var i = 0; i < ks.length; i++) if (d[ks[i]] && /^https?:/i.test(d[ks[i]])) return d[ks[i]];
        var oc = n.getAttribute && n.getAttribute('onclick');
        if (oc) { var m = oc.match(/https?:\/\/[^'"\s)]+/); if (m) return m[0]; }
        if (n.tagName === 'IMG' && n.src) return n.src;
      }
      var t = (el.textContent || '').trim();
      if (/^https?:\/\/\S+$/i.test(t)) return t;
      return '';
    };
    var onClick = function (e) {
      if (!window.__RB.extract) return;
      var raw = urlFrom(e.target); if (!raw) return;
      e.preventDefault(); e.stopPropagation();
      try { RBNative.extracted(cleanUrl(raw), (e.target.textContent || '').trim().slice(0, 120), location.hostname); } catch (x) {}
    };
    document.addEventListener('click', onClick, true);

    window.__rbAllLinks = function () {
      var set = {};
      var push = function (u) { if (u) { u = cleanUrl(u); if (/^https?:/i.test(u)) set[u] = 1; } };
      var as = document.querySelectorAll('a[href]'); for (var i = 0; i < as.length; i++) push(as[i].href);
      var bs = document.querySelectorAll('[onclick],[data-href],[data-url],[data-link]'); for (var j = 0; j < bs.length; j++) push(urlFrom(bs[j]));
      return Object.keys(set).slice(0, 500);
    };
    window.__rbAllImages = function () {
      var set = {}; var im = document.querySelectorAll('img[src]');
      for (var i = 0; i < im.length; i++) if (/^https?:|^data:image/i.test(im[i].src)) set[im[i].src] = 1;
      return Object.keys(set).slice(0, 60);
    };

    // ---------- Rechazo de avisos de cookies ----------
    var CMP = ['#onetrust-reject-all-handler', '.ot-pc-refuse-all-handler', '#CybotCookiebotDialogBodyButtonDecline', '[data-testid="uc-deny-all-button"]', '.cmp-intro_rejectAll', 'button[aria-label*="Reject" i]', 'button[aria-label*="Rechazar" i]', '#didomi-notice-disagree-button', '.sp_choice_type_REJECT_ALL', '#cmpbntnotxt'];
    var CMPT = ['reject all', 'reject', 'decline', 'rechazar todo', 'rechazar', 'solo lo necesario', 'necessary only', 'continuar sin aceptar'];
    var killBanners = function () {
      if (!window.__RB.banners) return;
      for (var i = 0; i < CMP.length; i++) { var b = document.querySelector(CMP[i]); if (b) { try { b.click(); return; } catch (e) {} } }
      var btns = document.querySelectorAll('button, a[role="button"], [role="button"]');
      for (var j = 0; j < btns.length; j++) { var t = (btns[j].textContent || '').trim().toLowerCase(); if (t.length < 30 && CMPT.indexOf(t) >= 0) { try { btns[j].click(); return; } catch (e) {} } }
    };
    window.__rbKillBanners = killBanners;
    try { var obs = new MutationObserver(killBanners); if (document.documentElement) obs.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
    if (window.__RB.banners) { setTimeout(killBanners, 400); setTimeout(killBanners, 1500); }
  } catch (e) {}
})();
