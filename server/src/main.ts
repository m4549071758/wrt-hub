import type { FastifyInstance } from "fastify";
import { buildApp } from "./app.js";
import { OidcVerifier } from "./auth.js";
import { CommandQueue } from "./commands.js";
import { loadConfig } from "./config.js";
import { openDb } from "./db/index.js";
import { LiveState } from "./live.js";
import { Monitor } from "./monitor.js";
import { fcmNotifier, type Logger, type Notifier } from "./notify.js";

const MONITOR_INTERVAL_MS = 10_000;

const config = loadConfig(process.env);
const db = openDb(config.dbPath);
let app: FastifyInstance | undefined;
// The monitor logs only when a notification fails, which is after the app (and its logger) exists.
const log: Logger = { warn: (obj, msg) => app?.log.warn(obj, msg) };
// Without FCM credentials events are still recorded and shown in the app; only the push is skipped.
const notify: Notifier = config.fcm ? fcmNotifier(db, log, config.fcm) : async () => {};
const monitor = new Monitor(db, notify, log, config.offlineMs);
app = buildApp({
  config,
  db,
  live: new LiveState(),
  queue: new CommandQueue(db),
  monitor,
  oidc: config.oidc ? new OidcVerifier(config.oidc) : null,
  now: Date.now,
  logger: true,
});

const timer = setInterval(() => monitor.tick(), MONITOR_INTERVAL_MS);
const server = app;
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    clearInterval(timer);
    void server.close().then(() => process.exit(0));
  });
}
await server.listen({ host: config.host, port: config.port });
