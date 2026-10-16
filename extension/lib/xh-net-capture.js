// X Helper · MAIN world 网络嗅探（document_start 注入 x.com 页面）
// 包装 window.fetch，抓两样东西：
//  1) X API 请求的 authorization（Bearer，X 网页公用 token，非私密）
//  2) grok add_response.json 请求的 x-client-transaction-id（AI 评论生成必需的签名）
// 通过 window.postMessage 转给 isolated world 的 content-grok.js，全程在本机，不外发。
(() => {
  'use strict';
  if (window.__xhNetCaptureInstalled) return;
  window.__xhNetCaptureInstalled = true;

  const GROK_RE = /\/2\/grok\/add_response\.json/;
  const XAPI_RE = /(\/i\/api\/|api\.x\.com|api\.twitter\.com)/;

  function getHeader(headers, name) {
    try {
      if (!headers) return null;
      if (typeof headers.get === 'function') return headers.get(name);
      if (Array.isArray(headers)) {
        const hit = headers.find(([k]) => String(k).toLowerCase() === name);
        return hit ? hit[1] : null;
      }
      for (const k of Object.keys(headers)) {
        if (k.toLowerCase() === name) return headers[k];
      }
    } catch (_) {}
    return null;
  }

  function inspect(input, init) {
    try {
      const url = typeof input === 'string' ? input : (input && input.url) || '';
      if (!url) return;
      const isGrok = GROK_RE.test(url);
      if (!isGrok && !XAPI_RE.test(url)) return;
      const headers =
        (init && init.headers) || (typeof input !== 'string' && input.headers) || null;
      const auth = getHeader(headers, 'authorization');
      const tx = getHeader(headers, 'x-client-transaction-id');
      if ((auth && auth.startsWith('Bearer ')) || (isGrok && tx)) {
        window.postMessage(
          { type: 'XH_NET_CAPTURE', auth: auth || null, tx: tx || null },
          '*'
        );
      }
    } catch (_) {}
  }

  const origFetch = window.fetch;
  window.fetch = function (input, init) {
    inspect(input, init);
    return origFetch.apply(this, arguments);
  };
})();
