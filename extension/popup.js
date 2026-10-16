// X Helper 扩展 popup：读 chrome.storage.local 渲染统计页（对标 App 浅色 UI）
// 文案全部走 chrome.i18n，跟随浏览器语言（zh_CN / en）。

const t = (key, subs) => chrome.i18n.getMessage(key, subs) || key;

function dayKey(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function fmtMinutes(sec) {
  const m = Math.round(sec / 60);
  if (m < 60) return t('fmtMinutes', [String(m)]);
  return t('fmtHoursMinutes', [String(Math.floor(m / 60)), String(m % 60)]);
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function renderBars(hours) {
  const max = Math.max(...hours, 1);
  const nowH = new Date().getHours();
  let bars = '';
  for (let h = 0; h < 24; h++) {
    const pct = Math.max(8, Math.round((hours[h] / max) * 100));
    bars += `<div class="bar${h === nowH ? ' now' : ''}" style="height:${pct}%"></div>`;
  }
  return `<div class="bars-track"><div class="bars">${bars}</div>` +
    `<div class="bars-axis"><span>${t('axis0')}</span><span>${t('axis12')}</span><span>${t('axis24')}</span></div></div>`;
}

async function render() {
  // 静态文案
  document.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.getAttribute('data-i18n'));
  });
  document.querySelectorAll('[data-i18n-ph]').forEach((el) => {
    el.placeholder = t(el.getAttribute('data-i18n-ph'));
  });

  // Jev API Key 设置（只存本地）
  const keyInput = document.getElementById('jevKey');
  const keyStatus = document.getElementById('jevStatus');
  const saved = await chrome.storage.local.get(['jevApiKey']);
  if (saved.jevApiKey) {
    keyInput.value = saved.jevApiKey;
    keyStatus.textContent = t('jevKeySaved');
  }
  document.getElementById('jevSave').addEventListener('click', async () => {
    const v = keyInput.value.trim();
    await chrome.storage.local.set({ jevApiKey: v });
    keyStatus.textContent = t('jevKeySaved');
    keyStatus.style.color = '#1A8CD8';
  });

  const { days = {}, handle = null } = await chrome.storage.local.get(['days', 'handle']);
  document.getElementById('uid').textContent = handle ? '@' + handle : '';

  const keys = Object.keys(days).sort();
  const todayKey = dayKey(new Date());
  const today = days[todayKey] || { seconds: 0, opens: 0, hours: new Array(24).fill(0) };

  let html = '';

  // Hero：今日使用
  html += `<div class="card hero">
    <div class="card-label">${esc(t('todayUsage'))}</div>
    <div class="hero-num">${esc(fmtMinutes(today.seconds))}</div>
    <div class="hero-meta">${esc(t('opensCount', [String(today.opens)]))}</div>
  </div>`;

  // 24 小时分布
  html += `<div class="card tonal">
    <div class="card-title">${esc(t('hours24'))}</div>
    ${renderBars(today.hours || new Array(24).fill(0))}
  </div>`;

  // 账号数据
  const prevKey = keys.filter((k) => k < todayKey).pop();
  const prev = prevKey ? days[prevKey] : null;
  const num = (v) => (v == null ? '–' : v.toLocaleString());
  let pubLine = '';
  if (today.posts != null && prev && prev.posts != null && today.posts > prev.posts) {
    pubLine = `<div class="pub">${esc(t('postsToday', [String(today.posts - prev.posts)]))}</div>`;
  }
  const snapLine = today.snapTime
    ? `<div class="snap-time">${esc(t('updatedAt', [today.snapTime]) + ' · ' + t('openProfileHint'))}</div>`
    : `<div class="snap-time">${esc(t('openProfileHint'))}</div>`;
  html += `<div class="card outlined">
    <div class="card-title">${esc(t('accountData'))}</div>
    <div class="grid">
      <div class="cell"><div class="num">${esc(num(today.followers))}</div><div class="lab">${esc(t('followers'))}</div></div>
      <div class="cell"><div class="num">${esc(num(today.following))}</div><div class="lab">${esc(t('following'))}</div></div>
      <div class="cell"><div class="num">${esc(num(today.posts))}</div><div class="lab">${esc(t('posts'))}</div></div>
    </div>${pubLine}${snapLine}
  </div>`;

  // 最近 7 天
  const recent = keys.slice(-7).reverse();
  if (recent.length > 0) {
    html += `<div class="card tonal"><div class="card-title" style="margin-bottom:10px">${esc(t('last7days'))}</div>`;
    for (let i = 0; i < recent.length; i++) {
      const k = recent[i];
      const d = days[k];
      const pk = keys[keys.indexOf(k) - 1];
      const pd = pk ? days[pk] : null;
      let extra = '';
      if (pd && d.followers != null && pd.followers != null && d.followers !== pd.followers) {
        const delta = d.followers - pd.followers;
        extra += ` <span class="up">${esc(t('rowFollowers', [(delta > 0 ? '+' : '') + delta]))}</span>`;
      }
      if (pd && d.posts != null && pd.posts != null && d.posts > pd.posts) {
        extra += ` <span class="up">${esc(t('rowPosts', ['+' + (d.posts - pd.posts)]))}</span>`;
      }
      const mmdd = k.slice(5);
      html += `<div class="wrow"><span class="d">${esc(mmdd)}</span> ` +
        `<span class="s">${esc(t('rowOpened', [String(d.opens || 0)]))} · ${esc(fmtMinutes(d.seconds || 0))}</span>${extra}</div>`;
    }
    html += `</div>`;
  }

  document.getElementById('content').innerHTML = html;
}

document.addEventListener('DOMContentLoaded', render);
