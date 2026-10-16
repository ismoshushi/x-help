// X Helper 浏览器扩展 · 后台 service worker（Manifest V3）
// 职责：x.com 标签页使用时长计时、账号数据快照落盘、每晚 23:05 当日总结通知
// 数据只存 chrome.storage.local，不向外发送任何数据。

const TICK_MS = 10000;          // content 心跳间隔
const SESSION_GAP_MS = 90000;   // 心跳间隔超过此值记为一次新的"打开"
const KEEP_DAYS = 30;           // 本地保留天数

function dayKey(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function freshDay() {
  return {
    seconds: 0, opens: 0,
    hours: new Array(24).fill(0),
    followers: null, following: null, posts: null, snapTime: null,
  };
}

async function getStore() {
  const r = await chrome.storage.local.get(['days', 'handle', 'lastTick']);
  return { days: r.days || {}, handle: r.handle || null, lastTick: r.lastTick || 0 };
}

// 只有当前激活标签页的心跳才计入（防多标签重复计时）
async function isActiveTab(tabId) {
  try {
    const t = await chrome.tabs.get(tabId);
    return !!t.active;
  } catch (e) {
    return false;
  }
}

async function creditTick() {
  const now = new Date();
  const { days, lastTick } = await getStore();
  const key = dayKey(now);
  const d = days[key] || freshDay();
  if (now.getTime() - lastTick > SESSION_GAP_MS) d.opens += 1;
  d.seconds += TICK_MS / 1000;
  d.hours[now.getHours()] += TICK_MS / 1000;
  days[key] = d;
  // 修剪旧数据
  const keys = Object.keys(days).sort();
  while (keys.length > KEEP_DAYS) delete days[keys.shift()];
  await chrome.storage.local.set({ days, lastTick: now.getTime() });
}

async function saveStats(stats) {
  const { days } = await getStore();
  const key = dayKey(new Date());
  const d = days[key] || freshDay();
  if (typeof stats.followers === 'number') d.followers = stats.followers;
  if (typeof stats.following === 'number') d.following = stats.following;
  if (typeof stats.posts === 'number') d.posts = stats.posts;
  d.snapTime = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  days[key] = d;
  await chrome.storage.local.set({ days });
}

chrome.runtime.onMessage.addListener((msg, sender) => {
  if (!sender.tab) return;
  if (msg.type === 'xhelper-tick') {
    isActiveTab(sender.tab.id).then((ok) => { if (ok) creditTick(); });
  } else if (msg.type === 'xhelper-stats') {
    saveStats(msg.stats || {});
  } else if (msg.type === 'xhelper-handle' && msg.handle) {
    chrome.storage.local.set({ handle: msg.handle });
  }
});

// ---- 每晚 23:05 当日总结 ----
function next2305() {
  const n = new Date();
  n.setHours(23, 5, 0, 0);
  if (n.getTime() <= Date.now()) n.setDate(n.getDate() + 1);
  return n.getTime();
}

function scheduleDaily() {
  chrome.alarms.create('xhelper-daily', { when: next2305(), periodInMinutes: 1440 });
}

chrome.runtime.onInstalled.addListener(scheduleDaily);
chrome.runtime.onStartup.addListener(scheduleDaily);

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'xhelper-daily') pushDailySummary();
});

function fmtMinutes(sec) {
  const m = Math.round(sec / 60);
  if (m < 60) return chrome.i18n.getMessage('fmtMinutes', [String(m)]);
  const h = Math.floor(m / 60);
  return chrome.i18n.getMessage('fmtHoursMinutes', [String(h), String(m % 60)]);
}

async function pushDailySummary() {
  const { days, handle } = await getStore();
  const keys = Object.keys(days).sort();
  const today = days[dayKey(new Date())] || freshDay();
  const prev = keys.length >= 2 ? days[keys[keys.length - 2]] : null;

  const lines = [];
  lines.push(chrome.i18n.getMessage('dailyUsage', [fmtMinutes(today.seconds), String(today.opens)]));
  if (today.followers != null) {
    let fLine = chrome.i18n.getMessage('dailyFollowers', [String(today.followers)]);
    if (prev && prev.followers != null && prev.followers !== today.followers) {
      const delta = today.followers - prev.followers;
      fLine += (delta > 0 ? ' (+' : ' (') + delta + ')';
    }
    lines.push(fLine);
  }
  if (today.posts != null && prev && prev.posts != null && today.posts !== prev.posts) {
    lines.push(chrome.i18n.getMessage('dailyPosts', [String(today.posts - prev.posts)]));
  }

  const title = 'X Helper' + (handle ? ' @' + handle : '');
  chrome.notifications.create('xhelper-daily-' + Date.now(), {
    type: 'basic',
    iconUrl: 'icons/icon128.png',
    title,
    message: lines.join('\n') || chrome.i18n.getMessage('dailyEmpty'),
  });
}
