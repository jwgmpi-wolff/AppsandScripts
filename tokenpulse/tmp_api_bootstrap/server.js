const http = require('node:http');

function sendJson(res, status, payload) {
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
  });
  res.end(JSON.stringify(payload));
}

const server = http.createServer((req, res) => {
  const url = req.url || '/';

  if (req.method === 'GET' && url === '/healthz') {
    return sendJson(res, 200, { ok: true, service: 'tokenpulse-azure-api', live: true });
  }

  if (req.method === 'GET' && url === '/api/tokenpulse') {
    return sendJson(res, 200, {
      providers: [],
      models: [],
      projects: [],
      usageEvents: [],
      alerts: [],
    });
  }

  if (req.method === 'POST' && url === '/api/tokenpulse/alerts') {
    return sendJson(res, 201, { id: `alert-${Date.now()}` });
  }

  const putMatch = req.method === 'PUT' && /^\/api\/tokenpulse\/alerts\/[^/]+$/.test(url);
  if (putMatch) {
    const id = url.split('/').pop();
    return sendJson(res, 200, { id });
  }

  const delMatch = req.method === 'DELETE' && /^\/api\/tokenpulse\/alerts\/[^/]+$/.test(url);
  if (delMatch) {
    const id = url.split('/').pop();
    return sendJson(res, 200, { ok: true, id });
  }

  return sendJson(res, 404, { error: 'Not found' });
});

const port = Number.parseInt(process.env.PORT || '8080', 10);
server.listen(port, () => {
  console.log(`tokenpulse-azure-api listening on ${port}`);
});
