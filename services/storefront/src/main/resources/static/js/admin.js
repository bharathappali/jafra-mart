import { api, apiRaw } from './api.js';
import { barChart } from './charts.js';
import { renderTimeline, subsystemName } from './timeline.js';

const $ = (id) => document.getElementById(id);
let activeTab = 'dashboard';
let sse;
let pollTimer;

function showTab(name) {
  activeTab = name;
  document.querySelectorAll('[data-tab-panel]').forEach((el) => {
    el.classList.toggle('hidden', el.getAttribute('data-tab-panel') !== name);
  });
  document.querySelectorAll('.tab').forEach((el) => {
    el.classList.toggle('active', el.dataset.tab === name);
  });
  if (name === 'scenarios' || name === 'timeline') refreshConductor();
  if (name === 'services') refreshServices();
  if (name === 'orders') refreshOrders();
  if (name === 'customers') refreshCustomers();
}

async function refreshDashboard(summary, orders) {
  if (summary) {
    $('kpi-orders').textContent = summary.ordersTotal ?? '—';
    $('kpi-revenue').textContent = summary.revenueCents != null ? `$${(summary.revenueCents / 100).toFixed(2)}` : '—';
    $('kpi-rev5').textContent = summary.revenueLast5mCents != null ? `$${(summary.revenueLast5mCents / 100).toFixed(2)}` : '—';
    $('kpi-customers').textContent = summary.activeCustomers ?? '—';
    barChart($('opm-chart'), summary.ordersPerMinute || []);
  }
  if (orders) {
    const feed = $('live-orders');
    feed.innerHTML = '';
    for (const o of orders) {
      const li = document.createElement('li');
      li.textContent = `${o.orderNumber} · ${o.customerId} · ${o.status} · $${((o.totalCents || 0) / 100).toFixed(2)}`;
      feed.appendChild(li);
    }
  }
  try {
    const top = await api('/api/admin/customers/top?limit=10');
    const tbody = $('top-customers');
    tbody.innerHTML = '';
    for (const c of top) {
      const tr = document.createElement('tr');
      tr.innerHTML = `<td>${c.customerId}</td><td>${c.customerName || ''}</td><td>${c.orders}</td><td>$${((c.totalCents || 0) / 100).toFixed(2)}</td>`;
      tbody.appendChild(tr);
    }
  } catch { /* ignore */ }
}

function startSse() {
  if (sse) sse.close();
  sse = new EventSource('/api/admin/stream');
  sse.addEventListener('summary', (ev) => {
    try { refreshDashboard(JSON.parse(ev.data), null); } catch { /* ignore */ }
  });
  sse.addEventListener('orders', (ev) => {
    try { refreshDashboard(null, JSON.parse(ev.data)); } catch { /* ignore */ }
  });
  sse.onerror = () => { /* keep trying */ };
}

async function refreshOrders() {
  const filter = $('order-filter').value.trim();
  try {
    const orders = filter
      ? await api('/api/admin/customers/' + encodeURIComponent(filter) + '/orders')
      : await api('/api/admin/orders/recent?limit=50');
    const tbody = $('orders-body');
    tbody.innerHTML = '';
    for (const o of orders) {
      const tr = document.createElement('tr');
      const items = (o.items || []).map((i) => `${i.sku}×${i.quantity}`).join(', ');
      tr.innerHTML = `<td>${o.orderNumber}</td><td>${o.createdAt || ''}</td><td>${o.customerId}</td>
        <td>${o.status}</td><td>$${((o.totalCents || 0) / 100).toFixed(2)}</td><td class="muted">${items}</td>`;
      tbody.appendChild(tr);
    }
  } catch (err) {
    $('orders-body').innerHTML = `<tr><td colspan="6" class="error">${err.message}</td></tr>`;
  }
}

async function refreshCustomers() {
  try {
    const top = await api('/api/admin/customers/top?limit=10');
    const tbody = $('customers-body');
    tbody.innerHTML = '';
    for (const c of top) {
      const tr = document.createElement('tr');
      tr.innerHTML = `<td><button type="button" class="secondary">${c.customerId}</button></td>
        <td>${c.customerName || ''}</td><td>${c.orders}</td><td>$${((c.totalCents || 0) / 100).toFixed(2)}</td>`;
      tr.querySelector('button').addEventListener('click', () => {
        $('order-filter').value = c.customerId;
        showTab('orders');
        refreshOrders();
      });
      tbody.appendChild(tr);
    }
  } catch (err) {
    $('customers-body').innerHTML = `<tr><td colspan="4" class="error">${err.message}</td></tr>`;
  }
}

async function conductorFetch(path, options) {
  const res = await apiRaw('/api/admin/conductor' + path, {
    headers: { 'Accept': 'application/json', ...(options?.body ? { 'Content-Type': 'application/json' } : {}) },
    ...options,
  });
  if (!res.ok) {
    const err = new Error('loadgen not deployed');
    err.status = res.status;
    throw err;
  }
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return res.text();
}

async function refreshConductor() {
  const notice = $('loadgen-notice');
  try {
    const scenarios = await conductorFetch('/scenarios');
    notice.classList.add('hidden');
    const list = Array.isArray(scenarios) ? scenarios : (scenarios.scenarios || []);
    const wrap = $('scenario-cards');
    wrap.innerHTML = '';
    for (const s of list) {
      const card = document.createElement('article');
      card.className = 'card panel';
      const status = s.status || s;
      const state = status.state || 'IDLE';
      const chips = (s.subsystems || []).map((n) =>
        `<span class="chip">${n} ${subsystemName(n)}</span>`).join(' ');
      const intensity = status.currentIntensity ?? status.intensity ?? 0;
      const guardTripped = !!(status.guard && status.guard.tripped);
      card.innerHTML = `
        <h3>${s.id} — ${s.title || s.name || ''}</h3>
        <div class="muted">${s.service || ''}</div>
        <div class="chips">${chips}</div>
        <div><span class="state-${state}">${state}</span>
          ${guardTripped ? '<span class="chip warning">guard tripped</span>' : ''}</div>
        <div class="intensity-bar" aria-label="intensity"><span style="width:${intensity * 100}%"></span></div>
        <div class="field"><label for="int-${s.id}">Intensity</label>
          <input id="int-${s.id}" type="number" min="0.1" max="1" step="0.1" value="1.0"></div>
        <div class="field"><label for="hold-${s.id}">Hold seconds</label>
          <input id="hold-${s.id}" type="number" min="1" value="120"></div>
        <div>
          <button type="button" data-act="start">Start</button>
          <button type="button" class="secondary" data-act="stop">Stop</button>
          <button type="button" class="warning" data-act="reset">Reset</button>
        </div>`;
      card.querySelector('[data-act="start"]').addEventListener('click', async () => {
        const intensity = Number($('int-' + s.id).value);
        const holdSeconds = Number($('hold-' + s.id).value);
        await conductorFetch('/scenarios/' + s.id + '/start', {
          method: 'POST',
          body: JSON.stringify({ intensity, holdSeconds }),
        });
        refreshConductor();
      });
      card.querySelector('[data-act="stop"]').addEventListener('click', async () => {
        await conductorFetch('/scenarios/' + s.id + '/stop', { method: 'POST', body: '{}' });
        refreshConductor();
      });
      card.querySelector('[data-act="reset"]').addEventListener('click', async () => {
        await conductorFetch('/scenarios/' + s.id + '/reset', { method: 'POST', body: '{}' });
        refreshConductor();
      });
      wrap.appendChild(card);
    }

    let run = {};
    try { run = await conductorFetch('/runs/current'); } catch { run = {}; }
    $('run-status').textContent = run.runId
      ? `runId=${run.runId} elapsed=${formatElapsed(run.elapsedSeconds || 0)}`
      : 'No active run';

    if (activeTab === 'timeline') {
      let timeline = [];
      let gt = [];
      try {
        const tl = await conductorFetch('/timeline?script=default-30m');
        timeline = Array.isArray(tl) ? tl : (tl.entries || []);
      } catch { /* ignore */ }
      if (run.runId) {
        try {
          const raw = await conductorFetch('/ground-truth?runId=' + encodeURIComponent(run.runId));
          if (typeof raw === 'string') {
            gt = raw.trim().split('\n').filter(Boolean).map((line) => JSON.parse(line));
          } else if (Array.isArray(raw)) {
            gt = raw;
          }
        } catch { /* ignore */ }
      }
      renderTimeline($('timeline-view'), timeline, gt, run);
      $('gt-link').href = '/api/admin/conductor/ground-truth?runId=' + encodeURIComponent(run.runId || '') + '&download=1';
    }
  } catch {
    notice.classList.remove('hidden');
    notice.textContent = 'loadgen not deployed';
    $('scenario-cards').innerHTML = '';
    $('timeline-view').innerHTML = '<p class="muted">loadgen not deployed</p>';
  }
}

function formatElapsed(sec) {
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

async function refreshServices() {
  try {
    const services = await api('/api/admin/services');
    const wrap = $('services-grid');
    wrap.innerHTML = '';
    for (const s of services) {
      const el = document.createElement('article');
      el.className = 'card panel';
      el.innerHTML = `<strong>${s.component}</strong>
        <div>${s.up ? 'UP' : 'DOWN'}</div>
        <div class="muted">${s.pod || '—'}</div>
        <div class="muted">${s.framework || ''} · ${s.gc || ''}</div>`;
      wrap.appendChild(el);
    }
  } catch (err) {
    $('services-grid').innerHTML = `<p class="error">${err.message}</p>`;
  }
}

async function init() {
  try {
    const me = await api('/api/me');
    if (!(me.roles || []).includes('ADMIN')) {
      location.href = '/shop.html';
      return;
    }
    $('who').textContent = me.displayName || me.username;
  } catch {
    return;
  }

  document.querySelectorAll('.tab').forEach((btn) => {
    btn.addEventListener('click', () => showTab(btn.dataset.tab));
  });
  $('btn-logout').addEventListener('click', async () => {
    await api('/api/auth/logout', { method: 'POST' });
    location.href = '/login.html';
  });
  $('btn-filter-orders').addEventListener('click', refreshOrders);
  $('btn-run-script').addEventListener('click', async () => {
    try {
      await conductorFetch('/runs', {
        method: 'POST',
        body: JSON.stringify({
          script: 'default-30m',
          seed: Number($('seed').value) || 20261019,
          timeScale: Number($('time-scale').value) || 1.0,
        }),
      });
      refreshConductor();
    } catch {
      $('loadgen-notice').classList.remove('hidden');
      $('loadgen-notice').textContent = 'loadgen not deployed';
    }
  });
  $('btn-stop-run').addEventListener('click', async () => {
    try { await conductorFetch('/runs/current/stop', { method: 'POST', body: '{}' }); refreshConductor(); }
    catch { $('loadgen-notice').textContent = 'loadgen not deployed'; $('loadgen-notice').classList.remove('hidden'); }
  });
  $('btn-reset-all').addEventListener('click', async () => {
    try { await conductorFetch('/scenarios/reset-all', { method: 'POST', body: '{}' }); refreshConductor(); }
    catch { $('loadgen-notice').textContent = 'loadgen not deployed'; $('loadgen-notice').classList.remove('hidden'); }
  });

  startSse();
  showTab('dashboard');
  pollTimer = setInterval(() => {
    if (activeTab === 'scenarios' || activeTab === 'timeline') refreshConductor();
  }, 2000);
}

init();
