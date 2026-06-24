import express from 'express';
import cors from 'cors';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const app = express();
const port = Number.parseInt(process.env.PORT ?? '8787', 10);
const upstreamBaseUrl = process.env.TOKENPULSE_UPSTREAM_BASE_URL;
const upstreamBearerToken = process.env.TOKENPULSE_UPSTREAM_BEARER_TOKEN;
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const distDir = path.resolve(__dirname, '..', 'dist');
const indexHtmlPath = path.join(distDir, 'index.html');

app.use(cors());
app.use(express.json({ limit: '1mb' }));
app.use(express.static(distDir, { maxAge: '1h' }));

function noStore(res) {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
}

function buildHeaders(incomingHeaders = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...incomingHeaders,
  };

  if (upstreamBearerToken) {
    headers.Authorization = `Bearer ${upstreamBearerToken}`;
  }

  return headers;
}

function requireUpstream(res) {
  if (!upstreamBaseUrl) {
    noStore(res);
    res.status(500).json({
      error: 'TOKENPULSE_UPSTREAM_BASE_URL is not configured. Live mode requires an upstream API.',
    });
    return false;
  }
  return true;
}

async function forwardJson(res, path, init = {}) {
  const response = await fetch(`${upstreamBaseUrl}${path}`, {
    ...init,
    headers: buildHeaders(init.headers ?? {}),
    cache: 'no-store',
  });

  const contentType = response.headers.get('content-type') ?? 'application/json';
  noStore(res);
  res.status(response.status);
  res.setHeader('Content-Type', contentType);

  const body = await response.text();
  res.send(body);
}

app.get('/healthz', (_req, res) => {
  noStore(res);
  res.json({
    ok: true,
    liveMode: true,
    upstreamConfigured: Boolean(upstreamBaseUrl),
  });
});

app.get('/api/tokenpulse', async (_req, res) => {
  if (!requireUpstream(res)) return;

  try {
    await forwardJson(res, '/tokenpulse', { method: 'GET' });
  } catch (error) {
    noStore(res);
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.post('/api/tokenpulse/alerts', async (req, res) => {
  if (!requireUpstream(res)) return;

  try {
    await forwardJson(res, '/tokenpulse/alerts', {
      method: 'POST',
      body: JSON.stringify(req.body ?? {}),
    });
  } catch (error) {
    noStore(res);
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.put('/api/tokenpulse/alerts/:id', async (req, res) => {
  if (!requireUpstream(res)) return;

  try {
    await forwardJson(res, `/tokenpulse/alerts/${encodeURIComponent(req.params.id)}`, {
      method: 'PUT',
      body: JSON.stringify(req.body ?? {}),
    });
  } catch (error) {
    noStore(res);
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.delete('/api/tokenpulse/alerts/:id', async (req, res) => {
  if (!requireUpstream(res)) return;

  try {
    await forwardJson(res, `/tokenpulse/alerts/${encodeURIComponent(req.params.id)}`, {
      method: 'DELETE',
    });
  } catch (error) {
    noStore(res);
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.use((req, res, next) => {
  if (req.path.startsWith('/api/') || req.path === '/healthz') {
    next();
    return;
  }

  res.sendFile(indexHtmlPath, err => {
    if (err) {
      res.status(404).json({ error: 'Frontend assets not found. Ensure dist/ is deployed.' });
    }
  });
});

app.listen(port, () => {
  console.log(`TokenPulse API proxy listening on http://127.0.0.1:${port}`);
});
