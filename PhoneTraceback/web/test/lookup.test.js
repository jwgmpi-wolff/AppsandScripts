import assert from "node:assert/strict";
import test from "node:test";
import request from "supertest";
import { createApp } from "../src/app.js";
import { createLookupService } from "../src/lookup.js";

const fixedNow = () => new Date("2026-07-28T12:00:00.000Z");

test("returns sourced numbering assignment without inferred coordinates", async () => {
  const app = createApp({ lookup: createLookupService({ now: fixedNow }) });

  const response = await request(app).get("/api/lookup").query({ number: "+1 202 555 0123" });

  assert.equal(response.status, 200);
  assert.equal(response.body.countryCode, "US");
  assert.equal(response.body.region, "United States");
  assert.equal(response.body.numberingAssignment.area, "United States");
  assert.equal(response.body.numberingAssignment.areaCode, "202");
  assert.equal(response.body.numberingAssignment.tracksCurrentLocation, false);
  assert.equal("approximateCoordinates" in response.body, false);
  assert.doesNotMatch(JSON.stringify(response.body), /latitude|longitude|approximateCoordinates/);
  assert.match(response.body.disclaimer, /Not a live caller, device, or network location/);
  assert.equal(response.body.investigation.authenticity.status, "unverified");
  assert.match(response.body.investigation.authenticity.summary, /do not verify/);
  assert.equal(response.body.investigation.publicSearches.length, 5);
  assert.match(response.body.investigation.publicSearches[0].url, /%22%2B12025550123%22%20spam%20scam/);
  assert.match(response.body.investigation.publicSearches[2].url, /^https:\/\/www\.nanpa\.com\//);
  assert.equal(response.body.sources.length, 2);
  assert.equal(response.body.sources[0].retrievedAtUtc, "2026-07-28T12:00:00.000Z");
});

test("rejects invalid numbers", async () => {
  const lookup = createLookupService();

  const response = await request(createApp({ lookup })).get("/api/lookup").query({ number: "123" });

  assert.equal(response.status, 400);
  assert.match(response.body.error, /not valid/);
});

test("supports non-geographic numbering plans without inferred coordinates", async () => {
  const lookup = createLookupService({ now: fixedNow });

  const response = await request(createApp({ lookup }))
    .get("/api/lookup")
    .query({ number: "+800 1234 5678" });

  assert.equal(response.status, 200);
  assert.equal(response.body.countryCode, null);
  assert.equal(response.body.numberingAssignment.area, "Non-geographic numbering plan");
  assert.equal(response.body.numberingAssignment.tracksCurrentLocation, false);
  assert.equal("approximateCoordinates" in response.body, false);
  assert.equal(response.body.sources.length, 1);
});