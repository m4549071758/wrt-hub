import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { JWTVerifyGetKey } from "jose";
import { afterEach } from "vitest";
import type { FastifyInstance } from "fastify";
import { Admin } from "../src/admin.js";
import { buildApp } from "../src/app.js";
import { OidcVerifier } from "../src/auth.js";
import { CommandQueue } from "../src/commands.js";
import type { Config, InfluxConfig, OidcConfig } from "../src/config.js";
import { openDb, type Db } from "../src/db/index.js";
import { LiveState } from "../src/live.js";
import { Monitor } from "../src/monitor.js";
import type { Notification } from "../src/notify.js";
import { FakeAgent } from "./fake-agent.js";

export function fixture(path: string): Record<string, unknown> {
  return JSON.parse(readFileSync(fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url)), "utf8")) as Record<string, unknown>;
}

export interface HarnessOptions {
  oidc?: { config: OidcConfig; keys: JWTVerifyGetKey };
  influx?: InfluxConfig;
  fetchFn?: typeof fetch;
}

export interface Harness {
  app: FastifyInstance;
  db: Db;
  admin: Admin;
  /** Set [now] to move the server's clock. */
  clock: { now: number };
  site: { id: string };
  router: { id: string; token: string };
  agent: FakeAgent;
  /** Authorization header of the paired phone. */
  appAuth: { authorization: string };
  deviceId: string;
  notifications: Notification[];
  monitor: Monitor;
}

/** A server on an in-memory database with a settable clock, one site with a router node, and a paired phone. */
export async function harness(options: HarnessOptions = {}): Promise<Harness> {
  const clock = { now: 1_800_000_000_000 };
  const now = () => clock.now;
  const config: Config = {
    host: "127.0.0.1",
    port: 0,
    dbPath: ":memory:",
    staleMs: 30_000,
    offlineMs: 60_000,
    oidc: options.oidc?.config ?? null,
    influx: options.influx ?? null,
    fcm: null,
  };
  const db = openDb(":memory:");
  const notifications: Notification[] = [];
  const monitor = new Monitor(db, async (n) => void notifications.push(n), { warn: () => {} }, config.offlineMs, now);
  const queue = new CommandQueue(db, now, 120_000, 300_000);
  const live = new LiveState();
  const app = buildApp({
    config,
    db,
    live,
    queue,
    monitor,
    oidc: options.oidc ? new OidcVerifier(options.oidc.config, options.oidc.keys) : null,
    now,
    ...(options.fetchFn ? { fetchFn: options.fetchFn } : {}),
  });
  await app.ready();
  const admin = new Admin(db, now);
  const site = admin.addSite("Cabin");
  const router = admin.addNode(site.id, "Router");
  const { code } = admin.createPairingCode("phone", 600_000);
  const paired = (await app.inject({ method: "POST", url: "/app/v1/pair", payload: { code } })).json() as { token: string; deviceId: string };
  const appAuth = { authorization: `Bearer ${paired.token}` };
  return { app, db, admin, clock, site, router, agent: new FakeAgent(app, router.token), appAuth, deviceId: paired.deviceId, notifications, monitor };
}

/** Registers cleanup with vitest and returns a factory: each test gets its own server, closed after it. */
export function perTest(): (options?: HarnessOptions) => Promise<Harness> {
  let current: Harness | undefined;
  afterEach(async () => {
    await current?.app.close();
    current = undefined;
  });
  return async (options) => {
    current = await harness(options);
    return current;
  };
}
