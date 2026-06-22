'use strict';

/* ── DOM refs ────────────────────────────────────────────────────────── */
const form        = document.getElementById('lookup-form');
const input       = document.getElementById('query-input');
const inputError  = document.getElementById('input-error');
const lookupBtn   = document.getElementById('lookup-btn');
const loading     = document.getElementById('loading');
const errorBanner = document.getElementById('error-banner');
const resultsSection = document.getElementById('results-section');
const mapLinkRow  = document.getElementById('map-link-row');
const mapLink     = document.getElementById('map-link');

/* ── Example chips ───────────────────────────────────────────────────── */
document.querySelectorAll('.example-chip').forEach(chip => {
  chip.addEventListener('click', () => {
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
    td.className   = extraClass || '';
  } else {
    td.textContent = '—';
    td.className   = 'na';
  }
}

/* Convert country code to flag emoji (works in all modern browsers) */
function countryFlag(code) {
  if (!code || code.length !== 2) return '';
  const points = [...code.toUpperCase()].map(c => 127397 + c.charCodeAt(0));
  return String.fromCodePoint(...points);
}

/* ── Main lookup ─────────────────────────────────────────────────────── */
async function doLookup(query) {
  // Reset state
  inputError.textContent = '';
  hide(errorBanner);
  hide(resultsSection);
  hide(mapLinkRow);
  show(loading);
  lookupBtn.disabled = true;

  try {
    const res  = await fetch(`/api/lookup?q=${encodeURIComponent(query)}`);
    const data = await res.json();

    hide(loading);
    lookupBtn.disabled = false;

    if (data.error) {
      errorBanner.textContent = data.error;
      show(errorBanner);
      return;
    }

    // Populate identity column
    setCell('r-input',    data.inputProvided);
    setCell('r-type',     data.inputType);
    setCell('r-hostname', data.resolvedHostname);
    setCell('r-ip',       data.resolvedIP);

    const orgText = [data.organization, data.isp]
      .filter(Boolean)
      .filter((v, i, a) => a.indexOf(v) === i)   // dedupe
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

    show(resultsSection);
    resultsSection.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  } catch (err) {
    hide(loading);
    lookupBtn.disabled = false;
    errorBanner.textContent = 'Failed to connect to the lookup service. Please try again.';
    show(errorBanner);
  }
}

/* ── Form submission ─────────────────────────────────────────────────── */
form.addEventListener('submit', e => {
  e.preventDefault();
  const q = input.value.trim();
  if (!q) {
    inputError.textContent = 'Please enter an IP address, domain, or URL.';
    input.focus();
    return;
  }
  doLookup(q);
});
