'use strict';

const express = require('express');
const fetch   = require('node-fetch');
const path    = require('path');
const { URL } = require('url');

const app  = express();
const PORT = process.env.PORT || 3000;

app.use(express.static(path.join(__dirname, 'public')));

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
    const apiRes  = await fetch(apiUrl, { timeout: 8000 });
    const data    = await apiRes.json();

    if (data.status === 'fail') {
      return res.status(200).json({
        error: 'Location information is unavailable for this IP address or website.',
        raw: data.message || null
      });
    }

    // Determine input type
    let inputType = 'Unknown';
    if (/^(\d{1,3}\.){3}\d{1,3}$/.test(host)) {
      inputType = 'IPv4 Address';
    } else if (host.includes(':')) {
      inputType = 'IPv6 Address';
    } else {
      inputType = 'Domain / URL';
    }

    return res.json({
      inputProvided:    raw,
      inputType,
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
    });
  } catch (err) {
    console.error('Lookup error:', err.message);
    return res.status(502).json({ error: 'Failed to reach geolocation service. Please try again.' });
  }
});

app.listen(PORT, () => {
  console.log(`IPTraceback running at http://localhost:${PORT}`);
});
