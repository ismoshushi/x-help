// X Helper · AI 评论生成（Grok）
// 原理：直接调用 X 内置 Grok 接口 https://grok.x.com/2/grok/add_response.json，
// 借用浏览器已登录态（cookie + x-csrf-token + 嗅探到的 bearer/tx-id），不需要 API key，
// 全程本机直连，不经过任何第三方服务。isTemporaryChat=true，不污染 Grok 对话历史。
//
// 签名（x-client-transaction-id）来源：lib/xh-net-capture.js 在 MAIN world 嗅探
// 用户在 x.com/i/grok 的真实请求。抓到后缓存 1 小时；过期则提示用户去发一条消息。
(() => {
  'use strict';

  const ENDPOINT = 'https://grok.x.com/2/grok/add_response.json';
  const TX_TTL_MS = 60 * 60 * 1000;
  const PH = '[推文内容]';

  const t = (key, subs) => chrome.i18n.getMessage(key, subs) || key;

  const state = { bearer: null, txId: null, txAt: 0 };

  // ---------- 嗅探结果 ----------
  window.addEventListener('message', (e) => {
    if (e.source !== window || !e.data || e.data.type !== 'XH_NET_CAPTURE') return;
    const { auth, tx } = e.data;
    if (auth) state.bearer = auth;
    if (tx && tx.length > 16) {
      state.txId = tx;
      state.txAt = Date.now();
      chrome.storage.local.set({ grokTx: { id: tx, at: state.txAt } }).catch(() => {});
    }
  });
  chrome.storage.local.get(['grokTx']).then(({ grokTx }) => {
    if (grokTx && Date.now() - grokTx.at < TX_TTL_MS) {
      state.txId = grokTx.id;
      state.txAt = grokTx.at;
    }
  }).catch(() => {});

  // ---------- 提示词模板（3 套，口语化、无句号，贴合用户发推风格） ----------
  const TEMPLATES = [
    {
      id: 'fun',
      labelKey: 'aiTplFun',
      prompt:
        `${PH}\n\n用轻松幽默的口吻玩梗接话，生成5条中文评论，每条用代码块包裹，` +
        `日常口语化，每条不超过40字，不要用中文句号`,
    },
    {
      id: 'view',
      labelKey: 'aiTplView',
      prompt:
        `${PH}\n\n针对这条推文补充一个有信息量的观点或角度，生成5条中文评论，` +
        `每条用代码块包裹，日常口语化，每条不超过60字，不要用中文句号`,
    },
    {
      id: 'warm',
      labelKey: 'aiTplWarm',
      prompt:
        `${PH}\n\n生成5条友好真诚的中文互动评论，每条用代码块包裹，` +
        `日常口语化，每条不超过30字，不要用中文句号`,
    },
    {
      id: 'question',
      labelKey: 'aiTplAsk',
      prompt:
        `${PH}\n\n针对这条推文提一个真诚具体的追问，生成5条中文评论，` +
        `每条用代码块包裹，日常口语化，每条不超过40字，不要用中文句号`,
    },
  ];

  // Jev strategy -> 模板下标
  const TPL_INDEX = { fun: 0, view: 1, warm: 2, question: 3 };

  function renderPrompt(tweetText, template) {
    const text = String(tweetText || '').trim().slice(0, 2000);
    return template.includes(PH) ? template.split(PH).join(text) : `${text}\n\n${template}`;
  }

  function cookieVal(name) {
    try {
      const m = document.cookie.match(
        new RegExp(`(?:^|;\\s*)${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}=([^;]+)`)
      );
      return m ? decodeURIComponent(m[1]) : '';
    } catch (_) {
      return '';
    }
  }

  function convoId() {
    try {
      const epoch = 1288834974657n;
      const ms = BigInt(Date.now()) - epoch;
      const rand = BigInt(Math.floor(Math.random() * 4194304));
      return String((ms << 22n) + rand);
    } catch (_) {
      return `${Date.now()}${Math.floor(Math.random() * 1e6)}`;
    }
  }

  function buildBody(prompt) {
    return JSON.stringify({
      responses: [{ message: prompt, sender: 1, promptSource: '', fileAttachments: [] }],
      systemPromptName: '',
      grokModelOptionId: 'grok-3-latest',
      modelMode: 'MODEL_MODE_FAST',
      conversationId: convoId(),
      returnSearchResults: false,
      returnCitations: false,
      promptMetadata: { promptSource: 'NATURAL', action: 'INPUT' },
      imageGenerationCount: 0,
      requestFeatures: { eagerTweets: false, serverHistory: false },
      enableSideBySide: true,
      toolOverrides: {},
      modelConfigOverride: {},
      isTemporaryChat: true,
    });
  }

  // NDJSON 流里抽 ASSISTANT/final 文本
  function extractFinal(raw) {
    const out = [];
    for (const line of String(raw || '').split(/\r?\n/)) {
      const s = line.trim();
      if (!s || !(s.startsWith('{') || s.startsWith('['))) continue;
      try {
        const p = JSON.parse(s);
        const r = p.result || p;
        if (r && r.sender === 'ASSISTANT' && r.messageTag === 'final' && typeof r.message === 'string') {
          out.push(r.message);
        }
      } catch (_) {}
    }
    return out.join('');
  }

  // 文本 -> 候选评论列表：优先代码块，其次序号列表
  function extractComments(text) {
    const joined = String(text || '');
    if (!joined.trim()) return [];
    const dedupe = (arr) =>
      [...new Set(arr.map((s) => s.trim()).filter(Boolean))].slice(0, 10);
    const blocks = [];
    const re = /```(?:[\w-]+)?\s*([\s\S]*?)```/g;
    let m;
    while ((m = re.exec(joined))) {
      const c = m[1].trim();
      if (c) blocks.push(c);
    }
    if (blocks.length) return dedupe(blocks);
    const items = joined
      .split(/\n+/)
      .map((s) => s.replace(/^\s*(?:\d+[\).]|[-*])\s+/, '').trim())
      .filter((s) => s.length >= 2 && s.length <= 500);
    return dedupe(items).slice(0, 10);
  }

  async function generate(tweetText, template, onProgress) {
    if (!cookieVal('ct0')) throw new Error(t('aiNeedLogin'));
    if (!state.bearer) throw new Error(t('aiNeedLogin'));
    if (!state.txId || Date.now() - state.txAt > TX_TTL_MS) {
      throw new Error(t('aiNeedGrok'));
    }
    const res = await fetch(ENDPOINT, {
      method: 'POST',
      credentials: 'include',
      headers: {
        authorization: state.bearer,
        'content-type': 'text/plain;charset=UTF-8',
        accept: '*/*',
        'x-csrf-token': cookieVal('ct0'),
        'x-twitter-active-user': 'yes',
        'x-twitter-auth-type': 'OAuth2Session',
        'x-twitter-client-language': (navigator.language || 'en').toLowerCase(),
        'x-xai-request-id': crypto.randomUUID(),
        'x-client-transaction-id': state.txId,
      },
      body: buildBody(renderPrompt(tweetText, template)),
    });
    if (res.status === 404) {
      state.txId = null;
      chrome.storage.local.remove(['grokTx']).catch(() => {});
      throw new Error(t('aiTxExpired'));
    }
    if (!res.ok) throw new Error(t('aiFailed', [String(res.status)]));

    // 流式读取，边下边解析
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    let finalText = '';
    let lastCount = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const nl = buf.lastIndexOf('\n');
      if (nl < 0) continue;
      const ready = buf.slice(0, nl);
      buf = buf.slice(nl + 1);
      const add = extractFinal(ready);
      if (!add) continue;
      finalText += add;
      const list = extractComments(finalText);
      if (list.length !== lastCount) {
        lastCount = list.length;
        if (onProgress(list) === false) {
          try { await reader.cancel(); } catch (_) {}
          break;
        }
      }
    }
    if (buf.trim()) finalText += extractFinal(buf);
    const comments = extractComments(finalText);
    if (!comments.length) throw new Error(t('aiEmpty'));
    return comments;
  }

  // ---------- 回复框注入 ----------
  const PANEL_ID = 'xh-ai-panel';

  function closePanel() {
    document.getElementById(PANEL_ID)?.remove();
  }

  // 只处理回复弹窗里的 composer（主时间线发帖框不碰，避免抓错推文）
  function findReplyComposer() {
    const ta = document.querySelector(
      '[role="dialog"] [data-testid="tweetTextarea_0"]:not([data-xh-ai="1"])'
    );
    return ta || null;
  }

  function parentTweetText(composer) {
    try {
      const dialog = composer.closest('[role="dialog"]');
      if (!dialog) return '';
      const articles = dialog.querySelectorAll('article');
      for (const a of articles) {
        const txt = (a.innerText || '').trim();
        if (txt.length > 10) return txt.slice(0, 1500);
      }
    } catch (_) {}
    return '';
  }

  function injectButton(composer) {
    composer.dataset.xhAi = '1';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = t('aiBtn');
    btn.style.cssText = [
      'position:absolute', 'top:6px', 'right:8px', 'z-index:50',
      'font-size:12px', 'font-weight:700', 'color:#1D9BF0',
      'background:#E8F5FE', 'border:1px solid #B9DDF8', 'border-radius:999px',
      'padding:4px 12px', 'cursor:pointer',
    ].join(';');
    btn.addEventListener('click', (ev) => {
      ev.stopPropagation();
      ev.preventDefault();
      openPanel(composer);
    });
    const host = composer.closest('[role="dialog"]') || composer.parentElement;
    const cs = getComputedStyle(host);
    if (cs.position === 'static') host.style.position = 'relative';
    host.appendChild(btn);
  }

  function openPanel(composer) {
    closePanel();
    const tweetText = parentTweetText(composer);
    const panel = document.createElement('div');
    panel.id = PANEL_ID;
    panel.style.cssText = [
      'position:fixed', 'z-index:99999', 'width:340px', 'max-height:420px',
      'overflow-y:auto', 'background:#fff', 'color:#0F1419',
      'border:1px solid #CFD9DE', 'border-radius:16px',
      'box-shadow:0 8px 28px rgba(0,0,0,.18)', 'padding:12px',
      'font-family:system-ui,sans-serif', 'font-size:14px',
      'top:20%', 'left:50%', 'transform:translateX(-50%)',
    ].join(';');

    const title = document.createElement('div');
    title.style.cssText = 'font-weight:800;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center;';
    title.innerHTML = `<span>${t('aiPanelTitle')}</span>`;
    const x = document.createElement('button');
    x.textContent = '✕';
    x.style.cssText = 'border:none;background:none;cursor:pointer;font-size:14px;color:#536471;';
    x.onclick = closePanel;
    title.appendChild(x);
    panel.appendChild(title);

    // Jev 意图判断结果条
    const jevBar = document.createElement('div');
    jevBar.style.cssText = 'font-size:12px;color:#536471;margin-bottom:8px;';
    const tplRow = document.createElement('div');
    tplRow.style.cssText = 'display:flex;gap:6px;margin-bottom:10px;';
    const list = document.createElement('div');
    list.style.cssText = 'display:flex;flex-direction:column;gap:6px;';
    const status = document.createElement('div');
    status.style.cssText = 'font-size:12px;color:#536471;margin-bottom:8px;';
    panel.appendChild(jevBar);
    panel.appendChild(tplRow);
    panel.appendChild(status);
    panel.appendChild(list);
    document.body.appendChild(panel);

    let currentTpl = 1;
    let cancelled = false;
    const closeObserver = new MutationObserver(() => {
      if (!document.body.contains(panel)) cancelled = true;
    });
    closeObserver.observe(document.body, { childList: true });

    function paintTemplates() {
      tplRow.querySelectorAll('button').forEach((el, j) => {
        el.style.background = j === currentTpl ? '#1D9BF0' : '#fff';
        el.style.color = j === currentTpl ? '#fff' : '#1D9BF0';
        el.style.borderColor = j === currentTpl ? '#1D9BF0' : '#B9DDF8';
      });
    }

    function renderItems(comments) {
      list.innerHTML = '';
      comments.forEach((c) => {
        const item = document.createElement('button');
        item.type = 'button';
        item.textContent = c;
        item.title = t('aiUse');
        item.style.cssText = [
          'text-align:left', 'background:#F7F9F9', 'border:1px solid #EFF3F4',
          'border-radius:12px', 'padding:8px 10px', 'cursor:pointer',
          'font-size:13px', 'line-height:1.4', 'color:#0F1419',
        ].join(';');
        item.onmouseenter = () => (item.style.borderColor = '#1D9BF0');
        item.onmouseleave = () => (item.style.borderColor = '#EFF3F4');
        item.onclick = () => {
          insertIntoComposer(composer, c);
          closePanel();
        };
        list.appendChild(item);
      });
    }

    async function run() {
      if (!tweetText) {
        status.textContent = t('aiNoParent');
        return;
      }
      status.textContent = t('aiGenerating');
      status.style.color = '#536471';
      list.innerHTML = '';
      try {
        await generate(tweetText, TEMPLATES[currentTpl].prompt, (comments) => {
          if (cancelled) return false;
          renderItems(comments);
          status.textContent = '';
        });
        status.textContent = '';
      } catch (err) {
        status.textContent = err.message || t('aiFailed', ['?']);
        status.style.color = '#F4212E';
      }
    }

    // Jev 建议别回 / 有风险时的警告条（带"仍要生成"）
    function warnNode(text, fallbackTpl) {
      const wrap = document.createElement('div');
      wrap.style.cssText = 'display:flex;align-items:center;gap:8px;flex-wrap:wrap;';
      const span = document.createElement('span');
      span.textContent = text;
      span.style.color = '#B0281A';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = t('jevStillGo');
      btn.style.cssText = 'font-size:12px;font-weight:700;color:#1D9BF0;background:#fff;border:1px solid #B9DDF8;border-radius:999px;padding:4px 12px;cursor:pointer;';
      btn.onclick = () => {
        currentTpl = fallbackTpl;
        paintTemplates();
        wrap.remove();
        run();
      };
      wrap.appendChild(span);
      wrap.appendChild(btn);
      return wrap;
    }

    // Jev 意图先行：一次调用三问（策略/值得度/风险），再定向生成
    async function judgeFirst() {
      if (!tweetText) {
        status.textContent = t('aiNoParent');
        return;
      }
      if (!window.__xhJev) {
        run(); // 极端降级：直接生成
        return;
      }
      jevBar.textContent = t('jevJudging');
      try {
        const r = await window.__xhJev.judgeReplyIntent(tweetText);
        if (cancelled) return;
        const tplIdx = TPL_INDEX[r.strategy] ?? 1;
        const stratName = t(TEMPLATES[tplIdx].labelKey);
        const conf = r.strategyConf != null ? ` (${Math.round(r.strategyConf * 100)}%)` : '';
        if (r.verdict === 'skip') {
          jevBar.innerHTML = '';
          jevBar.appendChild(warnNode(`${t('jevSkip')}${r.strategy === 'skip' ? '' : ' · ' + t('jevNotWorth')}`, 1));
          return;
        }
        currentTpl = tplIdx;
        paintTemplates();
        if (r.verdict === 'warn') {
          jevBar.innerHTML = '';
          jevBar.appendChild(
            warnNode(`${t('jevRecommend', [stratName])}${conf} · ${t('jevRisky')}`, tplIdx)
          );
          return;
        }
        jevBar.textContent = t('jevRecommend', [stratName]) + conf;
        run();
      } catch (err) {
        if (err && err.code === 'NO_KEY') {
          jevBar.textContent = t('jevNoKey');
          run(); // 无 key 降级：手动模板直接生成
        } else {
          jevBar.textContent = '';
          status.textContent = (err && err.message) || t('jevFailed', ['?']);
          status.style.color = '#F4212E';
        }
      }
    }

    TEMPLATES.forEach((tplDef, i) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = t(tplDef.labelKey);
      b.style.cssText = [
        'flex:1', 'font-size:12px', 'font-weight:700', 'border-radius:999px',
        'padding:6px 0', 'cursor:pointer',
        'background:#fff', 'color:#1D9BF0', 'border:1px solid #B9DDF8',
      ].join(';');
      b.onclick = () => {
        currentTpl = i;
        paintTemplates();
        run();
      };
      tplRow.appendChild(b);
    });
    paintTemplates();
    judgeFirst();
  }

  function insertIntoComposer(composer, text) {
    try {
      composer.focus();
      // contenteditable：execCommand 会触发 React 的 input 监听，回复按钮自动激活
      const ok = document.execCommand('insertText', false, text);
      if (!ok) {
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(composer);
        range.collapse(false);
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand('insertText', false, text);
      }
      composer.dispatchEvent(new InputEvent('input', { bubbles: true }));
    } catch (_) {}
  }

  // 监听回复弹窗出现
  const obs = new MutationObserver(() => {
    const c = findReplyComposer();
    if (c) injectButton(c);
  });
  obs.observe(document.documentElement, { childList: true, subtree: true });
})();
