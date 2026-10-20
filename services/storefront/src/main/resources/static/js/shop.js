import { api } from './api.js';

const CATEGORIES = [
  'Home', 'Kitchen', 'Office', 'Audio', 'Outdoor', 'Fashion',
  'Footwear', 'Accessories', 'Decor', 'Electronics', 'Wellness', 'Toys',
];

const state = {
  page: 0,
  category: '',
  fuzzy: false,
  cartCount: 0,
  flashTimer: null,
  stockTimer: null,
};

const $ = (id) => document.getElementById(id);

async function refreshCartCount() {
  try {
    const cart = await api('/api/cart');
    const items = cart.items || [];
    state.cartCount = items.reduce((n, i) => n + (i.quantity || 0), 0);
    $('cart-count').textContent = String(state.cartCount);
  } catch {
    /* ignore */
  }
}

async function loadProducts() {
  const params = new URLSearchParams({ page: String(state.page), size: '24' });
  if (state.category) params.set('category', state.category);
  const data = await api('/api/catalog/products?' + params.toString());
  const grid = $('product-grid');
  grid.innerHTML = '';
  for (const p of data.items || []) {
    const el = document.createElement('article');
    el.className = 'card product-card';
    el.innerHTML = `
      <img src="/api/catalog/products/${p.sku}/thumbnail?size=128" alt="${p.name}" width="128" height="128">
      <strong>${p.name}</strong>
      <span>$${(p.priceCents / 100).toFixed(2)}</span>
      <span class="muted">★ ${p.rating}</span>
      <button type="button" data-sku="${p.sku}">View</button>`;
    el.querySelector('button').addEventListener('click', () => openProduct(p.sku));
    grid.appendChild(el);
  }
  $('page-label').textContent = `Page ${state.page + 1} · ${data.total} items`;
  $('prev-page').disabled = state.page <= 0;
  $('next-page').disabled = (state.page + 1) * 24 >= data.total;
}

async function openProduct(sku) {
  const detail = await api('/api/catalog/products/' + sku);
  const p = detail.product;
  const a = detail.availability || {};
  const dlg = $('product-dialog');
  dlg.classList.remove('hidden');
  $('pd-thumb').src = `/api/catalog/products/${sku}/thumbnail?size=256`;
  $('pd-name').textContent = p.name;
  $('pd-price').textContent = `$${(p.priceCents / 100).toFixed(2)}`;
  $('pd-avail').textContent = `Available: ${a.available ?? a.quantity ?? '—'}${a.inStock === false ? ' (out of stock)' : ''}`;
  $('pd-qty').value = '1';
  $('pd-add').onclick = async () => {
    const quantity = Number($('pd-qty').value);
    await api('/api/cart/items', { method: 'POST', body: { sku, quantity } });
    await refreshCartCount();
  };
  const rec = await api('/api/recommendations?productId=' + encodeURIComponent(sku));
  const strip = $('pd-recs');
  strip.innerHTML = '';
  for (const rsku of (rec.items || []).slice(0, 8)) {
    const img = document.createElement('img');
    img.src = `/api/catalog/products/${rsku}/thumbnail?size=64`;
    img.alt = rsku;
    img.width = 64;
    img.height = 64;
    img.title = rsku;
    img.style.cursor = 'pointer';
    img.addEventListener('click', () => openProduct(rsku));
    strip.appendChild(img);
  }
}

async function runSearch() {
  const q = $('search-q').value.trim();
  if (!q) return;
  const params = new URLSearchParams({ q, limit: '24', fuzzy: String(state.fuzzy) });
  const data = await api('/api/catalog/search?' + params.toString());
  state.category = '';
  document.querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
  const grid = $('product-grid');
  grid.innerHTML = '';
  for (const p of data.items || []) {
    const el = document.createElement('article');
    el.className = 'card product-card';
    el.innerHTML = `
      <img src="/api/catalog/products/${p.sku}/thumbnail?size=128" alt="${p.name}" width="128" height="128">
      <strong>${p.name}</strong>
      <span>$${(p.priceCents / 100).toFixed(2)}</span>
      <button type="button" data-sku="${p.sku}">View</button>`;
    el.querySelector('button').addEventListener('click', () => openProduct(p.sku));
    grid.appendChild(el);
  }
  $('page-label').textContent = `Search “${q}” · ${data.count} results`;
}

async function pollFlash() {
  try {
    const flash = await api('/api/catalog/flash');
    const banner = $('flash-banner');
    if (flash.active) {
      banner.classList.remove('hidden');
      $('flash-text').textContent = 'Flash sale: Frosted Glass Smart Lamp −20 % with FLASH20';
      if (!state.stockTimer) {
        const tick = async () => {
          try {
            const inv = await api('/api/inventory/SKU-00042');
            $('flash-stock').textContent = `Stock: ${inv.available ?? inv.quantity ?? '—'}`;
          } catch { /* ignore */ }
        };
        tick();
        state.stockTimer = setInterval(tick, 3000);
      }
    } else {
      banner.classList.add('hidden');
      if (state.stockTimer) {
        clearInterval(state.stockTimer);
        state.stockTimer = null;
      }
    }
  } catch { /* ignore */ }
}

async function openCart() {
  const cart = await api('/api/cart');
  const drawer = $('cart-drawer');
  drawer.classList.remove('hidden');
  const lines = $('cart-lines');
  lines.innerHTML = '';
  for (const item of cart.items || []) {
    const row = document.createElement('div');
    row.innerHTML = `<span>${item.sku} × ${item.quantity}</span>
      <button type="button" class="secondary" data-sku="${item.sku}">Remove</button>`;
    row.querySelector('button').addEventListener('click', async () => {
      await api('/api/cart/items/' + item.sku, { method: 'DELETE' });
      await refreshCartCount();
      openCart();
    });
    lines.appendChild(row);
  }
  $('cart-subtotal').textContent = `Subtotal: $${((cart.subtotalCents || 0) / 100).toFixed(2)}`;
  $('coupon-msg').textContent = '';
}

async function loadOrders() {
  const orders = await api('/api/orders/mine');
  const panel = $('orders-panel');
  panel.classList.remove('hidden');
  panel.innerHTML = '<h2>My orders</h2>';
  for (const o of orders) {
    const div = document.createElement('div');
    div.className = 'card panel';
    const items = (o.items || []).map((i) => `${i.sku}×${i.quantity}`).join(', ');
    div.innerHTML = `<strong>${o.orderNumber}</strong>
      <div>${o.createdAt || ''} · ${o.status} · $${((o.totalCents || 0) / 100).toFixed(2)}</div>
      <div class="muted">${items}</div>`;
    panel.appendChild(div);
  }
}

function initChips() {
  const wrap = $('chips');
  const all = document.createElement('button');
  all.type = 'button';
  all.className = 'chip active';
  all.textContent = 'All';
  all.addEventListener('click', () => {
    state.category = '';
    state.page = 0;
    wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
    all.classList.add('active');
    loadProducts();
  });
  wrap.appendChild(all);
  for (const cat of CATEGORIES) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip';
    b.textContent = cat;
    b.addEventListener('click', () => {
      state.category = cat;
      state.page = 0;
      wrap.querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
      b.classList.add('active');
      loadProducts();
    });
    wrap.appendChild(b);
  }
}

async function init() {
  try {
    const me = await api('/api/me');
    if ((me.roles || []).includes('ADMIN') && !(me.roles || []).includes('USER')) {
      location.href = '/admin.html';
      return;
    }
    $('who').textContent = me.displayName || me.username;
  } catch {
    return;
  }
  initChips();
  await loadProducts();
  await refreshCartCount();
  pollFlash();
  setInterval(pollFlash, 10000);

  $('search-q').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runSearch();
  });
  $('fuzzy-toggle').addEventListener('change', (e) => {
    state.fuzzy = e.target.checked;
  });
  $('prev-page').addEventListener('click', () => { state.page--; loadProducts(); });
  $('next-page').addEventListener('click', () => { state.page++; loadProducts(); });
  $('btn-cart').addEventListener('click', openCart);
  $('btn-orders').addEventListener('click', loadOrders);
  $('btn-logout').addEventListener('click', async () => {
    await api('/api/auth/logout', { method: 'POST' });
    location.href = '/login.html';
  });
  $('pd-close').addEventListener('click', () => $('product-dialog').classList.add('hidden'));
  $('cart-close').addEventListener('click', () => $('cart-drawer').classList.add('hidden'));
  $('btn-coupon').addEventListener('click', async () => {
    const code = $('coupon-code').value.trim();
    const res = await api('/api/cart/coupon', { method: 'POST', body: { code } });
    $('coupon-msg').textContent = res.valid ? `Valid: ${res.message}` : `Invalid: ${res.message}`;
    $('coupon-msg').className = res.valid ? 'ok' : 'error';
  });
  $('btn-checkout').addEventListener('click', async () => {
    const cardToken = $('card-token').value;
    try {
      const order = await api('/api/checkout', { method: 'POST', body: { cardToken } });
      $('confirm-panel').classList.remove('hidden');
      $('confirm-panel').innerHTML = `<h2>Order placed</h2>
        <p>${order.orderNumber}</p>
        <p>Subtotal $${(order.subtotalCents / 100).toFixed(2)} · Discount $${(order.discountCents / 100).toFixed(2)} · Total $${(order.totalCents / 100).toFixed(2)}</p>
        <p>Status: ${order.status}</p>`;
      await refreshCartCount();
      $('cart-drawer').classList.add('hidden');
    } catch (err) {
      $('coupon-msg').textContent = err.body?.message || err.message;
      $('coupon-msg').className = 'error';
    }
  });
}

init();
