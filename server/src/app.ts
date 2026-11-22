import { createGunzip } from "node:zlib";
import { and, desc, eq, isNull } from "drizzle-orm";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { z } from "zod";
import { authenticateDevice, authenticateNode, bearer, pair, type AgentPrincipal, type AppPrincipal, type OidcVerifier } from "./auth.js";
import { CommandRequest, CommandResult, parseCommand, type CommandQueue, type CommandView } from "./commands.js";
import type { Config } from "./config.js";
import type { Db } from "./db/index.js";
import { events, nodes, pushTargets, sites } from "./db/schema.js";
import type { LiveState, NodeSnapshot } from "./live.js";
import type { Monitor } from "./monitor.js";
import { readStarlink, STARLINK_RANGES, type StarlinkRange } from "./starlink.js";

export interface Deps {
  config: Config;
  db: Db;
  live: LiveState;
  queue: CommandQueue;
  monitor: Monitor;
  oidc: OidcVerifier | null;
  now: () => number;
  fetchFn?: typeof fetch;
  logger?: boolean;
}

declare module "fastify" {
  interface FastifyRequest {
    agent?: AgentPrincipal;
    principal?: AppPrincipal;
  }
}

/** Decompressed snapshot size limit: a mesh AP with many clients is well under 1 MB. */
const BODY_LIMIT = 4 * 1024 * 1024;
const MAX_WAIT_S = 25;
const SSE_HEARTBEAT_MS = 15_000;

const WaitQuery = z.object({ wait: z.coerce.number().int().min(0).max(MAX_WAIT_S).default(0) });
const IdParams = z.object({ id: z.string().min(1).max(64) });
const PairBody = z.strictObject({ code: z.string().min(1).max(64) });
const PushBody = z.strictObject({ token: z.string().min(1).max(4096) });
const EventsQuery = z.object({ limit: z.coerce.number().int().min(1).max(200).default(50) });
const StarlinkQuery = z.object({ range: z.enum(Object.keys(STARLINK_RANGES) as [StarlinkRange, ...StarlinkRange[]]).default("1h") });
const SnapshotBody = z.looseObject({ schemaVersion: z.number().int() });

type NodeState = "fresh" | "stale" | "offline" | "never";

export function buildApp(deps: Deps): FastifyInstance {
  const { config, db, live, queue, monitor, oidc, now } = deps;
  const app = Fastify({ logger: deps.logger ?? false, bodyLimit: BODY_LIMIT, trustProxy: true });

  // Agents may gzip snapshots; the body limit then applies to the decompressed size. Fastify checks Content-Length
  // against the replacement stream's receivedEncodedLength, so that counts the compressed bytes read.
  app.addHook("preParsing", async (request, _reply, payload) => {
    if (request.headers["content-encoding"] !== "gzip") return payload;
    const inflated = Object.assign(createGunzip(), { receivedEncodedLength: 0 });
    payload.on("data", (chunk: Buffer) => {
      inflated.receivedEncodedLength += chunk.length;
    });
    return payload.pipe(inflated);
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof z.ZodError) return reply.code(400).send({ error: z.prettifyError(error) });
    const status = error instanceof Error && "statusCode" in error && typeof error.statusCode === "number" ? error.statusCode : 500;
    if (status >= 500) request.log.error({ err: error }, "request failed");
    return reply.code(status).send({ error: status >= 500 || !(error instanceof Error) ? "internal error" : error.message });
  });

  function stateOf(snapshot: NodeSnapshot | undefined): NodeState {
    if (!snapshot) return "never";
    const age = now() - snapshot.receivedAt;
    if (age > config.offlineMs) return "offline";
    return age > config.staleMs ? "stale" : "fresh";
  }

  function nodeView(node: { id: string; label: string }) {
    const snapshot = live.get(node.id);
    return { id: node.id, label: node.label, state: stateOf(snapshot), receivedAt: snapshot?.receivedAt ?? null };
  }

  function activeNodes(siteId?: string) {
    const where = siteId ? and(eq(nodes.siteId, siteId), isNull(nodes.revokedAt)) : isNull(nodes.revokedAt);
    return db.select({ id: nodes.id, label: nodes.label, siteId: nodes.siteId }).from(nodes).where(where).all();
  }

  function siteOr404(id: string, reply: FastifyReply) {
    const site = db.select().from(sites).where(eq(sites.id, id)).get();
    if (!site) void reply.code(404).send({ error: "no such site" });
    return site;
  }

  function commandView(command: CommandView) {
    const { id, nodeId, name, args, status, result, createdAt, sentAt, finishedAt } = command;
    return { id, nodeId, name, args, status, result, createdAt, sentAt, finishedAt };
  }

  function abortOnClose(request: FastifyRequest): AbortSignal {
    const controller = new AbortController();
    request.raw.on("close", () => controller.abort());
    return controller.signal;
  }

  // Agents ---------------------------------------------------------------------------------------------------------
  app.register(
    async (agent) => {
      agent.addHook("onRequest", async (request, reply) => {
        const token = bearer(request.headers.authorization);
        const principal = token ? authenticateNode(db, token) : undefined;
        if (!principal) return reply.code(401).send({ error: "unauthorized" });
        request.agent = principal;
      });

      agent.post("/snapshot", async (request, reply) => {
        const snapshot = SnapshotBody.parse(request.body);
        const { nodeId, siteId } = request.agent!;
        live.put({ nodeId, siteId, receivedAt: now(), snapshot });
        monitor.onSnapshot(nodeId, snapshot);
        return reply.code(204).send();
      });

      agent.get("/commands", async (request) => {
        const { wait } = WaitQuery.parse(request.query);
        const taken = await queue.take(request.agent!.nodeId, wait * 1000, abortOnClose(request));
        return { commands: taken.map(({ id, name, args }) => ({ id, name, args })) };
      });

      agent.post("/commands/:id/result", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        const { status, result } = CommandResult.parse(request.body);
        if (!queue.finish(request.agent!.nodeId, id, status, result)) {
          return reply.code(409).send({ error: "command not awaiting a result from this node" });
        }
        return reply.code(204).send();
      });
    },
    { prefix: "/agent/v1" },
  );

  // App, without authentication ----------------------------------------------------------------------------------
  app.get("/app/v1/config", async () => ({
    auth: {
      pairing: true,
      oidc: config.oidc ? { issuer: config.oidc.issuer, clientId: config.oidc.clientId, audience: config.oidc.audience } : null,
    },
    features: { starlink: config.influx !== null },
  }));

  app.post("/app/v1/pair", async (request, reply) => {
    const { code } = PairBody.parse(request.body);
    const paired = pair(db, code, now());
    if (!paired) return reply.code(403).send({ error: "invalid or expired code" });
    return paired;
  });

  app.get("/healthz", async () => ({ ok: true }));

  // App, authenticated -------------------------------------------------------------------------------------------
  app.register(
    async (api) => {
      api.addHook("onRequest", async (request, reply) => {
        const token = bearer(request.headers.authorization);
        let principal: AppPrincipal | undefined;
        if (token) {
          // A JWT has three dot-separated parts; device tokens are plain base64url.
          principal = token.split(".").length === 3 && oidc ? await oidc.verify(token) : authenticateDevice(db, token, now());
        }
        if (!principal) return reply.code(401).send({ error: "unauthorized" });
        request.principal = principal;
      });

      api.get("/sites", async () => {
        const all = activeNodes();
        return {
          serverTime: now(),
          sites: db
            .select()
            .from(sites)
            .all()
            .map((site) => ({ id: site.id, name: site.name, nodes: all.filter((n) => n.siteId === site.id).map(nodeView) })),
        };
      });

      api.get("/sites/:id/snapshots", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        if (!siteOr404(id, reply)) return reply;
        return {
          serverTime: now(),
          nodes: activeNodes(id).map((node) => ({ ...nodeView(node), snapshot: live.get(node.id)?.snapshot ?? null })),
        };
      });

      api.get("/sites/:id/stream", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        if (!siteOr404(id, reply)) return reply;
        reply.hijack();
        const raw = reply.raw;
        raw.writeHead(200, {
          "content-type": "text/event-stream",
          "cache-control": "no-cache, no-transform",
          connection: "keep-alive",
          // nginx and similar proxies buffer responses unless told not to.
          "x-accel-buffering": "no",
        });
        const send = (update: NodeSnapshot) =>
          raw.write(`event: snapshot\ndata: ${JSON.stringify({ nodeId: update.nodeId, receivedAt: update.receivedAt, serverTime: now(), snapshot: update.snapshot })}\n\n`);
        for (const node of activeNodes(id)) {
          const current = live.get(node.id);
          if (current) send(current);
        }
        const unsubscribe = live.subscribe(id, send);
        const heartbeat = setInterval(() => raw.write(`: ${now()}\n\n`), SSE_HEARTBEAT_MS);
        request.raw.on("close", () => {
          clearInterval(heartbeat);
          unsubscribe();
        });
      });

      api.get("/sites/:id/events", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        const { limit } = EventsQuery.parse(request.query);
        if (!siteOr404(id, reply)) return reply;
        return {
          events: db.select().from(events).where(eq(events.siteId, id)).orderBy(desc(events.createdAt), desc(events.id)).limit(limit).all(),
        };
      });

      api.get("/nodes/:id/counters", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        if (!activeNodes().some((n) => n.id === id)) return reply.code(404).send({ error: "no such node" });
        return { samples: live.counterHistory(id) };
      });

      api.post("/nodes/:id/commands", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        if (!activeNodes().some((n) => n.id === id)) return reply.code(404).send({ error: "no such node" });
        const body = CommandRequest.parse(request.body);
        const parsed = parseCommand(body.name, body.args);
        if ("error" in parsed) return reply.code(400).send({ error: parsed.error });
        const command = queue.enqueue(id, request.principal!.issuer, parsed.name, parsed.args);
        request.log.info({ issuer: request.principal!.issuer, nodeId: id, command: parsed.name, commandId: command.id }, "command issued");
        return reply.code(202).send(commandView(command));
      });

      api.get("/commands/:id", async (request, reply) => {
        const { id } = IdParams.parse(request.params);
        const { wait } = WaitQuery.parse(request.query);
        const command = await queue.awaitResult(id, wait * 1000, abortOnClose(request));
        if (!command) return reply.code(404).send({ error: "no such command" });
        return commandView(command);
      });

      api.post("/push", async (request, reply) => {
        const { token } = PushBody.parse(request.body);
        db.insert(pushTargets)
          .values({ token, owner: request.principal!.issuer, createdAt: now() })
          .onConflictDoUpdate({ target: pushTargets.token, set: { owner: request.principal!.issuer } })
          .run();
        return reply.code(204).send();
      });

      api.delete("/push", async (request, reply) => {
        const { token } = PushBody.parse(request.body);
        db.delete(pushTargets)
          .where(and(eq(pushTargets.token, token), eq(pushTargets.owner, request.principal!.issuer)))
          .run();
        return reply.code(204).send();
      });

      api.get("/starlink", async (request, reply) => {
        if (!config.influx) return reply.code(404).send({ error: "starlink is not configured" });
        const { range } = StarlinkQuery.parse(request.query);
        try {
          return { range, series: await readStarlink(config.influx, range, deps.fetchFn) };
        } catch (error) {
          request.log.warn({ err: error }, "starlink query failed");
          return reply.code(502).send({ error: "starlink data unavailable" });
        }
      });
    },
    { prefix: "/app/v1" },
  );

  return app;
}
