'use strict';

/* ── DOM refs ────────────────────────────────────────────────────────── */
const form              = document.getElementById('lookup-form');
const input             = document.getElementById('query-input');
const batchInput        = document.getElementById('batch-input');
const inputError        = document.getElementById('input-error');
const lookupBtn         = document.getElementById('lookup-btn');
const batchLookupBtn    = document.getElementById('batch-lookup-btn');
const loading           = document.getElementById('loading');
const loadingText       = document.getElementById('loading-text');
const errorBanner       = document.getElementById('error-banner');
const resultsSection    = document.getElementById('results-section');
const batchResultsSection = document.getElementById('batch-results-section');
const batchResultsList  = document.getElementById('batch-results-list');
const batchProgress     = document.getElementById('batch-progress');
const mapLinkRow        = document.getElementById('map-link-row');
const mapLink           = document.getElementById('map-link');
const cacheIndicator    = document.getElementById('cache-indicator');
const warningIndicator  = document.getElementById('warning-indicator');
const exportBtn         = document.getElementById('export-btn');
const batchExportBtn    = document.getElementById('batch-export-btn');
const formTabs          = document.querySelectorAll('.form-tab');
const formTabContents   = document.querySelectorAll('.form-tab-content');

let lastLookupData = null;
let lastBatchResults = [];

/* ── Tab switching ───────────────────────────────────────────────────── */
formTabs.forEach(tab => {
  tab.addEventListener('click', (e) => {
    e.preventDefault();
    const targetTab = tab.dataset.tab;
    
    formTabs.forEach(t => t.classList.remove('active'));
    formTabContents.forEach(c => c.classList.remove('active'));
    
    tab.classList.add('active');
    document.getElementById(`tab-${targetTab}`).classList.add('active');
  });
});

/* ── Example chips ───────────────────────────────────────────────────── */
document.querySelectorAll('.example-chip').forEach(chip => {
  chip.addEventListener('click', (e) => {
    e.preventDefault();
    input.value = chip.dataset.value;
    input.focus();
    inputError.textContent = '';
  });
});

/* ── Helpers ─────────────────────────────────────────────────────────── */
function show(el)  { el.classList.remove('hidden'); }
function hide(el)  { el.classList.add('hidden'); }
function na(val)   { return (val !== null && val !== undefined && val !== '') ? val : null; }

function setCell(id, value, extraClass) {
  const td = document.getElementById(id);
  if (!td) return;
  const v = na(value);
  if (v !== null) {
    td.textContent = v;
    td.className = extraClass || '';
    td.dataset.copyValue = v.replace(/°/g, '').trim(); // Store raw value for copy
  } else {
    td.textContent = '—';
    td.className = 'na';
    td.dataset.copyValue = '';
  }
}

/* Convert country code to flag emoji */
function countryFlag(code) {
  if (!code || code.length !== 2) return '';
  const points = [...code.toUpperCase()].map(c => 127397 + c.charCodeAt(0));
  return String.fromCodePoint(...points);
}

/* Debounce helper */
function debounce(func, wait) {
  let timeout;
  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
}

/* Copy to clipboard */
function copyToClipboard(text) {
  navigator.clipboard.writeText(text).then(() => {
    showToast('Copied to clipboard!');
  }).catch(err => {
    console.error('Failed to copy:', err);
  });
}

function showToast(msg) {
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.textContent = msg;
  document.body.appendChild(toast);
  setTimeout(() => toast.classList.add('show'), 10);
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => document.body.removeChild(toast), 300);
  }, 2500);
}

/* ── Copy button setup ────────────────────────────────────────────────── */
function setupCopyButtons() {
  document.querySelectorAll('.copyable').forEach(cell => {
    cell.style.cursor = 'pointer';
    cell.title = 'Click to copy';
    cell.addEventListener('click', () => {
      const value = cell.dataset.copyValue || cell.textContent;
      if (value && value !== '—') {
        copyToClipboard(value);
      }
    });
  });
}

/* ── Export to JSON ──────────────────────────────────────────────────── */
function exportAsJSON(data, filename = 'lookup-result.json') {
  const json = JSON.stringify(data, null, 2);
  const blob = new Blob([json], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

exportBtn.addEventListener('click', () => {
  if (lastLookupData) {
    const filename = `lookup-${lastLookupData.resolvedIP || lastLookupData.inputProvided}.json`;
    exportAsJSON(lastLookupData, filename);
  }
});

batchExportBtn.addEventListener('click', () => {
  if (lastBatchResults.length > 0) {
    exportAsJSON(lastBatchResults, 'batch-lookup-results.json');
  }
});

/* ── Main single lookup ──────────────────────────────────────────────── */
async function doLookup(query) {
  inputError.textContent = '';
  hide(errorBanner);
  hide(resultsSection);
  hide(batchResultsSection);
  hide(mapLinkRow);
  hide(cacheIndicator);
  hide(warningIndicator);
  show(loading);
  loadingText.textContent = 'Looking up location…';
  lookupBtn.disabled = true;

  try {
    const res  = await fetch(`/api/lookup?q=${encodeURIComponent(query)}`);
    const data = await res.json();

    hide(loading);
    lookupBtn.disabled = false;

    if (data.error) {
      errorBanner.textContent = data.error;
      if (data.warning) show(warningIndicator);
      show(errorBanner);
      return;
    }

    lastLookupData = data;

    // Show cache indicator
    if (data.fromCache) {
      show(cacheIndicator);
    }

    // Show warning if present
    if (data.warning) {
      show(warningIndicator);
    }

    // Populate identity column
    setCell('r-input',    data.inputProvided);
    setCell('r-type',     data.inputType);
    setCell('r-hostname', data.resolvedHostname);
    setCell('r-ip',       data.resolvedIP);

    const orgText = [data.organization, data.isp]
      .filter(Boolean)
      .filter((v, i, a) => a.indexOf(v) === i)
      .join(' / ') || null;
    setCell('r-org',     orgText);
    setCell('r-asn',     data.asn);
    setCell('r-asnname', data.asnName);

    // Populate location column
    const countryText = data.country
      ? `${countryFlag(data.countryCode)} ${data.country}` : null;
    setCell('r-country', countryText);

    const stateText = (data.state && data.stateCode && data.stateCode !== data.state)
      ? `${data.state} (${data.stateCode})`
      : (data.state || null);
    setCell('r-state',  stateText);
    setCell('r-city',   data.city);
    setCell('r-postal', data.postalCode);

    setCell('r-lat', data.latitude  !== null ? `${data.latitude}°`  : null);
    setCell('r-lon', data.longitude !== null ? `${data.longitude}°` : null);
    setCell('r-tz',  data.timezone);

    // Map link
    if (data.latitude !== null && data.longitude !== null) {
      mapLink.href = `https://www.openstreetmap.org/?mlat=${data.latitude}&mlon=${data.longitude}&zoom=10`;
      show(mapLinkRow);
    }

    setupCopyButtons();
    show(resultsSection);
    resultsSection.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    console.error('Lookup error:', err);
    hide(loading);
    lookupBtn.disabled = false;
    errorBanner.textContent = 'Failed to connect to the lookup service. Please try again.';
    show(errorBanner);
  }
}

/* ── Batch lookup ────────────────────────────────────────────────────── */
async function doBatchLookup(queries) {
  const queryList = queries
    .split('\n')
    .map(q => q.trim())
    .filter(q => q.length > 0)
    .slice(0, 50); // Max 50

  if (queryList.length === 0) {
    inputError.textContent = 'Please enter at least one IP or domain.';
    return;
  }

  inputError.textContent = '';
  hide(errorBanner);
  hide(resultsSection);
  hide(batchResultsSection);
  show(loading);
  loadingText.textContent = `Looking up ${queryList.length} item(s)…`;
  batchLookupBtn.disabled = true;

  lastBatchResults = [];
  batchResultsList.innerHTML = '';

  for (let i = 0; i < queryList.length; i++) {
    const query = queryList[i];
    batchProgress.textContent = `${i + 1} of ${queryList.length}`;

    try {
      const res = await fetch(`/api/lookup?q=${encodeURIComponent(query)}`);
      const data = await res.json();

      lastBatchResults.push({
        input: query,
        result: data
      });

      // Add to results list
      const itemDiv = document.createElement('div');
      itemDiv.className = 'batch-result-item';
      
      if (data.error) {
        itemDiv.innerHTML = `<strong>${escapeHtml(query)}</strong><span class="batch-error">${escapeHtml(data.error)}</span>`;
      } else {
        const country = data.country ? `${countryFlag(data.countryCode)} ${data.country}` : 'Unknown';
        const city = data.city || 'Unknown';
        const ip = data.resolvedIP || query;
        itemDiv.innerHTML = `
          <strong>${escapeHtml(query)}</strong>
          <span class="batch-info">${escapeHtml(ip)} • ${escapeHtml(city)}, ${escapeHtml(country)}</span>
        `;
      }
      
      batchResultsList.appendChild(itemDiv);

    } catch (err) {
      console.error('Batch lookup error for', query, err);
      lastBatchResults.push({
        input: query,
        result: { error: 'Connection failed' }
      });
      
      const itemDiv = document.createElement('div');
      itemDiv.className = 'batch-result-item';
      itemDiv.innerHTML = `<strong>${escapeHtml(query)}</strong><span class="batch-error">Connection failed</span>`;
      batchResultsList.appendChild(itemDiv);
    }

    // Rate limit: 45ms between requests
    await new Promise(r => setTimeout(r, 45));
  }

  hide(loading);
  batchLookupBtn.disabled = false;
  batchProgress.textContent = '';
  show(batchResultsSection);
  batchResultsSection.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/* ── Form submission ─────────────────────────────────────────────────── */
form.addEventListener('submit', e => {
  e.preventDefault();
  
  const activeTab = document.querySelector('.form-tab-content.active');
  
  if (activeTab.id === 'tab-single') {
    const q = input.value.trim();
    if (!q) {
      inputError.textContent = 'Please enter an IP address, domain, or URL.';
      input.focus();
      return;
    }
    doLookup(q);
  } else if (activeTab.id === 'tab-batch') {
    const batch = batchInput.value.trim();
    if (!batch) {
      inputError.textContent = 'Please enter at least one IP or domain.';
      batchInput.focus();
      return;
    }
    doBatchLookup(batch);
  }
});

/* ── Debounced input validation ──────────────────────────────────────── */
const validateInput = debounce(() => {
  inputError.textContent = '';
}, 500);

input.addEventListener('input', validateInput);
