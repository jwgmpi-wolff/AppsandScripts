'use strict';

const express = require('express');
const path    = require('path');

const IPTracebackExtended = require('./index');

const app  = express();
const PORT = process.env.PORT || 3001;

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

/* ── Cache (1 hour TTL, max 500 entries) ──────────────────────────── */
const CACHE_TTL      = 3600 * 1000;
const MAX_CACHE_SIZE = 500;
const cache          = new Map();

function getCached(key) {
  const entry = cache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.ts > CACHE_TTL) { cache.delete(key); return null; }
  return entry.data;
}
function setCached(key, data) {
  if (cache.size >= MAX_CACHE_SIZE) cache.delete(cache.keys().next().value);
  cache.set(key, { data, ts: Date.now() });
}

/* ── Shared IPTracebackExtended instance ──────────────────────────── */
const ipt = new IPTracebackExtended({ geolocate: false, skipPrivate: true });

/* ────────────────────────────────────────────────────────────────────
   POST /api/decode
   Body: { input: string, method: string }
   ──────────────────────────────────────────────────────────────────── */
app.post('/api/decode', (req, res) => {
  const { input, method = 'auto' } = req.body || {};
  if (!input || typeof input !== 'string' || input.trim().length === 0) {
    return res.status(400).json({ error: 'No input provided.' });
  }
  if (input.trim().length > 8192) {
    return res.status(400).json({ error: 'Input too long (max 8192 chars).' });
  }

  try {
    const safeMethod = String(method).replace(/[^a-z0-9\-]/gi, '');
    const result = ipt.decode(input.trim(), safeMethod);
    return res.json(result);
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/* ────────────────────────────────────────────────────────────────────
   POST /api/traceback
   Body: {
     encodedInputs?: string[],
     scrapeURLs?: string[],
     hostnames?: string[],
     discoverPublic?: boolean,
     geolocate?: boolean
   }
   ──────────────────────────────────────────────────────────────────── */
app.post('/api/traceback', async (req, res) => {
  const {
    encodedInputs  = [],
    scrapeURLs     = [],
    hostnames      = [],
    discoverPublic = true,
    geolocate      = false
  } = req.body || {};

  // Validate array inputs
  if (!Array.isArray(encodedInputs) || !Array.isArray(scrapeURLs) || !Array.isArray(hostnames)) {
    return res.status(400).json({ error: 'encodedInputs, scrapeURLs, and hostnames must be arrays.' });
  }

  // Cap lengths to prevent abuse
  const safeInputs    = encodedInputs.slice(0, 20).map(s => String(s).slice(0, 4096));
  const safeURLs      = scrapeURLs.slice(0, 5).map(s => String(s).slice(0, 512));
  const safeHostnames = hostnames.slice(0, 10).map(s => String(s).slice(0, 253));

  // Basic URL validation
  for (const url of safeURLs) {
    try {
      const parsed = new URL(url);
      if (!['http:', 'https:'].includes(parsed.protocol)) {
        return res.status(400).json({ error: `Invalid URL protocol: ${url}` });
      }
    } catch {
      return res.status(400).json({ error: `Invalid URL: ${url}` });
    }
  }

  try {
    const result = await ipt.traceback({
      encodedInputs:  safeInputs,
      scrapeURLs:     safeURLs,
      hostnames:      safeHostnames,
      discoverPublic: Boolean(discoverPublic),
      geolocate:      Boolean(geolocate)
    });
    return res.json(result);
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/* ────────────────────────────────────────────────────────────────────
   GET /api/dns/:hostname
   ──────────────────────────────────────────────────────────────────── */
app.get('/api/dns/:hostname', async (req, res) => {
  const hostname = req.params.hostname.trim().toLowerCase();
  if (!hostname || hostname.length > 253) {
    return res.status(400).json({ error: 'Invalid hostname.' });
  }
  // Only allow valid hostname chars
  if (!/^[a-z0-9.\-]+$/.test(hostname)) {
    return res.status(400).json({ error: 'Hostname contains invalid characters.' });
  }

  const cacheKey = `dns:${hostname}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json({ ...cached, fromCache: true });

  try {
    const result = await ipt.dnsLookupAll(hostname);
    setCached(cacheKey, result);
    return res.json(result);
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/* ────────────────────────────────────────────────────────────────────
   GET /api/classify/:ip
   ──────────────────────────────────────────────────────────────────── */
app.get('/api/classify/:ip', (req, res) => {
  const ip = req.params.ip.trim();
  if (!ip) return res.status(400).json({ error: 'No IP provided.' });

  try {
    const result = ipt.classify(ip);
    return res.json(result);
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/* ────────────────────────────────────────────────────────────────────
   GET /api/geolocate/:ip
   ──────────────────────────────────────────────────────────────────── */
app.get('/api/geolocate/:ip', async (req, res) => {
  const ip = req.params.ip.trim();
  if (!ip) return res.status(400).json({ error: 'No IP provided.' });

  const cacheKey = `geo:${ip}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json({ ...cached, fromCache: true });

  try {
    const result = await ipt.geolocate(ip);
    if (result.success) setCached(cacheKey, result);
    return res.json(result);
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/* ────────────────────────────────────────────────────────────────────
   GET /api/public-ip
   ──────────────────────────────────────────────────────────────────── */
app.get('/api/public-ip', async (req, res) => {
  try {
    const results = await ipt.discoverPublicIPs();
    const successful = results.filter(r => r.success);
    const ip = successful.length > 0 ? successful[0].ip : null;
    return res.json({ ip, sources: results });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});

/* ────────────────────────────────────────────────────────────────────
   POST /api/trace
   Body: { ips: string[] }
   Batch classify + geolocate a list of destination IPs.
   Returns per-IP: classification, geolocation, reverse DNS.
   ──────────────────────────────────────────────────────────────────── */
app.post('/api/trace', async (req, res) => {
  const { ips } = req.body || {};
  if (!Array.isArray(ips) || ips.length === 0) {
    return res.status(400).json({ error: 'ips must be a non-empty array.' });
  }

  const safeIPs = [...new Set(
    ips.slice(0, 20).map(ip => String(ip).trim()).filter(ip => ip.length > 0 && ip.length <= 45)
  )];

  const { isValidIP } = require('./utils');
  const validIPs = safeIPs.filter(ip => isValidIP(ip));
  if (validIPs.length === 0) {
    return res.status(400).json({ error: 'No valid IP addresses provided.' });
  }

  // Run classify, geolocate, and reverse DNS in parallel per IP
  const results = await Promise.all(validIPs.map(async ip => {
    const cacheKey = `trace:${ip}`;
    const cached = getCached(cacheKey);
    if (cached) return { ...cached, fromCache: true };

    const [classification, geo, reverseDNS] = await Promise.allSettled([
      Promise.resolve(ipt.classify(ip)),
      ipt.geolocate(ip),
      ipt.reverseLookup(ip)
    ]);

    const entry = {
      ip,
      classification: classification.status === 'fulfilled' ? classification.value : null,
      geo:            geo.status            === 'fulfilled' ? geo.value            : { success: false, ip, error: geo.reason?.message },
      reverseDNS:     reverseDNS.status     === 'fulfilled' ? reverseDNS.value     : { success: false, ip }
    };

    if (entry.geo?.success) setCached(cacheKey, entry);
    return entry;
  }));

  return res.json({ results });
});

/* ── Clear cache endpoint ─────────────────────────────────────────── */
app.get('/api/cache-clear', (req, res) => {
  cache.clear();
  res.json({ success: true, message: 'Cache cleared' });
});

/* ── 404 fallback ─────────────────────────────────────────────────── */
app.use((req, res) => res.status(404).json({ error: 'Not found' }));

app.listen(PORT, () => {
  console.log(`IPTraceback Extended running at http://localhost:${PORT}`);
});
