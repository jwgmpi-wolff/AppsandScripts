'use strict';

const express = require('express');
const fetch   = require('node-fetch');
const path    = require('path');
const { URL } = require('url');

const app  = express();
const PORT = process.env.PORT || 3000;

app.use(express.static(path.join(__dirname, 'public')));

/* ── Cache ────────────────────────────────────────────────────────── */
const CACHE_TTL = 3600 * 1000; // 1 hour
const MAX_CACHE_SIZE = 1000;
const cache = new Map();

function getCached(key) {
  const entry = cache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.timestamp > CACHE_TTL) {
    cache.delete(key);
    return null;
  }
  return entry.data;
}

function setCached(key, data) {
  if (cache.size >= MAX_CACHE_SIZE) {
    const firstKey = cache.keys().next().value;
    cache.delete(firstKey);
  }
  cache.set(key, { data, timestamp: Date.now() });
}

/* ── Retry logic ─────────────────────────────────────────────────── */
async function fetchWithRetry(url, maxAttempts = 3, timeout = 8000) {
  let lastError;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await fetch(url, { timeout });
      if (res.status === 429) {
        // Rate limited — wait and retry
        const waitMs = Math.min(1000 * Math.pow(2, attempt - 1), 10000);
        console.warn(`Rate limited. Waiting ${waitMs}ms before retry...`);
        await new Promise(r => setTimeout(r, waitMs));
        continue;
      }
      return res;
    } catch (err) {
      lastError = err;
      if (attempt < maxAttempts) {
        const waitMs = 500 * Math.pow(2, attempt - 1);
        console.warn(`Attempt ${attempt} failed, retrying in ${waitMs}ms...`);
        await new Promise(r => setTimeout(r, waitMs));
      }
    }
  }
  throw lastError || new Error('Max retries exceeded');
}

/* ── Fallback geolocation provider ───────────────────────────────── */
async function lookupFallback(host) {
  // Simple fallback: just return basic DNS resolution
  // In production, you could use a secondary service
  try {
    const { lookup } = require('dns').promises;
    const ip = await lookup(host).then(r => r.address);
    return { query: ip, status: 'fallback' };
  } catch {
    throw new Error('Fallback lookup failed');
  }
}

/**
 * Extract a host/IP from whatever the user typed.
 * Accepts bare IPs, bare hostnames, and full URLs (http/https).
 */
function extractHost(input) {
  const trimmed = input.trim();
  if (!trimmed) return null;

  // If it looks like a URL with a scheme, parse it properly
  if (/^https?:\/\//i.test(trimmed)) {
    try {
      return new URL(trimmed).hostname;
    } catch {
      return null;
    }
  }

  // Strip any trailing path/query the user might have pasted
  const hostPart = trimmed.split('/')[0].split('?')[0].split('#')[0];

  // Basic sanity: must contain at least one alphanumeric character
  if (!/[a-zA-Z0-9]/.test(hostPart)) return null;

  return hostPart;
}

/**
 * Check if IP is private/reserved
 */
function isPrivateIP(ip) {
  if (!ip) return false;
  return /^(127\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.|169\.254\.|::1|fc00:|fe80:)/.test(ip);
}

/**
 * Determine input type (IPv4, IPv6, Domain)
 */
function getInputType(host) {
  if (/^(\d{1,3}\.){3}\d{1,3}$/.test(host)) {
    return 'IPv4 Address';
  } else if (/^[\da-f:]+:[\da-f:]+$/i.test(host)) {
    return 'IPv6 Address';
  } else {
    return 'Domain / URL';
  }
}

// Geolocation proxy — keeps API calls server-side
app.get('/api/lookup', async (req, res) => {
  const raw = (req.query.q || '').trim();
  if (!raw) {
    return res.status(400).json({ error: 'No input provided.' });
  }

  const host = extractHost(raw);
  if (!host) {
    return res.status(400).json({
      error: 'Invalid IP address or URL. Please enter a valid public IP address or website URL.'
    });
  }

  // Check for private/reserved IPs
  if (isPrivateIP(host)) {
    return res.status(200).json({
      error: 'This is a private or reserved IP address. Geolocation data is not available for private networks.',
      inputType: getInputType(host)
    });
  }

  // Check cache
  const cacheKey = `lookup:${host.toLowerCase()}`;
  const cached = getCached(cacheKey);
  if (cached) {
    return res.json({ ...cached, fromCache: true });
  }

  const fields = [
    'status', 'message',
    'query', 'reverse',
    'country', 'countryCode',
    'regionName', 'region',
    'city', 'zip',
    'lat', 'lon',
    'timezone',
    'isp', 'org', 'as', 'asname'
  ].join(',');

  const apiUrl = `http://ip-api.com/json/${encodeURIComponent(host)}?fields=${fields}`;

  try {
    const apiRes = await fetchWithRetry(apiUrl);
    const data = await apiRes.json();

    if (data.status === 'fail') {
      return res.status(200).json({
        error: 'Location information is unavailable for this IP address or website.',
        raw: data.message || null,
        inputType: getInputType(host)
      });
    }

    const result = {
      inputProvided:    raw,
      inputType:        getInputType(host),
      resolvedHostname: data.reverse  || null,
      resolvedIP:       data.query    || null,
      isp:              data.isp      || null,
      organization:     data.org      || null,
      country:          data.country  || null,
      countryCode:      data.countryCode || null,
      state:            data.regionName  || null,
      stateCode:        data.region   || null,
      city:             data.city     || null,
      postalCode:       data.zip      || null,
      latitude:         data.lat      ?? null,
      longitude:        data.lon      ?? null,
      timezone:         data.timezone || null,
      asn:              data.as       || null,
      asnName:          data.asname   || null
    };

    setCached(cacheKey, result);
    return res.json(result);

  } catch (err) {
    console.error('Primary lookup failed:', err.message);
    
    // Try fallback
    try {
      const fallbackData = await lookupFallback(host);
      if (fallbackData.query && !isPrivateIP(fallbackData.query)) {
        return res.status(200).json({
          inputProvided:    raw,
          inputType:        getInputType(host),
          resolvedIP:       fallbackData.query,
          resolvedHostname: null,
          warning:          'Geolocation data unavailable; only IP resolution available.',
          isp:              null,
          organization:     null,
          country:          null,
          countryCode:      null,
          state:            null,
          stateCode:        null,
          city:             null,
          postalCode:       null,
          latitude:         null,
          longitude:        null,
          timezone:         null,
          asn:              null,
          asnName:          null
        });
      }
    } catch (fallbackErr) {
      console.error('Fallback lookup also failed:', fallbackErr.message);
    }

    return res.status(502).json({ 
      error: 'Failed to reach geolocation service. Please try again later.',
      inputType: getInputType(host)
    });
  }
});

app.listen(PORT, () => {
  console.log(`IPTraceback running at http://localhost:${PORT}`);
});
