/* 里程志 MileNote · 站点交互（原生 JS，无依赖、无追踪） */
(function () {
  'use strict';

  var root = document.documentElement;
  var EN = /^en/i.test(root.lang || '');
  function t(zh, en) { return EN ? en : zh; }

  var THEME_KEY = 'milenote-theme';

  /* ---------- 主题 ---------- */
  function paintThemeMeta(theme) {
    var color = theme === 'dark' ? '#0e1316' : '#f7fafb';
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

  /* ---------- 顶栏 ---------- */
  function initHeader() {
    var header = document.querySelector('.site-header');
    if (header) {
      var onScroll = function () {
        header.classList.toggle('is-scrolled', window.scrollY > 6);
      };
      window.addEventListener('scroll', onScroll, { passive: true });
      onScroll();
    }

    var toggle = document.querySelector('.nav-toggle');
    var menu = document.getElementById('mobile-menu');
    if (!toggle || !menu) return;

    var setOpen = function (open) {
      menu.hidden = !open;
      toggle.setAttribute('aria-expanded', String(open));
      toggle.setAttribute('aria-label', open
        ? t('关闭菜单', 'Close menu')
        : t('打开菜单', 'Open menu'));
    };

    toggle.addEventListener('click', function () {
      setOpen(menu.hidden);
    });

    menu.addEventListener('click', function (event) {
      if (event.target.closest('a')) setOpen(false);
    });

    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape' || menu.hidden) return;
      setOpen(false);
      toggle.focus();
    });

    window.addEventListener('resize', function () {
      if (window.innerWidth > 860 && !menu.hidden) setOpen(false);
    });
  }

  /* ---------- 界面预览 ---------- */
  function initScreens() {
    var stageImg = document.querySelector('[data-screen-img]');
    if (!stageImg) return;

    var tabs = Array.prototype.slice.call(document.querySelectorAll('[data-screen]'));
    var caption = document.querySelector('[data-screen-cap]');
    /* 资源前缀由页面给出（中文页 assets/screens/，英文页 ../assets/screens-en/） */
    var base = stageImg.getAttribute('data-screen-base') || '';
    var shots = EN ? [
      { file: 'dashboard.webp', cap: 'Overview' },
      { file: 'editor.webp', cap: 'Quick entry' },
      { file: 'analytics.webp', cap: 'Expenses' },
      { file: 'maintenance.webp', cap: 'Maintenance' }
    ] : [
      { file: 'dashboard.webp', cap: '用车总览' },
      { file: 'editor.webp', cap: '快速记账' },
      { file: 'analytics.webp', cap: '费用统计' },
      { file: 'maintenance.webp', cap: '维修保养' }
    ];
    shots.forEach(function (shot) { shot.src = base + shot.file; });

    var preloaded = {};
    function preload(index) {
      var shot = shots[index];
      if (!shot || preloaded[shot.src]) return;
      preloaded[shot.src] = true;
      var img = new Image();
      img.src = shot.src;
    }

    function show(index) {
      var next = (index + shots.length) % shots.length;
      var shot = shots[next];
      stageImg.src = shot.src;
      stageImg.alt = shot.cap + t('界面', ' screen');
      if (caption) caption.textContent = shot.cap;
      tabs.forEach(function (tab, i) {
        tab.setAttribute('aria-selected', String(i === next));
        tab.tabIndex = i === next ? 0 : -1;
      });
      preload(next + 1);
      preload(next - 1);
    }

    var current = 0;

    tabs.forEach(function (tab, i) {
      tab.tabIndex = i === 0 ? 0 : -1;
      tab.addEventListener('click', function () {
        current = i;
        show(current);
      });
      tab.addEventListener('keydown', function (event) {
        var delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
        if (!delta) return;
        event.preventDefault();
        current = (i + delta + tabs.length) % tabs.length;
        show(current);
        tabs[current].focus();
      });
    });

    var prev = document.querySelector('[data-screen-prev]');
    var next = document.querySelector('[data-screen-next]');
    if (prev) prev.addEventListener('click', function () { current -= 1; show(current); });
    if (next) next.addEventListener('click', function () { current += 1; show(current); });

    preload(1);
    preload(2);
  }

  /* ---------- 版本信息：跟随最新 GitHub Release ----------
     静态 HTML 里的下载按钮指向 Releases 页面，任何情况下都不会指向旧版本；
     这里只在取到更新的元数据时，把按钮直接指向最新 APK，并补上版本号。
     失败（被墙、限流、禁用 JS）就保持原样，不显示任何可能过期的版本号。 */
  function initRelease() {
    var links = document.querySelectorAll('[data-download]');
    if (!links.length) return;

    var API = 'https://api.github.com/repos/Selwiro/MileNoteAndroid/releases?per_page=10';
    var CACHE_KEY = 'milenote-release-v2';
    var TTL = 5 * 60 * 1000;

    function apply(data) {
      links.forEach(function (link) { link.href = data.apkUrl; });

      var sizeText = data.size ? t('约 ' + Math.round(data.size / 1048576) + ' MB',
        'about ' + Math.round(data.size / 1048576) + ' MB') : '';
      var versionText = t('最新版本 v', 'Latest v') + data.version + (sizeText ? ' · ' + sizeText : '');
      document.querySelectorAll('[data-release-version]').forEach(function (el) {
        el.textContent = versionText;
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
        /* 取不到就保持 Releases 页面链接：宁可多一次跳转，也不给旧版本 */
      });
  }

  /* ---------- 年份 ---------- */
  function initYear() {
    document.querySelectorAll('#year').forEach(function (el) {
      el.textContent = String(new Date().getFullYear());
    });
  }

  initTheme();
  initHeader();
  initScreens();
  initRelease();
  initYear();
})();
