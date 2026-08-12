import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { createRequestHandler } from "../server.mjs";

test("normalizes a timestamped Finnhub quote without returning the key", async () => {
  const providerFetch = async url => {
    assert.match(url, /token=server-secret/);
    return jsonResponse({ c: 421.25, t: 1786460340 });
  };
  const response = await invoke(createRequestHandler({ fetchImpl: providerFetch, env: { MARKET_DATA_PROVIDER: "finnhub", FINNHUB_API_KEY: "server-secret" } }), "/v1/quote/MSFT");
  assert.equal(response.status, 200);
  assert.equal(response.body.provider, "Finnhub");
  assert.equal(response.body.price, 421.25);
  assert.match(response.body.timestamp, /^\d{4}-\d{2}-\d{2}T/);
  assert.doesNotMatch(JSON.stringify(response.body), /server-secret/);
});

test("maps provider throttling without generating market values", async () => {
  const response = await invoke(createRequestHandler({
    fetchImpl: async () => jsonResponse({}, 429),
    env: { MARKET_DATA_PROVIDER: "finnhub", FINNHUB_API_KEY: "server-secret" },
  }), "/v1/quote/MSFT");
  assert.equal(response.status, 429);
  assert.deepEqual(response.body, { error: "Provider rate limit exceeded" });
});

test("rejects unsupported symbols before calling a provider", async () => {
  let called = false;
  const response = await invoke(createRequestHandler({ fetchImpl: async () => { called = true; } }), "/v1/quote/not%20valid");
  assert.equal(response.status, 404);
  assert.equal(called, false);
});

async function invoke(handler, path) {
  const server = createServer(handler).listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const address = server.address();
    const response = await fetch(`http://127.0.0.1:${address.port}${path}`);
    return { status: response.status, body: await response.json() };
  } finally {
    server.close();
    await once(server, "close");
  }
}

function jsonResponse(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}