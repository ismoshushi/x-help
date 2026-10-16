// X Helper 浏览器扩展 · 内容脚本（跑在 x.com / twitter.com 页面里）
// 职责：1) 可见标签页每 10 秒发一次心跳（供后台计时）；
//       2) 识别登录账号的 handle；3) 打开自己主页时抓取粉丝/关注/帖子数快照。
// 注意：X 的 DOM 结构经常变，所有解析都带多重降级，抓不到就跳过，不报错。

(function () {
  'use strict';
  const TICK_MS = 10000;

  // ---- 1. 心跳 ----
  setInterval(() => {
    if (document.visibilityState === 'visible') {
      try { chrome.runtime.sendMessage({ type: 'xhelper-tick' }); } catch (e) {}
    }
  }, TICK_MS);

  // ---- 2. 识别登录账号 ----
  // 侧边导航的"个人资料"入口 href 即登录账号：/<handle>
  function detectHandle() {
    try {
      const a = document.querySelector('a[data-testid="AppTabBar_Profile_Link"]');
      const href = a && a.getAttribute('href');
      const m = href && href.match(/^\/([A-Za-z0-9_]{1,15})\/?$/);
      return m ? m[1] : null;
    } catch (e) {
      return null;
    }
  }

  // "3,095" / "12.3K" / "12.3万" / "1.2M" -> 整数
  function parseCount(s) {
    if (!s) return null;
    s = String(s).trim().replace(/,/g, '').replace(/\s+/g, '');
    const m = s.match(/^([\d.]+)([KkMm万])?/);
    if (!m) return null;
    let v = parseFloat(m[1]);
    if (Number.isNaN(v)) return null;
    const u = m[2];
    if (u === 'K' || u === 'k') v *= 1000;
    else if (u === 'M' || u === 'm') v *= 1e6;
    else if (u === '万') v *= 1e4;
    return Math.round(v);
  }

  // 从"粉丝/正在关注"链接里取数字：链接文本结构通常是 <span><span>694</span></span><span>粉丝</span>
  function countFromLink(a) {
    if (!a) return null;
    const spans = a.querySelectorAll('span');
    for (const sp of spans) {
      const v = parseCount(sp.textContent);
      // 链接里第一个能解析出的纯数字就是计数值（排除"粉丝"这类文字）
      if (v !== null && /^\s*[\d,.]+\s*[KkMm万]?\s*$/.test(sp.textContent)) return v;
    }
    return parseCount(a.textContent);
  }

  // 顶栏 "3,095 posts" / "3,095 条帖子"
  function parsePostsFromHeader() {
    try {
      const headers = document.querySelectorAll('h2[role="heading"], [data-testid="primaryColumn"] h2');
      for (const h of headers) {
        const t = h.textContent || '';
        const m = t.match(/([\d,.]+)\s*(posts|帖子|条推文)/i);
        if (m) {
          const v = parseCount(m[1]);
          if (v !== null) return v;
        }
      }
      // 兜底：整个主列扫一遍
      const col = document.querySelector('[data-testid="primaryColumn"]');
      if (col) {
        const m = col.textContent.match(/([\d,.]+)\s*(posts|帖子)/i);
        if (m) return parseCount(m[1]);
      }
    } catch (e) {}
    return null;
  }

  // ---- 3. 自己主页快照 ----
  function snapshotOwnProfile(handle) {
    try {
      const path = location.pathname.replace(/\/+$/, '').toLowerCase();
      if (path !== '/' + handle.toLowerCase()) return; // 只在自己主页抓
      const stats = {};
      const root = document.querySelector('[data-testid="primaryColumn"]') || document;
      const links = root.querySelectorAll(
        'a[href$="/following"], a[href$="/followers"], a[href$="/verified_followers"]'
      );
      links.forEach((a) => {
        const href = (a.getAttribute('href') || '').toLowerCase();
        const v = countFromLink(a);
        if (v === null) return;
        if (href.endsWith('/following')) stats.following = v;
        else stats.followers = v; // followers / verified_followers 都算粉丝
      });
      const posts = parsePostsFromHeader();
      if (posts !== null) stats.posts = posts;
      if (Object.keys(stats).length > 0) {
        chrome.runtime.sendMessage({ type: 'xhelper-stats', stats });
      }
    } catch (e) {}
  }

  function boot() {
    const h = detectHandle();
    if (h) {
      try { chrome.runtime.sendMessage({ type: 'xhelper-handle', handle: h }); } catch (e) {}
      setTimeout(() => snapshotOwnProfile(h), 1500); // 等 React 渲染完
    }
  }

  // X 是单页应用：站内跳转不重载页面，盯住 URL 变化
  let lastUrl = location.href;
  setInterval(() => {
    if (location.href !== lastUrl) {
      lastUrl = location.href;
      boot();
    }
  }, 1000);

  if (document.readyState === 'complete') setTimeout(boot, 1200);
  else window.addEventListener('load', () => setTimeout(boot, 1200));
})();
