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

  /* ---------- 幻灯片翻页（空格 / ↓ / PageDown 前进，↑ 后退） ---------- */
  function initDeck() {
    var slides = Array.prototype.slice.call(document.querySelectorAll('.slide'));
    if (!slides.length) return;

    var dots = Array.prototype.slice.call(document.querySelectorAll('[data-goto]'));
    var countEl = document.querySelector('[data-pager-count]');
    var hint = document.querySelector('[data-deck-hint]');
    var header = document.querySelector('.site-header');
    var reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    var index = 0;
    var ticking = false;

    /* 浏览器会在刷新时恢复上次滚动位置，和「一屏一页」冲突，这里关掉 */
    if ('scrollRestoration' in history) history.scrollRestoration = 'manual';

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    function paint(i) {
      slides.forEach(function (slide, j) {
        slide.classList.toggle('is-active', j === i);
      });
      dots.forEach(function (dot, j) {
        dot.setAttribute('aria-current', j === i ? 'true' : 'false');
      });
      if (countEl) countEl.textContent = pad(i + 1) + ' / ' + pad(slides.length);
      if (hint) hint.classList.toggle('is-hidden', i > 0);
      if (header) header.classList.toggle('is-scrolled', i > 0);
    }

    function setActive(i, syncHash) {
      index = Math.max(0, Math.min(slides.length - 1, i));
      paint(index);
      if (syncHash !== false && slides[index].id) {
        try { history.replaceState(null, '', '#' + slides[index].id); } catch (e) { /* ignore */ }
      }
    }

    function goTo(i) {
      i = Math.max(0, Math.min(slides.length - 1, i));
      setActive(i);
      slides[i].scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
    }

    /* 当前页：以视口 40% 处所在的幻灯片为准 */
    function nearest() {
      var y = window.scrollY || window.pageYOffset || 0;
      var ref = y + window.innerHeight * 0.4;
      var best = 0;
      for (var i = 0; i < slides.length; i += 1) {
        if (slides[i].offsetTop <= ref) best = i;
      }
      return best;
    }

    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(function () {
        ticking = false;
        setActive(nearest());
      });
    }, { passive: true });

    dots.forEach(function (dot) {
      dot.addEventListener('click', function () {
        goTo(parseInt(dot.getAttribute('data-goto'), 10) || 0);
      });
    });

    document.addEventListener('keydown', function (event) {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      var tag = ((event.target && event.target.tagName) || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

      var step = function (delta) {
        event.preventDefault();
        goTo(nearest() + delta);
      };

      switch (event.key) {
        case ' ':
        case 'Spacebar':
          step(event.shiftKey ? -1 : 1);
          break;
        case 'ArrowDown':
        case 'PageDown':
          step(1);
          break;
        case 'ArrowUp':
        case 'PageUp':
          step(-1);
          break;
        case 'Home':
          event.preventDefault();
          goTo(0);
          break;
        case 'End':
          event.preventDefault();
          goTo(slides.length - 1);
          break;
        default:
          break;
      }
    });

    /* 带 #hash 打开时直接落到对应那一屏 */
    var start = 0;
    if (location.hash) {
      var target = location.hash.slice(1);
      slides.forEach(function (slide, j) {
        if (slide.id === target) start = j;
      });
    }
    function jumpTo(i) {
      slides[i].scrollIntoView({ behavior: 'auto', block: 'start' });
    }

    setActive(start, false);
    jumpTo(start);
    /* 图片/字体加载完后浏览器可能再动一次滚动，load 时校正 */
    window.addEventListener('load', function () {
      jumpTo(start);
      setActive(start, false);
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
    function preload(i) {
      var shot = shots[(i + shots.length) % shots.length];
      if (!shot || preloaded[shot.src]) return;
      preloaded[shot.src] = true;
      var img = new Image();
      img.src = shot.src;
    }

    var current = 0;

    function show(i) {
      current = (i + shots.length) % shots.length;
      var shot = shots[current];
      stageImg.src = shot.src;
      stageImg.alt = shot.cap + t('界面', ' screen');
      if (caption) caption.textContent = shot.cap;
      tabs.forEach(function (tab, j) {
        tab.setAttribute('aria-selected', String(j === current));
        tab.tabIndex = j === current ? 0 : -1;
      });
      preload(current + 1);
      preload(current - 1);
    }

    tabs.forEach(function (tab, i) {
      tab.tabIndex = i === 0 ? 0 : -1;
      tab.addEventListener('click', function () { show(i); });
      tab.addEventListener('keydown', function (event) {
        var delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
        if (!delta) return;
        /* ← → 只在 tab 内部换图，不触发整页翻页 */
        event.preventDefault();
        event.stopPropagation();
        show(i + delta);
        var next = tabs[(i + delta + tabs.length) % tabs.length];
        if (next) next.focus();
      });
    });

    var prev = document.querySelector('[data-screen-prev]');
    var next = document.querySelector('[data-screen-next]');
    if (prev) prev.addEventListener('click', function () { show(current - 1); });
    if (next) next.addEventListener('click', function () { show(current + 1); });

    preload(1);
    preload(2);
  }

  /* ---------- 下载入口：静态指向 Releases 页，取到最新 Release 才升级为 APK 直链 ----------
     取不到（被墙、限流、禁用 JS）就保持 Releases 页面：最多多一次跳转，绝不会给旧版本。 */
  function initRelease() {
    var links = document.querySelectorAll('[data-download]');
    if (!links.length) return;

    var API = 'https://api.github.com/repos/Selwiro/MileNoteAndroid/releases?per_page=10';
    var CACHE_KEY = 'milenote-release-v2';
    var TTL = 5 * 60 * 1000;

    function apply(data) {
      links.forEach(function (link) { link.href = data.apkUrl; });

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
      return { version: version, apkUrl: apk.browser_download_url };
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
  initDeck();
  initScreens();
  initRelease();
  initYear();
})();
