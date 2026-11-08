/* 里程志 MileNote · 站点交互（原生 JS，无依赖） */
(function () {
  'use strict';

  var root = document.documentElement;
  var THEME_KEY = 'milenote-theme';
  /* 站点有两套语言页面（/ 与 /en/），这里只切换少量由 JS 写入的界面文案；
     默认中文，行为与原来一致。 */
  var EN = /^en/i.test(root.lang || '');
  function t(zh, en) { return EN ? en : zh; }
  var darkQuery = window.matchMedia('(prefers-color-scheme: dark)');

  /* ---------- 主题 ---------- */
  function paintThemeMeta(theme) {
    var color = theme === 'dark' ? '#0E1316' : '#F7FAFB';
    document.querySelectorAll('meta[name="theme-color"]').forEach(function (meta) {
      meta.setAttribute('content', color);
    });
  }

  function applyTheme(theme, persist) {
    root.dataset.theme = theme;
    document.querySelectorAll('.theme-toggle').forEach(function (btn) {
      btn.setAttribute('aria-pressed', String(theme === 'dark'));
      btn.setAttribute('aria-label', t('深色模式', 'Dark mode'));
    });
    paintThemeMeta(theme);
    if (persist) {
      try { localStorage.setItem(THEME_KEY, theme); } catch (e) { /* 隐私模式下忽略 */ }
    }
  }

  function initTheme() {
    var stored = null;
    try { stored = localStorage.getItem(THEME_KEY); } catch (e) { /* ignore */ }
    applyTheme(stored === 'dark' || stored === 'light' ? stored : (darkQuery.matches ? 'dark' : 'light'), false);

    document.querySelectorAll('.theme-toggle').forEach(function (btn) {
      btn.addEventListener('click', function () {
        applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
      });
    });

    var onSystemChange = function (event) {
      var stored2 = null;
      try { stored2 = localStorage.getItem(THEME_KEY); } catch (e) { /* ignore */ }
      if (!stored2) applyTheme(event.matches ? 'dark' : 'light', false);
    };
    if (typeof darkQuery.addEventListener === 'function') darkQuery.addEventListener('change', onSystemChange);
    else if (typeof darkQuery.addListener === 'function') darkQuery.addListener(onSystemChange);
  }

  /* ---------- 顶部导航 ---------- */
  function initHeader() {
    var header = document.querySelector('.site-header');
    if (!header) return;
    var onScroll = function () {
      header.classList.toggle('is-scrolled', window.scrollY > 8);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();

    var toggle = document.querySelector('.nav-toggle');
    var menu = document.getElementById('mobile-menu');
    if (toggle && menu) {
      var setOpen = function (open) {
        menu.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
        toggle.setAttribute('aria-label', open ? t('关闭菜单', 'Close menu') : t('打开菜单', 'Open menu'));
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
        if (window.innerWidth > 900 && !menu.hidden) setOpen(false);
      });
    }
  }

  /* ---------- 滚动进场 ---------- */
  function initReveal() {
    var items = document.querySelectorAll('.reveal');
    if (!items.length) return;
    if (!('IntersectionObserver' in window)) {
      items.forEach(function (el) { el.classList.add('is-in'); });
      return;
    }
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.1, rootMargin: '0px 0px -6% 0px' });
    items.forEach(function (el) { observer.observe(el); });
  }

  /* ---------- 导航高亮 ---------- */
  function initSectionSpy() {
    var links = Array.prototype.slice.call(document.querySelectorAll('.nav-links a[href^="#"]'));
    if (!links.length || !('IntersectionObserver' in window)) return;
    var map = {};
    links.forEach(function (link) {
      var id = link.getAttribute('href').slice(1);
      var section = document.getElementById(id);
      if (section) map[id] = link;
    });
    var spy = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var link = map[entry.target.id];
        if (!link) return;
        if (entry.isIntersecting) {
          links.forEach(function (other) { other.classList.remove('is-active'); });
          link.classList.add('is-active');
        }
      });
    }, { rootMargin: '-45% 0px -50% 0px' });
    Object.keys(map).forEach(function (id) {
      var el = document.getElementById(id);
      if (el) spy.observe(el);
    });
  }

  /* ---------- 界面巡览 ---------- */
  var tour = { tabs: [], images: [], caption: null, stageBtn: null, current: 0 };

  function showScreen(index) {
    if (!tour.tabs.length) return;
    tour.current = (index + tour.tabs.length) % tour.tabs.length;
    var tab = tour.tabs[tour.current];
    var key = tab.getAttribute('data-key');

    tour.tabs.forEach(function (el) {
      var on = el === tab;
      el.classList.toggle('is-active', on);
      el.setAttribute('aria-pressed', String(on));
    });
    tour.images.forEach(function (img) {
      var on = img.getAttribute('data-key') === key;
      img.classList.toggle('is-active', on);
      if (on) img.removeAttribute('aria-hidden');
      else img.setAttribute('aria-hidden', 'true');
    });
    var caption = tab.getAttribute('data-caption') || '';
    if (tour.caption) tour.caption.textContent = caption;
    if (tour.stageBtn) {
      var tabTitle = tab.querySelector('strong').textContent;
      tour.stageBtn.setAttribute('aria-label', t('查看「' + tabTitle + '」的完整截图', 'View full screenshot of “' + tabTitle + '”'));
    }
    return key;
  }

  function initTour() {
    tour.tabs = Array.prototype.slice.call(document.querySelectorAll('.tour-tab'));
    tour.images = Array.prototype.slice.call(document.querySelectorAll('.stage-img'));
    tour.caption = document.querySelector('.stage-caption');
    tour.stageBtn = document.querySelector('.stage-btn');
    if (!tour.tabs.length || !tour.images.length) return;

    tour.tabs.forEach(function (tab, index) {
      tab.addEventListener('click', function () { showScreen(index); });
    });
    showScreen(0);
  }

  /* ---------- 截图大图 ---------- */
  function initLightbox() {
    var dialog = document.getElementById('lightbox');
    if (!dialog || typeof dialog.showModal !== 'function') return;
    var img = document.getElementById('lightbox-img');
    var cap = document.getElementById('lightbox-cap');
    if (!tour.tabs.length) return;

    function show(index) {
      tour.current = (index + tour.tabs.length) % tour.tabs.length;
      var tab = tour.tabs[tour.current];
      var key = tab.getAttribute('data-key');
      var source = tour.images.filter(function (item) { return item.getAttribute('data-key') === key; })[0];
      var title = tab.querySelector('strong').textContent;
      img.src = source ? source.getAttribute('src') : 'assets/screens/' + key + '.webp';
      img.alt = source ? source.getAttribute('alt') : title;
      var counter = t('（' + (tour.current + 1) + ' / ' + tour.tabs.length + '）', ' (' + (tour.current + 1) + ' / ' + tour.tabs.length + ')');
      cap.textContent = (tab.getAttribute('data-caption') || title) + counter;
      showScreen(tour.current);
    }

    if (tour.stageBtn) {
      tour.stageBtn.addEventListener('click', function () {
        show(tour.current);
        dialog.showModal();
        document.body.classList.add('has-lightbox');
      });
    }

    dialog.addEventListener('close', function () {
      document.body.classList.remove('has-lightbox');
    });

    dialog.addEventListener('click', function (event) {
      if (event.target === dialog || event.target.classList.contains('lightbox-stage')) dialog.close();
    });

    var closeBtn = dialog.querySelector('.lightbox-close');
    if (closeBtn) closeBtn.addEventListener('click', function () { dialog.close(); });

    var prev = dialog.querySelector('.lightbox-nav.prev');
    var next = dialog.querySelector('.lightbox-nav.next');
    if (prev) prev.addEventListener('click', function () { show(tour.current - 1); });
    if (next) next.addEventListener('click', function () { show(tour.current + 1); });

    document.addEventListener('keydown', function (event) {
      if (!dialog.open) return;
      if (event.key === 'ArrowLeft') { event.preventDefault(); show(tour.current - 1); }
      if (event.key === 'ArrowRight') { event.preventDefault(); show(tour.current + 1); }
    });
  }


  /* ---------- 版本信息：自动读取最新 GitHub Release ---------- */
  var RELEASE_API = 'https://api.github.com/repos/Selwiro/MileNoteAndroid/releases?per_page=20';
  var RELEASE_CACHE = 'milenote-release-cache-v1';
  var RELEASE_TTL = 10 * 60 * 1000;

  function formatReleaseSize(bytes) {
    if (!bytes) return '';
    return t('约 ' + Math.round(bytes / 1048576) + ' MB', 'About ' + Math.round(bytes / 1048576) + ' MB');
  }

  function applyRelease(data) {
    document.querySelectorAll('[data-release-link]').forEach(function (link) {
      link.href = data.apkUrl;
    });
    document.querySelectorAll('[data-release-tag]').forEach(function (el) {
      el.textContent = 'v' + data.version + ' beta';
    });
    document.querySelectorAll('[data-release-version]').forEach(function (el) {
      el.textContent = data.version;
    });
    var sizeText = formatReleaseSize(data.size);
    if (sizeText) {
      document.querySelectorAll('[data-release-size]').forEach(function (el) { el.textContent = sizeText; });
    }
    if (data.sha) {
      document.querySelectorAll('[data-release-sha]').forEach(function (el) { el.textContent = data.sha; });
    }
    if (data.versionCode) {
      document.querySelectorAll('[data-release-code]').forEach(function (el) { el.textContent = data.versionCode; });
    } else {
      document.querySelectorAll('[data-release-code-row]').forEach(function (el) { el.hidden = true; });
    }
    if (data.notesUrl) {
      document.querySelectorAll('[data-release-notes]').forEach(function (el) { el.href = data.notesUrl; });
    }
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

  function parseRelease(release) {
    if (!release || release.draft || !release.published_at) return null;
    var apk = (release.assets || []).filter(function (asset) {
      return /\.apk$/i.test(asset.name || '');
    })[0];
    if (!apk || !apk.browser_download_url) return null;
    var version = String(release.tag_name || '').replace(/^v/i, '');
    if (!version) return null;
    var sha = '';
    if (apk.digest && /^sha256:/i.test(apk.digest)) sha = apk.digest.split(':')[1];
    var codeMatch = /(?:versionCode|vc)\s*[:：]?\s*(\d+)/i.exec(release.body || '');
    return {
      version: version,
      apkUrl: apk.browser_download_url,
      size: apk.size,
      sha: sha,
      versionCode: codeMatch ? codeMatch[1] : '',
      notesUrl: release.html_url || ''
    };
  }

  function initRelease() {
    if (!document.querySelector('[data-release-link]')) return;

    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem(RELEASE_CACHE) || 'null'); } catch (e) { /* ignore */ }
    if (cached && cached.data && cached.at && Date.now() - cached.at < RELEASE_TTL) {
      applyRelease(cached.data);
      return;
    }
    if (!window.fetch) return;

    fetch(RELEASE_API, { headers: { Accept: 'application/vnd.github+json' } })
      .then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
      .then(function (list) {
        if (!Array.isArray(list)) return;
        var releases = list
          .filter(function (item) { return item && !item.draft && item.published_at; })
          .sort(function (a, b) { return new Date(b.published_at) - new Date(a.published_at); });
        for (var i = 0; i < releases.length; i += 1) {
          var data = parseRelease(releases[i]);
          if (data) {
            applyRelease(data);
            try {
              sessionStorage.setItem(RELEASE_CACHE, JSON.stringify({ at: Date.now(), data: data }));
            } catch (e) { /* ignore */ }
            return;
          }
        }
      })
      .catch(function () { /* 网络或限流失败时保留页面静态回退 */ });
  }

  /* ---------- 复制 SHA-256 ---------- */
  function initCopy() {
    document.querySelectorAll('[data-copy]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var target = document.getElementById(btn.getAttribute('data-copy'));
        if (!target) return;
        var text = target.textContent.trim();

        function done() {
          var original = btn.textContent;
          btn.textContent = t('已复制', 'Copied');
          btn.classList.add('is-done');
          window.setTimeout(function () {
            btn.textContent = original;
            btn.classList.remove('is-done');
          }, 1600);
        }

        function fallback() {
          var area = document.createElement('textarea');
          area.value = text;
          area.setAttribute('readonly', '');
          area.style.position = 'fixed';
          area.style.top = '-1000px';
          document.body.appendChild(area);
          area.select();
          try { document.execCommand('copy'); done(); } catch (e) { /* ignore */ }
          document.body.removeChild(area);
        }

        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(text).then(done, fallback);
        } else {
          fallback();
        }
      });
    });
  }

  /* ---------- FAQ 手风琴 ---------- */
  function initFaq() {
    var items = Array.prototype.slice.call(document.querySelectorAll('.faq details'));
    items.forEach(function (item) {
      item.addEventListener('toggle', function () {
        if (!item.open) return;
        items.forEach(function (other) {
          if (other !== item) other.open = false;
        });
      });
    });
  }

  /* ---------- 页脚年份 ---------- */
  function initYear() {
    var el = document.getElementById('year');
    if (el) el.textContent = String(new Date().getFullYear());
  }

  function init() {
    initTheme();
    initHeader();
    initTour();
    initReveal();
    initSectionSpy();
    initLightbox();
    initCopy();
    initFaq();
    initRelease();
    initYear();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
