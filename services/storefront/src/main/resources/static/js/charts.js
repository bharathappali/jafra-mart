export function barChart(container, series, opts = {}) {
  const width = opts.width || 640;
  const height = opts.height || 180;
  const pad = 28;
  const max = Math.max(1, ...series.map((s) => s.orders || s.value || 0));
  const barW = Math.max(2, (width - pad * 2) / Math.max(1, series.length) - 2);
  let bars = '';
  series.forEach((s, i) => {
    const v = s.orders ?? s.value ?? 0;
    const h = ((height - pad * 2) * v) / max;
    const x = pad + i * (barW + 2);
    const y = height - pad - h;
    bars += `<rect x="${x}" y="${y}" width="${barW}" height="${h}" fill="#9FA1FF" rx="2">
      <title>${s.minute || s.label || i}: ${v}</title></rect>`;
  });
  container.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" role="img" aria-label="bar chart">
    <line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#1F2040" stroke-opacity="0.3"/>
    ${bars}
  </svg>`;
}

export function sparkline(container, values, opts = {}) {
  const width = opts.width || 200;
  const height = opts.height || 48;
  const max = Math.max(1, ...values);
  const min = Math.min(0, ...values);
  const pts = values.map((v, i) => {
    const x = (i / Math.max(1, values.length - 1)) * width;
    const y = height - ((v - min) / (max - min || 1)) * (height - 4) - 2;
    return `${x},${y}`;
  }).join(' ');
  container.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" role="img" aria-label="sparkline">
    <polyline fill="none" stroke="#F9B2D7" stroke-width="2" points="${pts}"/>
  </svg>`;
}

export function gantt(container, rows, opts = {}) {
  const width = opts.width || 900;
  const rowH = 28;
  const padL = 120;
  const padT = 24;
  const durationMin = opts.durationMin || 30;
  const nowMin = opts.nowMin ?? null;
  const height = padT + rows.length * rowH + 20;
  const scale = (m) => padL + (m / durationMin) * (width - padL - 16);
  let body = '';
  rows.forEach((row, i) => {
    const y = padT + i * rowH;
    body += `<text x="8" y="${y + 16}" font-size="12" fill="#1F2040">${row.label}</text>`;
    (row.segments || []).forEach((seg) => {
      const x = scale(seg.start);
      const w = Math.max(2, scale(seg.end) - x);
      let fill = '#9FA1FF';
      let pattern = '';
      if (seg.phase === 'ramp') fill = 'rgba(159,161,255,0.35)';
      if (seg.phase === 'hold') fill = '#F9B2D7';
      if (seg.phase === 'recover') {
        fill = 'url(#hatch)';
        pattern = 'hatch';
      }
      body += `<rect x="${x}" y="${y + 4}" width="${w}" height="18" fill="${fill}" rx="3">
        <title>${row.label} ${seg.phase} ${seg.start}-${seg.end}</title></rect>`;
      void pattern;
    });
    (row.markers || []).forEach((m) => {
      const x = scale(m.at);
      body += `<circle cx="${x}" cy="${y + 13}" r="3" fill="#1F2040"><title>${m.label || ''}</title></circle>`;
    });
  });
  let now = '';
  if (nowMin != null) {
    const x = scale(nowMin);
    now = `<line x1="${x}" y1="${padT - 8}" x2="${x}" y2="${height - 8}" stroke="#1F2040" stroke-dasharray="4 3"/>`;
  }
  const labels = (opts.overlapLabels || []).map((l, i) =>
    `<text x="${scale(l.at)}" y="${padT - 8}" font-size="11" fill="#1F2040">${l.text}</text>`).join('');
  container.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" role="img" aria-label="timeline gantt">
    <defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
      <rect width="6" height="6" fill="rgba(159,161,255,0.25)"/>
      <line x1="0" y1="0" x2="0" y2="6" stroke="#9FA1FF" stroke-width="2"/>
    </pattern></defs>
    ${labels}${body}${now}
  </svg>`;
}
