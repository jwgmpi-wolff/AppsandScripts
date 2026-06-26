'use strict';

/* ══════════════════════════════════════════════════════════════════════
   IPTraceback Extended — Front-end JS
   ══════════════════════════════════════════════════════════════════════ */

/* ── Shared helpers ──────────────────────────────────────────────────── */
function show(el)  { el && el.classList.remove('hidden'); }
function hide(el)  { el && el.classList.add('hidden'); }
function esc(str)  { const d = document.createElement('div'); d.textContent = str; return d.innerHTML; }
function na(val)   { return (val !== null && val !== undefined && val !== '') ? val : null; }

function countryFlag(code) {
  if (!code || code.length !== 2) return '';
  return String.fromCodePoint(...[...code.toUpperCase()].map(c => 127397 + c.charCodeAt(0)));
}

function showToast(msg) {
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.textContent = msg;
  document.body.appendChild(toast);
  setTimeout(() => toast.classList.add('show'), 10);
  setTimeout(() => { toast.classList.remove('show'); setTimeout(() => toast.remove(), 350); }, 2500);
}

function copyToClipboard(text) {
  navigator.clipboard.writeText(text)
    .then(() => showToast('Copied!'))
    .catch(() => showToast('Copy failed'));
}

function setCell(id, value) {
  const td = document.getElementById(id);
  if (!td) return;
  const v = na(value);
  if (v !== null) {
    td.textContent = v;
    td.className = 'copyable';
    td.dataset.copyValue = String(v).replace(/°/g, '');
  } else {
    td.textContent = '—';
    td.className = 'na';
    td.dataset.copyValue = '';
  }
}

function exportJSON(data, filename) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url  = URL.createObjectURL(blob);
  const a    = Object.assign(document.createElement('a'), { href: url, download: filename });
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
}

function setupCopyables(container) {
  (container || document).querySelectorAll('.copyable').forEach(el => {
    el.title = 'Click to copy';
    el.onclick = () => {
      const v = el.dataset.copyValue || el.textContent;
      if (v && v !== '—') copyToClipboard(v);
    };
  });
}

/* ── Nav tab switching ──────────────────────────────────────────────── */
document.querySelectorAll('.nav-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.nav-tab').forEach(t => {
      t.classList.remove('active');
      t.setAttribute('aria-selected', 'false');
    });
    document.querySelectorAll('.tab-pane').forEach(p => {
      p.classList.remove('active');
    });
    tab.classList.add('active');
    tab.setAttribute('aria-selected', 'true');
    const target = document.getElementById(`tab-${tab.dataset.tab}`);
    if (target) target.classList.add('active');
  });
});

/* ── Generic example chips ──────────────────────────────────────────── */
document.querySelectorAll('.example-chip').forEach(chip => {
  chip.addEventListener('click', () => {
    const target = document.getElementById(chip.dataset.target);
    if (target) {
      target.value = chip.dataset.value;
      target.focus();
      
      // Auto-submit the associated form
      const prefix = chip.dataset.target.replace('-input', '');
      const form = document.getElementById(`${prefix}-form`);
      if (form) {
        // Simulate form submission by triggering submit event
        form.dispatchEvent(new Event('submit', { bubbles: true }));
      }
    }
  });
});

/* ── Clear All Data button ──────────────────────────────────────────– */
document.getElementById('clear-all-btn').addEventListener('click', async () => {
  // Clear server cache
  try {
    await fetch('/api/cache-clear');
  } catch (e) { /* silently fail */ }
  
  // Clear all form fields
  document.querySelectorAll('input[type="text"], textarea').forEach(el => {
    el.value = '';
  });
  
  // Reset checkboxes to defaults
  const geolocate = document.getElementById('tb-geolocate');
  const discover = document.getElementById('tb-discover');
  if (geolocate) geolocate.checked = true;
  if (discover) discover.checked = false;
  
  // Clear hub data
  Object.keys(window.ipTracebackHub._timers || {}).forEach(k =>
    clearTimeout(window.ipTracebackHub._timers[k])
  );
  window.ipTracebackHub._timers = {};
  
  // Hide all result sections
  document.querySelectorAll('.results-card').forEach(el => hide(el));
  document.querySelectorAll('.error-banner').forEach(el => hide(el));
  document.querySelectorAll('.loading').forEach(el => hide(el));
  
  // Hide results within Traceback tab
  document.querySelectorAll('#traceback-results [class*="block"]').forEach(el => hide(el));
  
  // Clear chain status
  hide(document.getElementById('chain-status'));
  
  showToast('✓ All data cleared');
});

/* ══════════════════════════════════════════════════════════════════════
   REACTIVE DATA HUB — Chain execution across tabs
   ══════════════════════════════════════════════════════════════════════ */
window.ipTracebackHub = {
  chainEnabled: true,
  _timers: {},

  /* ── helpers ─────────────────────────────────────────────────────── */
  setField(id, value) {
    const el = document.getElementById(id);
    if (el) el.value = value;
  },

  isPublicIPv4(ip) {
    if (!ip || typeof ip !== 'string' || ip.includes(':')) return false;
    const p = ip.split('.').map(Number);
    if (p.length !== 4 || p.some(n => isNaN(n) || n < 0 || n > 255)) return false;
    if (p[0] === 10) return false;
    if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return false;
    if (p[0] === 192 && p[1] === 168) return false;
    if (p[0] === 127 || p[0] === 0) return false;
    return true;
  },

  publicIPs(ips) {
    return (ips || []).filter(ip => this.isPublicIPv4(ip));
  },

  submit(formId, delay) {
    if (this._timers[formId]) clearTimeout(this._timers[formId]);
    this._timers[formId] = setTimeout(() => {
      const form = document.getElementById(formId);
      if (form && this.chainEnabled) {
        form.dispatchEvent(new Event('submit', { bubbles: true }));
      }
    }, delay);
  },

  updateChainStatus(msg) {
    const status = document.getElementById('chain-status');
    if (!status) return;
    status.textContent = msg;
    status.classList.add('active');
    if (this._statusTimer) clearTimeout(this._statusTimer);
    this._statusTimer = setTimeout(() => status.classList.remove('active'), 4000);
  },

  /* ── called after Decode produces results ────────────────────────── */
  onDecodeResult(encodedInput, ips) {
    const pub = this.publicIPs(ips);
    const label = `⛓ Decode → Traceback${pub.length ? ' → Classify → Geolocate' : ''}`;
    this.updateChainStatus(label);
    if (!this.chainEnabled) return;

    // → Traceback: feed encoded input
    this.setField('tb-encoded', encodedInput);
    document.getElementById('tb-discover').checked = false;
    document.getElementById('tb-geolocate').checked = true;
    this.submit('traceback-form', 500);

    // → Classify: feed extracted IPs
    if (pub.length > 0) {
      this.setField('classify-input', pub.join('\n'));
      this.submit('classify-form', 700);
    }

    // → Geolocate: feed first public IP
    if (pub.length > 0) {
      this.setField('geo-input', pub[0]);
      this.submit('geo-form', 900);
    }
  },

  /* ── called after Traceback produces results ─────────────────────── */
  onTracebackResult(ips) {
    const pub = this.publicIPs(ips);
    if (pub.length === 0) return;
    this.updateChainStatus(`⛓ Traceback → Classify → Geolocate (${pub.length} IP${pub.length > 1 ? 's' : ''})`);
    if (!this.chainEnabled) return;

    // → Classify
    this.setField('classify-input', pub.join('\n'));
    this.submit('classify-form', 400);

    // → Geolocate: first public IP
    this.setField('geo-input', pub[0]);
    this.submit('geo-form', 700);
  },

  /* ── called after DNS lookup produces A-record IPs ───────────────── */
  onDNSResult(hostname, ips) {
    const pub = this.publicIPs(ips);
    if (pub.length === 0) return;
    this.updateChainStatus(`⛓ DNS → Classify → Geolocate (${pub.length} IP${pub.length > 1 ? 's' : ''})`);
    if (!this.chainEnabled) return;

    // → Classify
    this.setField('classify-input', pub.join('\n'));
    this.submit('classify-form', 400);

    // → Geolocate: first public IP
    this.setField('geo-input', pub[0]);
    this.submit('geo-form', 700);

    // → Traceback: add hostname
    this.setField('tb-hostnames', hostname);
    document.getElementById('tb-discover').checked = false;
    document.getElementById('tb-geolocate').checked = true;
    this.submit('traceback-form', 900);
  },

  /* ── called after Classify produces results ──────────────────────── */
  onClassifyResult(classifications) {
    const pub = (classifications || [])
      .filter(c => c.valid && c.version === 4 && !c.isPrivate)
      .map(c => c.ip);
    if (pub.length === 0) return;
    this.updateChainStatus(`⛓ Classify → Geolocate (${pub[0]})`);
    if (!this.chainEnabled) return;

    // → Geolocate: first public IP
    this.setField('geo-input', pub[0]);
    this.submit('geo-form', 400);
  }
};

document.getElementById('chain-enabled')?.addEventListener('change', (e) => {
  window.ipTracebackHub.chainEnabled = e.target.checked;
  showToast(e.target.checked ? 'Chain execution enabled' : 'Chain execution disabled');
});

/* ══════════════════════════════════════════════════════════════════════   SHARED: buildTraceCard — renders one entry from /api/trace results
   ══════════════════════════════════════════════════════════════════ */
function buildTraceCard(entry) {
  const c   = entry.classification;
  const g   = entry.geo;
  const rev = entry.reverseDNS;
  const ip  = entry.ip;

  // Classification row values
  const classRows = c && c.valid && c.version === 4 ? `
    <tr><td>Class</td><td>${esc(c.class)}</td></tr>
    <tr><td>Status</td><td>${c.isPrivate
      ? '<span class="badge-private" style="font-size:.78rem">&#9650; Private</span>'
      : '<span class="badge-public"  style="font-size:.78rem">&#9654; Public</span>'}</td></tr>
    <tr><td>Numeric</td><td>${esc(String(c.numeric))}</td></tr>
    <tr><td>Hex</td><td>${esc(c.hex)}</td></tr>` : (c && c.valid && c.version === 6
      ? `<tr><td>IPv6</td><td>${esc(c.expanded)}</td></tr>` : '');

  // Geo row values
  const flag = g?.success ? countryFlag(g.countryCode) : '';
  const geoRows = g?.success ? `
    <tr><td>Country</td><td>${flag} ${esc(g.country||'?')}</td></tr>
    <tr><td>City</td><td>${esc(g.city||'?')}, ${esc(g.region||'?')}</td></tr>
    <tr><td>ISP</td><td>${esc(g.isp||'?')}</td></tr>
    <tr><td>ASN</td><td>${esc(g.asn||'?')}</td></tr>
    ${g.isProxy != null ? `<tr><td>Proxy</td><td>${g.isProxy
      ? '<span class="badge-private" style="font-size:.78rem">Yes</span>'
      : '<span style="color:var(--muted);font-size:.78rem">No</span>'}</td></tr>` : ''}
    ${g.isHosting ? `<tr><td>Hosting</td><td><span style="color:var(--yellow);font-size:.78rem">Yes</span></td></tr>` : ''}
    ${g.reverseDNS ? `<tr><td>rDNS</td><td>${esc(g.reverseDNS)}</td></tr>` : ''}
    ${g.lat != null ? `<tr><td>Coords</td><td>${g.lat}, ${g.lon}</td></tr>` : ''}` : '';

  // Reverse DNS (separate lookup)
  const rdnsExtra = rev?.success && rev.hostnames?.length && !g?.reverseDNS
    ? `<tr><td>rDNS</td><td>${esc(rev.hostnames[0])}</td></tr>` : '';

  const versionBadge = c?.valid
    ? `<span class="classify-version-badge ${c.version === 4 ? 'badge-v4' : 'badge-v6'}">IPv${c.version}</span>`
    : '';

  const mapHref = g?.success && g.lat != null
    ? `https://www.openstreetmap.org/?mlat=${g.lat}&mlon=${g.lon}&zoom=10` : null;

  return `
    <div class="trace-card ${entry.fromCache ? 'trace-cached' : ''}">
      <div class="trace-card-header">
        <span class="trace-ip">${esc(ip)}</span>
        ${versionBadge}
        ${entry.fromCache ? '<span class="cache-indicator" style="font-size:.7rem">⚡ Cached</span>' : ''}
      </div>
      <div class="trace-card-body">
        ${classRows || geoRows ? `<table class="trace-table">${classRows}${geoRows}${rdnsExtra}</table>` : `<div style="color:var(--muted);font-size:.82rem">No data available</div>`}
      </div>
      ${mapHref ? `<a href="${mapHref}" target="_blank" rel="noopener noreferrer" class="trace-map-link">&#127760; Map</a>` : ''}
    </div>`;
}

/* ══════════════════════════════════════════════════════════════════   DECODE TAB
   ══════════════════════════════════════════════════════════════════════ */
let lastDecodeData = null;
let decodeFoundIPs = [];

const decodeForm        = document.getElementById('decode-form');
const decodeInput       = document.getElementById('decode-input');
const decodeMethod      = document.getElementById('decode-method');
const decodeBtn         = document.getElementById('decode-btn');
const decodeLoading     = document.getElementById('decode-loading');
const decodeError       = document.getElementById('decode-error');
const decodeResults     = document.getElementById('decode-results');
const decodeList        = document.getElementById('decode-results-list');
const decodeExport      = document.getElementById('decode-export-btn');
const decodeTraceBtn    = document.getElementById('decode-trace-btn');
const decodeTraceSection= document.getElementById('decode-trace-section');
const decodeTraceLoading= document.getElementById('decode-trace-loading');
const decodeTraceError  = document.getElementById('decode-trace-error');
const decodeTraceGrid   = document.getElementById('decode-trace-grid');

function renderDecodeResult(result) {
  if (!result.success || !result.results || result.results.length === 0) {
    return `<div class="decode-item" style="color:var(--muted)">No valid decodings found for method <strong>${esc(result.results?.[0]?.method || decodeMethod.value)}</strong>.</div>`;
  }

  return result.results.map(r => {
    const ipsHtml = r.ips && r.ips.length > 0
      ? `<div class="decode-ips">${r.ips.map(ip => `<span class="ip-chip" title="Click to copy" onclick="copyToClipboard('${esc(ip)}')">${esc(ip)}</span>`).join('')}</div>`
      : '';
    return `
      <div class="decode-item">
        <div class="decode-item-header">
          <span class="decode-badge">${esc(r.method)}</span>
          <span class="decode-input-label">← ${esc(r.input.slice(0, 60))}${r.input.length > 60 ? '…' : ''}</span>
        </div>
        <div class="decode-result">${esc(r.decoded)}</div>
        ${r.ips && r.ips.length > 0 ? `<div style="font-size:.78rem;color:var(--muted);margin-bottom:.25rem">IPs found:</div>${ipsHtml}` : ''}
      </div>`;
  }).join('');
}

decodeForm.addEventListener('submit', async e => {
  e.preventDefault();
  const input  = decodeInput.value.trim();
  const method = decodeMethod.value;
  if (!input) return;

  hide(decodeError);
  hide(decodeResults);
  show(decodeLoading);
  decodeBtn.disabled = true;

  try {
    const res  = await fetch('/api/decode', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ input, method })
    });
    const data = await res.json();
    hide(decodeLoading);
    decodeBtn.disabled = false;

    if (data.error) {
      decodeError.textContent = data.error;
      show(decodeError);
      return;
    }

    lastDecodeData = data;
    decodeFoundIPs = [...new Set(data.results.flatMap(r => r.ips || []))];
    
    // Feed into reactive chain: Decode → Traceback → Classify → Geolocate
    window.ipTracebackHub.onDecodeResult(input, decodeFoundIPs);

    decodeList.innerHTML = renderDecodeResult(data);

    // Show "Trace IPs" button only when IPs were actually found
    if (decodeFoundIPs.length > 0) {
      decodeTraceBtn.textContent = `\u{1F3AF} Trace ${decodeFoundIPs.length} IP${decodeFoundIPs.length > 1 ? 's' : ''}`;
      show(decodeTraceBtn);
    } else {
      hide(decodeTraceBtn);
    }

    hide(decodeTraceSection);
    show(decodeResults);
    decodeResults.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    hide(decodeLoading);
    decodeBtn.disabled = false;
    decodeError.textContent = 'Failed to connect to server.';
    show(decodeError);
  }
});

decodeExport.addEventListener('click', () => {
  if (lastDecodeData) exportJSON(lastDecodeData, 'decode-result.json');
});

/* Trace IPs button — geolocate + classify all IPs found in decode output */
async function traceIPs(ips, loadingEl, errorEl, gridEl, sectionEl) {
  hide(errorEl);
  gridEl.innerHTML = '';
  show(loadingEl);
  show(sectionEl);
  sectionEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  try {
    const res  = await fetch('/api/trace', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ips })
    });
    const data = await res.json();
    hide(loadingEl);

    if (data.error) { errorEl.textContent = data.error; show(errorEl); return; }

    gridEl.innerHTML = data.results.map(entry => buildTraceCard(entry)).join('');
  } catch (err) {
    hide(loadingEl);
    errorEl.textContent = 'Failed to connect to server.';
    show(errorEl);
  }
}

decodeTraceBtn.addEventListener('click', () => {
  if (decodeFoundIPs.length > 0) {
    traceIPs(decodeFoundIPs, decodeTraceLoading, decodeTraceError, decodeTraceGrid, decodeTraceSection);
  }
});

/* ══════════════════════════════════════════════════════════════════════
   TRACEBACK TAB
   ══════════════════════════════════════════════════════════════════════ */
let lastTracebackData = null;

const tracebackForm    = document.getElementById('traceback-form');
const tbBtn            = document.getElementById('traceback-btn');
const tbLoading        = document.getElementById('traceback-loading');
const tbLoadingText    = document.getElementById('traceback-loading-text');
const tbError          = document.getElementById('traceback-error');
const tbResults        = document.getElementById('traceback-results');
const tbExport         = document.getElementById('traceback-export-btn');

function renderSourceIPs(sourceIPs) {
  if (!sourceIPs || sourceIPs.length === 0) return;

  // Separate destination IPs (from decode/dns/scrape) vs user's own IP (from public-ip services)
  const ownIPSources  = new Set(['ipify','icanhazip','ident','myip','jsonip']);
  const destIPs  = [...new Map(
    sourceIPs.filter(e => !ownIPSources.has(e.source)).map(e => [e.ip, e])
  ).values()];
  const ownIPs   = [...new Map(
    sourceIPs.filter(e =>  ownIPSources.has(e.source)).map(e => [e.ip, e])
  ).values()];

  if (destIPs.length > 0) {
    const block = document.getElementById('tb-source-block');
    const list  = document.getElementById('tb-source-list');
    list.innerHTML = destIPs.map(e =>
      `<span class="dns-ip-tag" title="Source: ${esc(e.source)}">${esc(e.ip)}</span>`
    ).join('');
    show(block);
  }

  if (ownIPs.length > 0) {
    const block = document.getElementById('tb-ownip-block');
    const list  = document.getElementById('tb-ownip-list');
    list.innerHTML = ownIPs.map(e =>
      `<span class="dns-ip-tag" style="border-color:var(--muted);color:var(--muted)" title="via ${esc(e.source)}">${esc(e.ip)}</span>`
    ).join('');
    show(block);
  }
}

function renderDecodedIPs(decodedIPs) {
  if (!decodedIPs || decodedIPs.length === 0) return;
  const block = document.getElementById('tb-decoded-block');
  const list  = document.getElementById('tb-decoded-list');
  list.innerHTML = decodedIPs.map(r => {
    const ipsHtml = r.ips && r.ips.length > 0
      ? `<div class="decode-ips">${r.ips.map(ip => `<span class="ip-chip">${esc(ip)}</span>`).join('')}</div>`
      : '';
    return `<div class="decode-item">
      <div class="decode-item-header">
        <span class="decode-badge">${esc(r.method)}</span>
        <span class="decode-input-label">← ${esc(r.input.slice(0,60))}${r.input.length>60?'…':''}</span>
      </div>
      <div class="decode-result">${esc(r.decoded)}</div>
      ${ipsHtml}
    </div>`;
  }).join('');
  show(block);
}

function renderDNS(dnsResults) {
  if (!dnsResults) return;
  const block = document.getElementById('tb-dns-block');
  const list  = document.getElementById('tb-dns-list');
  const items = Array.isArray(dnsResults) ? dnsResults : [dnsResults];
  list.innerHTML = items.map(dns => `
    <div class="dns-record-block" style="margin-bottom:.5rem">
      <div class="dns-record-type">${esc(dns.hostname)}</div>
      ${dns.ips && dns.ips.length ? `<div class="tag-list">${dns.ips.map(ip=>`<span class="dns-ip-tag">${esc(ip)}</span>`).join('')}</div>` : ''}
    </div>`).join('');
  show(block);
}

function renderClassifications(classifications) {
  if (!classifications || classifications.length === 0) return;
  const block = document.getElementById('tb-classify-block');
  const list  = document.getElementById('tb-classify-list');
  list.innerHTML = classifications.map(c => buildClassifyCard(c)).join('');
  show(block);
}

function renderGeolocations(geolocations) {
  if (!geolocations || geolocations.length === 0) return;
  const block = document.getElementById('tb-geo-block');
  const list  = document.getElementById('tb-geo-list');
  // Use richer trace cards when available, fall back to simple geo cards
  list.innerHTML = geolocations.map(g => buildGeoCard(g)).join('');
  show(block);
}

function renderErrors(errors) {
  if (!errors || errors.length === 0) return;
  const block = document.getElementById('tb-errors-block');
  const list  = document.getElementById('tb-errors-list');
  list.innerHTML = errors.map(e => `<li>${esc(e)}</li>`).join('');
  show(block);
}

tracebackForm.addEventListener('submit', async e => {
  e.preventDefault();

  const encodedInputs  = document.getElementById('tb-encoded').value.trim().split('\n').map(s=>s.trim()).filter(Boolean);
  const hostnames      = document.getElementById('tb-hostnames').value.trim().split('\n').map(s=>s.trim()).filter(Boolean);
  const scrapeURLs     = document.getElementById('tb-urls').value.trim().split('\n').map(s=>s.trim()).filter(Boolean);
  const discoverPublic = document.getElementById('tb-discover').checked;
  const geolocate      = document.getElementById('tb-geolocate').checked;

  hide(tbError);
  hide(tbResults);
  // Hide all result sub-blocks
  ['tb-source-block','tb-ownip-block','tb-decoded-block','tb-dns-block','tb-classify-block','tb-geo-block','tb-errors-block']
    .forEach(id => hide(document.getElementById(id)));

  show(tbLoading);
  tbBtn.disabled = true;
  tbLoadingText.textContent = 'Running pipeline…';

  try {
    const res  = await fetch('/api/traceback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ encodedInputs, hostnames, scrapeURLs, discoverPublic, geolocate })
    });
    const data = await res.json();
    hide(tbLoading);
    tbBtn.disabled = false;

    if (data.error) { tbError.textContent = data.error; show(tbError); return; }

    lastTracebackData = data;

    // Normalize dnsResults: server returns object (single) or null, never array
    const dnsArr = data.dnsResults
      ? (Array.isArray(data.dnsResults) ? data.dnsResults : [data.dnsResults])
      : [];

    // Feed all discovered IPs into reactive chain: Traceback → Classify → Geolocate
    const allTbIPs = [
      ...(data.sourceIPs || []).map(e => e.ip),
      ...dnsArr.flatMap(dns => dns.ips || [])
    ];
    window.ipTracebackHub.onTracebackResult(allTbIPs);

    renderSourceIPs(data.sourceIPs);
    renderDecodedIPs(data.decodedIPs);
    renderDNS(data.dnsResults);
    renderClassifications(data.classifications);
    renderGeolocations(data.geolocations);
    renderErrors(data.errors);
    show(tbResults);
    tbResults.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    hide(tbLoading);
    tbBtn.disabled = false;
    tbError.textContent = err.message || 'Failed to connect to server.';
    show(tbError);
  }
});

tbExport.addEventListener('click', () => {
  if (lastTracebackData) exportJSON(lastTracebackData, 'traceback-result.json');
});

/* ══════════════════════════════════════════════════════════════════════
   DNS TAB
   ══════════════════════════════════════════════════════════════════════ */
let lastDNSData = null;

const dnsForm    = document.getElementById('dns-form');
const dnsInput   = document.getElementById('dns-input');
const dnsBtn     = document.getElementById('dns-btn');
const dnsLoading = document.getElementById('dns-loading');
const dnsError   = document.getElementById('dns-error');
const dnsResults = document.getElementById('dns-results');
const dnsExport  = document.getElementById('dns-export-btn');

dnsForm.addEventListener('submit', async e => {
  e.preventDefault();
  const hostname = dnsInput.value.trim().toLowerCase();
  if (!hostname) return;

  hide(dnsError);
  hide(dnsResults);
  show(dnsLoading);
  dnsBtn.disabled = true;

  try {
    const res  = await fetch(`/api/dns/${encodeURIComponent(hostname)}`);
    const data = await res.json();
    hide(dnsLoading);
    dnsBtn.disabled = false;

    if (data.error) { dnsError.textContent = data.error; show(dnsError); return; }

    lastDNSData = data;
    document.getElementById('dns-hostname-label').textContent = data.hostname || hostname;

    // Feed A-record IPs into reactive chain: DNS → Classify → Geolocate → Traceback
    window.ipTracebackHub.onDNSResult(hostname, data.ips || []);

    // A records
    const aBlock = document.getElementById('dns-a-block');
    const aList  = document.getElementById('dns-a-list');
    if (data.ips && data.ips.length > 0) {
      aList.innerHTML = data.ips.map(ip => `<span class="dns-ip-tag">${esc(ip)}</span>`).join('');
      show(aBlock);
    } else { hide(aBlock); }

    // MX records
    const mxBlock = document.getElementById('dns-mx-block');
    const mxTbody = document.querySelector('#dns-mx-table tbody');
    if (data.mxRecords && data.mxRecords.length > 0) {
      mxTbody.innerHTML = data.mxRecords
        .sort((a,b) => a.priority - b.priority)
        .map(mx => `<tr><td>${esc(String(mx.priority))}</td><td>${esc(mx.exchange)}</td></tr>`).join('');
      show(mxBlock);
    } else { hide(mxBlock); }

    // TXT records
    const txtBlock = document.getElementById('dns-txt-block');
    const txtList  = document.getElementById('dns-txt-list');
    if (data.txtRecords && data.txtRecords.length > 0) {
      txtList.innerHTML = data.txtRecords
        .map(t => `<div class="txt-entry">${esc(Array.isArray(t) ? t.join('') : t)}</div>`).join('');
      show(txtBlock);
    } else { hide(txtBlock); }

    // NS records
    const nsBlock = document.getElementById('dns-ns-block');
    const nsList  = document.getElementById('dns-ns-list');
    if (data.nsRecords && data.nsRecords.length > 0) {
      nsList.innerHTML = data.nsRecords.map(ns => `<span class="dns-ip-tag">${esc(ns)}</span>`).join('');
      show(nsBlock);
    } else { hide(nsBlock); }

    // Warnings
    const warnBlock = document.getElementById('dns-warn-block');
    if (data.errors && data.errors.length > 0) {
      warnBlock.innerHTML = data.errors.map(e => `<div>⚠ ${esc(e)}</div>`).join('');
      show(warnBlock);
    } else { hide(warnBlock); }

    // Cache badge
    const cacheBadge = document.getElementById('dns-cache-badge');
    data.fromCache ? show(cacheBadge) : hide(cacheBadge);

    show(dnsResults);
    dnsResults.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    hide(dnsLoading);
    dnsBtn.disabled = false;
    dnsError.textContent = 'Failed to connect to server.';
    show(dnsError);
  }
});

dnsExport.addEventListener('click', () => {
  if (lastDNSData) exportJSON(lastDNSData, `dns-${lastDNSData.hostname || 'result'}.json`);
});

/* ══════════════════════════════════════════════════════════════════════
   CLASSIFY TAB
   ══════════════════════════════════════════════════════════════════════ */
let lastClassifyData = null;

const classifyForm    = document.getElementById('classify-form');
const classifyInput   = document.getElementById('classify-input');
const classifyBtn     = document.getElementById('classify-btn');
const classifyError   = document.getElementById('classify-error');
const classifyResults = document.getElementById('classify-results');
const classifyGrid    = document.getElementById('classify-grid');
const classifyExport  = document.getElementById('classify-export-btn');

function buildClassifyCard(c) {
  if (!c.valid) {
    return `<div class="classify-card invalid">
      <div class="classify-card-header">
        <span class="classify-ip">${esc(c.ip)}</span>
        <span class="classify-version-badge badge-invalid">Invalid</span>
      </div>
    </div>`;
  }

  const badgeClass = c.version === 4 ? 'badge-v4' : 'badge-v6';
  const badgeLabel = `IPv${c.version}`;

  let rows = '';
  if (c.version === 4) {
    const priv = c.isPrivate
      ? '<span class="badge-private">⬤ Private</span>'
      : '<span class="badge-public">⬤ Public</span>';
    rows = `
      <div class="classify-row"><span class="classify-row-key">Status</span><span class="classify-row-value">${priv}</span></div>
      <div class="classify-row"><span class="classify-row-key">Class</span><span class="classify-row-value">${esc(c.class)}</span></div>
      <div class="classify-row"><span class="classify-row-key">Numeric</span><span class="classify-row-value">${esc(String(c.numeric))}</span></div>
      <div class="classify-row"><span class="classify-row-key">Hex</span><span class="classify-row-value">${esc(c.hex)}</span></div>
      <div class="classify-row"><span class="classify-row-key">Binary</span><span class="classify-row-value" style="font-size:.72rem">${esc(c.binary)}</span></div>`;
  } else {
    rows = `
      <div class="classify-row"><span class="classify-row-key">Loopback</span><span class="classify-row-value">${c.isLoopback ? 'Yes' : 'No'}</span></div>
      <div class="classify-row"><span class="classify-row-key">Expanded</span><span class="classify-row-value" style="font-size:.72rem">${esc(c.expanded)}</span></div>`;
  }

  return `<div class="classify-card">
    <div class="classify-card-header">
      <span class="classify-ip">${esc(c.ip)}</span>
      <span class="classify-version-badge ${badgeClass}">${badgeLabel}</span>
    </div>
    <div class="classify-rows">${rows}</div>
  </div>`;
}

classifyForm.addEventListener('submit', async e => {
  e.preventDefault();
  const raw = classifyInput.value.trim();
  if (!raw) return;

  // Parse IPs (newline or comma separated)
  const ips = raw.split(/[\n,]+/).map(s => s.trim()).filter(Boolean);
  if (ips.length === 0) return;

  hide(classifyError);
  hide(classifyResults);

  try {
    const results = await Promise.all(ips.map(ip =>
      fetch(`/api/classify/${encodeURIComponent(ip)}`).then(r => r.json())
    ));

    lastClassifyData = results;

    // Feed into reactive chain: Classify → Geolocate
    window.ipTracebackHub.onClassifyResult(results);

    classifyGrid.innerHTML = results.map(c => buildClassifyCard(c)).join('');
    show(classifyResults);
    classifyResults.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    classifyError.textContent = 'Failed to connect to server.';
    show(classifyError);
  }
});

classifyExport.addEventListener('click', () => {
  if (lastClassifyData) exportJSON(lastClassifyData, 'classify-result.json');
});

/* ══════════════════════════════════════════════════════════════════════
   GEOLOCATE TAB
   ══════════════════════════════════════════════════════════════════════ */
let lastGeoData = null;

const geoForm    = document.getElementById('geo-form');
const geoInput   = document.getElementById('geo-input');
const geoBtn     = document.getElementById('geo-btn');
const geoLoading = document.getElementById('geo-loading');
const geoError   = document.getElementById('geo-error');
const geoResults = document.getElementById('geo-results');
const geoExport  = document.getElementById('geo-export-btn');

function buildGeoCard(g) {
  if (!g.success) {
    return `<div class="geo-card geo-error">
      <div class="geo-card-ip">${esc(g.ip)}</div>
      <div style="font-size:.82rem;color:var(--red)">${esc(g.error)}</div>
    </div>`;
  }
  const flag = countryFlag(g.countryCode);
  return `<div class="geo-card">
    <div class="geo-card-ip">${esc(g.ip)}</div>
    <table>
      <tr><td>Country</td><td>${flag} ${esc(g.country||'?')} (${esc(g.countryCode||'?')})</td></tr>
      <tr><td>City</td><td>${esc(g.city||'?')}, ${esc(g.region||'?')}</td></tr>
      <tr><td>ISP</td><td>${esc(g.isp||'?')}</td></tr>
      <tr><td>ASN</td><td>${esc(g.asn||'?')}</td></tr>
      ${g.isProxy!=null?`<tr><td>Proxy</td><td>${g.isProxy?'Yes':'No'}</td></tr>`:''}
    </table>
  </div>`;
}

geoForm.addEventListener('submit', async e => {
  e.preventDefault();
  const ip = geoInput.value.trim();
  if (!ip) return;

  hide(geoError);
  hide(geoResults);
  show(geoLoading);
  geoBtn.disabled = true;

  try {
    const res  = await fetch(`/api/geolocate/${encodeURIComponent(ip)}`);
    const data = await res.json();
    hide(geoLoading);
    geoBtn.disabled = false;

    if (data.error && !data.success) {
      geoError.textContent = data.error;
      show(geoError);
      return;
    }

    lastGeoData = data;

    setCell('geo-ip',      data.ip);
    setCell('geo-rdns',    data.reverseDNS);
    setCell('geo-isp',     data.isp);
    setCell('geo-org',     data.org);
    setCell('geo-asn',     data.asn);

    const proxyEl   = document.getElementById('geo-proxy');
    const hostingEl = document.getElementById('geo-hosting');
    if (proxyEl) {
      proxyEl.innerHTML = data.isProxy != null
        ? `<span class="pill ${data.isProxy ? 'pill-warn' : 'pill-no'}">${data.isProxy ? 'Yes' : 'No'}</span>` : '—';
    }
    if (hostingEl) {
      hostingEl.innerHTML = data.isHosting != null
        ? `<span class="pill ${data.isHosting ? 'pill-warn' : 'pill-no'}">${data.isHosting ? 'Yes' : 'No'}</span>` : '—';
    }

    const countryText = data.country
      ? `${countryFlag(data.countryCode)} ${data.country} (${data.countryCode})`
      : null;
    setCell('geo-country', countryText);
    setCell('geo-region',  data.region ? `${data.region}${data.regionCode ? ` (${data.regionCode})` : ''}` : null);
    setCell('geo-city',    data.city);
    setCell('geo-zip',     data.zip);
    setCell('geo-lat',     data.lat  != null ? `${data.lat}°`  : null);
    setCell('geo-lon',     data.lon  != null ? `${data.lon}°`  : null);
    setCell('geo-tz',      data.timezone);

    const mapRow  = document.getElementById('geo-map-row');
    const mapLink = document.getElementById('geo-map-link');
    if (data.lat != null && data.lon != null) {
      mapLink.href = `https://www.openstreetmap.org/?mlat=${data.lat}&mlon=${data.lon}&zoom=10`;
      show(mapRow);
    } else { hide(mapRow); }

    const cacheBadge = document.getElementById('geo-cache-badge');
    data.fromCache ? show(cacheBadge) : hide(cacheBadge);

    setupCopyables(geoResults);
    show(geoResults);
    geoResults.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    hide(geoLoading);
    geoBtn.disabled = false;
    geoError.textContent = 'Failed to connect to server.';
    show(geoError);
  }
});

geoExport.addEventListener('click', () => {
  if (lastGeoData) exportJSON(lastGeoData, `geo-${lastGeoData.ip || 'result'}.json`);
});
