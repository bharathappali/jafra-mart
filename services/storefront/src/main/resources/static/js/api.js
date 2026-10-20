export async function api(path, options = {}) {
  const opts = {
    credentials: 'same-origin',
    headers: { 'Accept': 'application/json', ...(options.headers || {}) },
    ...options,
  };
  if (opts.body && typeof opts.body === 'object' && !(opts.body instanceof FormData)) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(opts.body);
  }
  const res = await fetch(path, opts);
  if (res.status === 401 && !String(path).startsWith('/api/auth/login')) {
    location.href = '/login.html';
    throw new Error('UNAUTHORIZED');
  }
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    const data = await res.json();
    if (!res.ok) {
      const err = new Error(data.message || data.error || res.statusText);
      err.status = res.status;
      err.body = data;
      throw err;
    }
    return data;
  }
  if (!res.ok) {
    const err = new Error(res.statusText);
    err.status = res.status;
    throw err;
  }
  return res;
}

export async function apiRaw(path, options = {}) {
  const res = await fetch(path, { credentials: 'same-origin', ...options });
  if (res.status === 401) {
    location.href = '/login.html';
    throw new Error('UNAUTHORIZED');
  }
  return res;
}
