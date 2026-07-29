import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import rateLimit from "express-rate-limit";
import helmet from "helmet";
import { createLookupService, LookupError } from "./lookup.js";

const publicDirectory = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "public");

export function createApp({ lookup = createLookupService() } = {}) {
  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", 1);
  app.use(helmet({ contentSecurityPolicy: false }));
  app.use(express.static(publicDirectory));

  app.get("/health", (_request, response) => response.json({ status: "ok" }));
  app.get(
    "/api/lookup",
    rateLimit({ windowMs: 60_000, limit: 30, standardHeaders: "draft-8", legacyHeaders: false }),
    async (request, response, next) => {
      try {
        response.json(await lookup(request.query.number));
      } catch (error) {
        next(error);
      }
    },
  );

  app.use((error, _request, response, _next) => {
    if (error instanceof LookupError) {
      return response.status(error.status).json({ error: error.message });
    }
    console.error("Lookup failed", error);
    return response.status(500).json({ error: "The lookup could not be completed." });
  });

  return app;
}