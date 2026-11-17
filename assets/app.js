/* 里程志 MileNote · 站点交互（原生 JS，无依赖、无追踪） */
(function () {
  'use strict';

  var root = document.documentElement;
  var EN = /^en/i.test(root.lang || '');
  function t(zh, en) { return EN ? en : zh; }

  var THEME_KEY = 'milenote-theme';
  var reduceMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

  /* ---------- 主题 ---------- */
  function paintThemeMeta(theme) {
    var color = theme === 'dark' ? '#0b1115' : '#f5f9fb';
    document.querySelectorAll('meta[name="theme-color"]').forEach(function (meta) {
      meta.setAttribute('content', color);
    });
  }

  function applyTheme(theme, persist) {
    root.dataset.theme = theme;
    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      btn.setAttribute('aria-pressed', String(theme === 'dark'));
      btn.setAttribute('aria-label', t('切换浅色或深色模式', 'Toggle light or dark mode'));
    });
    paintThemeMeta(theme);
    if (persist) {
      try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* 隐私模式下忽略 */ }
    }
  }

  function initTheme() {
    var stored = null;
    try { stored = localStorage.getItem(THEME_KEY); } catch (e) { /* ignore */ }
    applyTheme(
      stored === 'dark' || stored === 'light'
        ? stored
        : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
      false
    );

    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
      });
    });

    if (window.matchMedia) {
      var query = window.matchMedia('(prefers-color-scheme: dark)');
      var onChange = function (event) {
        var saved = null;
        try { saved = localStorage.getItem(THEME_KEY); } catch (e) { /* ignore */ }
        if (!saved) applyTheme(event.matches ? 'dark' : 'light', false);
      };
      if (typeof query.addEventListener === 'function') query.addEventListener('change', onChange);
      else if (typeof query.addListener === 'function') query.addListener(onChange);
    }
  }

  /* ---------- 顶栏状态 + 顶部滚动进度 ---------- */
  function initScrollChrome() {
    var header = document.querySelector('.site-header');
    var progress = document.querySelector('[data-progress]');
    var ticking = false;

    function paint() {
      ticking = false;
      var y = window.scrollY || window.pageYOffset || 0;
      if (header) header.classList.toggle('is-scrolled', y > 8);
      if (progress) {
        var max = document.documentElement.scrollHeight - window.innerHeight;
        var ratio = max > 0 ? Math.min(1, Math.max(0, y / max)) : 0;
        progress.style.setProperty('--p', String(ratio));
      }
    }

    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(paint);
    }, { passive: true });
    window.addEventListener('resize', paint, { passive: true });
    paint();
  }

  /* ---------- 滚动入场（IntersectionObserver，无 JS 时内容照常可见） ---------- */
  function initReveal() {
    var items = Array.prototype.slice.call(document.querySelectorAll('[data-reveal]'));
    if (!items.length) return;

    if (reduceMotion || !('IntersectionObserver' in window)) {
      items.forEach(function (el) { el.classList.add('is-in'); });
      return;
    }

    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });

    items.forEach(function (el) { io.observe(el); });
  }

  /* ---------- 下载入口：静态指向 Releases 页，取到最新 Release 才升级为 APK 直链 ----------
     取不到（被墙、限流、禁用 JS）就保持 Releases 页面：最多多一次跳转，绝不会给旧版本。 */
  function initRelease() {
    var links = document.querySelectorAll('[data-download]');
    if (!links.length) return;

    var API = 'https://api.github.com/repos/Selwiro/MileNoteAndroid/releases?per_page=10';
    var CACHE_KEY = 'milenote-release-v3';
    var TTL = 5 * 60 * 1000;

    function versionLine(data) {
      var mb = data.size ? Math.round(data.size / (1024 * 1024)) : 0;
      if (EN) {
        return 'Version v' + data.version + (mb ? ' · about ' + mb + ' MB' : '');
      }
      return '版本 v' + data.version + (mb ? ' · 约 ' + mb + ' MB' : '');
    }

    function apply(data) {
      links.forEach(function (link) { link.href = data.apkUrl; });

      document.querySelectorAll('[data-release-version]').forEach(function (el) {
        el.textContent = versionLine(data);
      });

      var ld = document.querySelector('script[type="application/ld+json"]');
      if (ld) {
        try {
          var json = JSON.parse(ld.textContent);
          json.softwareVersion = data.version;
          json.downloadUrl = data.apkUrl;
          ld.textContent = JSON.stringify(json);
        } catch (e) { /* 保留原 JSON-LD */ }
      }
    }

    function parse(release) {
      if (!release || release.draft) return null;
      var apk = (release.assets || []).filter(function (asset) {
        return /\.apk$/i.test(asset.name || '');
      })[0];
      if (!apk || !apk.browser_download_url) return null;
      var version = String(release.tag_name || '').replace(/^v/i, '');
      if (!version) return null;
      return { version: version, apkUrl: apk.browser_download_url, size: apk.size || 0 };
    }

    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem(CACHE_KEY) || 'null'); } catch (e) { /* ignore */ }
    if (cached && cached.data && cached.at && Date.now() - cached.at < TTL) {
      apply(cached.data);
      return;
    }

    if (!window.fetch) return;

    fetch(API, { headers: { Accept: 'application/vnd.github+json' } })
      .then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
      .then(function (list) {
        if (!Array.isArray(list)) return;
        var releases = list.filter(function (item) { return item && !item.draft; })
          .sort(function (a, b) { return new Date(b.published_at) - new Date(a.published_at); });
        for (var i = 0; i < releases.length; i += 1) {
          var data = parse(releases[i]);
          if (!data) continue;
          apply(data);
          try {
            sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data: data }));
          } catch (e) { /* ignore */ }
          return;
        }
      })
      .catch(function () {
        /* 取不到就保持 Releases 页面链接 */
      });
  }

  /* ---------- 年份 ---------- */
  function initYear() {
    document.querySelectorAll('#year').forEach(function (el) {
      el.textContent = String(new Date().getFullYear());
    });
  }

  initTheme();
  initScrollChrome();
  initReveal();
  initRelease();
  initYear();
})();
