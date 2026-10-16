// X Helper · Jev 意图判断（TypeSafe System One API）
// 一次调用三问：回复策略选择（choice）+ 是否值得回（noul）+ 是否有风险（noul）。
// instructions 用英文（Jev 对 CJK 较弱，官方建议），state 保留推文原文；
// 阈值写在代码里，不进提示词；问题 id 不发给模型，仅用于取答案。
(() => {
  'use strict';

  const ENDPOINT = 'https://api.typesafe.ai/v1/systemone';
  const MODEL = 'jev-latest';

  // 阈值（代码里）
  const WORTH_IT_MIN = 0.35;
  const RISK_MAX = 0.6;

  const t = (key, subs) => chrome.i18n.getMessage(key, subs) || key;

  async function getKey() {
    const { jevApiKey } = await chrome.storage.local.get(['jevApiKey']);
    return (jevApiKey || '').trim();
  }

  function buildQuestions() {
    return {
      strategy: {
        type: 'choice',
        instructions:
          'Given the tweet in `state.text`, which reply strategy best serves the author? ' +
          'The author grows a Chinese X account about AI tools and side income; ' +
          'their voice is casual spoken Chinese with no full stops.',
        criteria: {
          fun: 'Playful banter, jokes, memes. Fits jokes, memes, light-hearted posts.',
          view: 'Add an informative angle, insight, or personal experience. Fits tech threads, opinions, tutorials.',
          warm: 'Friendly, sincere engagement. Fits personal updates, milestones, greetings.',
          question: 'Ask a genuine follow-up question. Fits posts inviting discussion.',
          skip: 'Do not reply. Fits ads, spam, flame wars, or content irrelevant to the author.',
        },
      },
      worth_it: {
        type: 'noul',
        instructions:
          'Would replying to the tweet in `state.text` likely help the author grow their X audience or strengthen their persona?',
        criteria: {
          true: 'Reply likely brings visibility, followers, or goodwill.',
          false: 'Reply unlikely to help growth.',
        },
      },
      risky: {
        type: 'noul',
        instructions:
          'Would replying to the tweet in `state.text` carry significant risk of flame war, misunderstanding, or platform policy violation?',
        criteria: {
          true: 'Notable risk of conflict, misunderstanding, or violation.',
          false: 'Safe to reply.',
        },
      },
    };
  }

  async function callJev(payload, apiKey, attempt = 0) {
    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${apiKey}`,
        'content-type': 'application/json',
        accept: 'application/json',
      },
      body: JSON.stringify(payload),
    });
    if (res.status === 429 || res.status === 529) {
      if (attempt < 2) {
        await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
        return callJev(payload, apiKey, attempt + 1);
      }
      throw new Error(t('jevRateLimit'));
    }
    if (res.status === 401) throw new Error(t('jevBadKey'));
    if (!res.ok) {
      let detail = '';
      try {
        const j = await res.json();
        detail = j.message || j.error || '';
      } catch (_) {}
      throw new Error(t('jevFailed', [`${res.status}${detail ? ' ' + detail : ''}`]));
    }
    return res.json();
  }

  // 主入口
  // 返回 { strategy, strategyConf, worthIt, risky, modelVersion, verdict }
  // verdict: 'go' | 'skip' | 'warn'
  async function judgeReplyIntent(tweetText) {
    const apiKey = await getKey();
    if (!apiKey) {
      const err = new Error(t('jevNoKey'));
      err.code = 'NO_KEY';
      throw err;
    }
    const text = String(tweetText || '').trim().slice(0, 3000);
    if (!text) throw new Error(t('aiNoParent'));
    const data = await callJev(
      { model: MODEL, state: { text }, questions: buildQuestions() },
      apiKey
    );
    const a = data.answers || {};
    const rawStrategy = a.strategy && a.strategy.choice;
    const strategy = ['fun', 'view', 'warm', 'question'].includes(rawStrategy)
      ? rawStrategy
      : 'view';
    const worthIt = typeof a.worth_it?.noul === 'number' ? a.worth_it.noul : 0.5;
    const risky = typeof a.risky?.noul === 'number' ? a.risky.noul : 0.5;

    let verdict = 'go';
    if (rawStrategy === 'skip' || worthIt < WORTH_IT_MIN) verdict = 'skip';
    else if (risky > RISK_MAX) verdict = 'warn';

    return {
      strategy,
      strategyConf:
        a.strategy && typeof a.strategy.confidence === 'number'
          ? a.strategy.confidence
          : null,
      worthIt,
      risky,
      modelVersion: data.model || null,
      verdict,
    };
  }

  window.__xhJev = { judgeReplyIntent };
})();
