import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from "jose";
import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";
import { commands } from "../src/db/schema.js";
import { parseFluxCsv } from "../src/starlink.js";
import { fixture, perTest } from "./harness.js";

const setup = perTest();

const oidcConfig = { issuer: "https://id.example", audience: "wrthub", clientId: "wrthub-app" };

async function oidcSetup() {
  const { publicKey, privateKey } = await generateKeyPair("ES256");
  const jwk = { ...(await exportJWK(publicKey)), kid: "k1", alg: "ES256" };
  const keys = createLocalJWKSet({ keys: [jwk] });
  const sign = (claims: { aud?: string; iss?: string; exp?: string }) =>
    new SignJWT({})
      .setProtectedHeader({ alg: "ES256", kid: "k1" })
      .setSubject("user-1")
      .setIssuer(claims.iss ?? oidcConfig.issuer)
      .setAudience(claims.aud ?? oidcConfig.audience)
      .setIssuedAt()
      .setExpirationTime(claims.exp ?? "5m")
      .sign(privateKey);
  return { keys, sign };
}

describe("OIDC", () => {
  it("accepts a token for this audience and records the subject as issuer", async () => {
    const { keys, sign } = await oidcSetup();
    const h = await setup({ oidc: { config: oidcConfig, keys } });
    const auth = { authorization: `Bearer ${await sign({})}` };
    expect((await h.app.inject({ url: "/app/v1/sites", headers: auth })).statusCode).toBe(200);
    await h.app.inject({ method: "POST", url: `/app/v1/nodes/${h.router.id}/commands`, headers: auth, payload: { name: "reboot" } });
    expect(h.db.select().from(commands).get()?.issuer).toBe("oidc:user-1");
    const config = (await h.app.inject({ url: "/app/v1/config" })).json() as { auth: { oidc: unknown } };
    expect(config.auth.oidc).toEqual({ issuer: oidcConfig.issuer, clientId: oidcConfig.clientId, audience: oidcConfig.audience });
  });

  it("refuses another audience, another issuer and expired tokens; device tokens keep working", async () => {
    const { keys, sign } = await oidcSetup();
    const h = await setup({ oidc: { config: oidcConfig, keys } });
    for (const token of [await sign({ aud: "other" }), await sign({ iss: "https://evil.example" }), await sign({ exp: "-1m" })]) {
      expect((await h.app.inject({ url: "/app/v1/sites", headers: { authorization: `Bearer ${token}` } })).statusCode).toBe(401);
    }
    expect((await h.app.inject({ url: "/app/v1/sites", headers: h.appAuth })).statusCode).toBe(200);
  });
});

describe("SSE", () => {
  it("sends the current snapshots on connect, then each new one", async () => {
    const h = await setup();
    await h.agent.sendSnapshot({ schemaVersion: 1, n: 1 });
    const address = await h.app.listen({ host: "127.0.0.1", port: 0 });
    const controller = new AbortController();
    const response = await fetch(`${address}/app/v1/sites/${h.site.id}/stream`, { headers: h.appAuth, signal: controller.signal });
    expect(response.headers.get("content-type")).toBe("text/event-stream");
    const reader = response.body!.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = "";
    const nextEvent = async () => {
      while (!buffer.includes("\n\n")) {
        const { value, done } = await reader.read();
        if (done) throw new Error("stream ended");
        buffer += value;
      }
      const end = buffer.indexOf("\n\n");
      const event = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      return JSON.parse(event.split("\n").find((l) => l.startsWith("data: "))!.slice(6)) as { nodeId: string; snapshot: { n: number } };
    };
    expect((await nextEvent()).snapshot.n).toBe(1);
    await h.agent.sendSnapshot({ schemaVersion: 1, n: 2 });
    const second = await nextEvent();
    expect(second.snapshot.n).toBe(2);
    expect(second.nodeId).toBe(h.router.id);
    controller.abort();
  });
});

describe("Starlink", () => {
  const csv = [
    ",result,table,_field,_time,_value",
    ",_result,0,starlink_dish_pop_ping_latency_seconds,2026-11-22T10:01:00Z,0.031",
    ",_result,0,starlink_dish_pop_ping_latency_seconds,2026-11-22T10:00:00Z,0.029",
    "",
    ",result,table,_field,_time,_value",
    ",_result,1,starlink_dish_up,2026-11-22T10:00:00Z,1",
    "",
  ].join("\r\n");

  it("parses Flux CSV into time-ordered series per field", () => {
    expect(parseFluxCsv(csv)).toEqual([
      {
        field: "starlink_dish_pop_ping_latency_seconds",
        points: [
          [Date.parse("2026-11-22T10:00:00Z"), 0.029],
          [Date.parse("2026-11-22T10:01:00Z"), 0.031],
        ],
      },
      { field: "starlink_dish_up", points: [[Date.parse("2026-11-22T10:00:00Z"), 1]] },
    ]);
  });

  it("queries the configured bucket with the read token and is off when not configured", async () => {
    const influx = { url: "https://influx.example", token: "read-token", org: "home", bucket: "starlink" };
    let request: { url: string; auth: string | null; body: string } | undefined;
    const fetchFn: typeof fetch = async (input, init) => {
      request = { url: String(input), auth: new Headers(init?.headers).get("authorization"), body: String(init?.body) };
      return new Response(csv, { status: 200 });
    };
    const h = await setup({ influx, fetchFn });
    const response = await h.app.inject({ url: "/app/v1/starlink?range=15m", headers: h.appAuth });
    expect(response.statusCode).toBe(200);
    expect((response.json() as { series: unknown[] }).series).toHaveLength(2);
    expect(request?.url).toBe("https://influx.example/api/v2/query?org=home");
    expect(request?.auth).toBe("Token read-token");
    expect(request?.body).toContain('from(bucket: \\"starlink\\")');
    expect(request?.body).toContain("range(start: -15m)");
    expect((await h.app.inject({ url: "/app/v1/starlink?range=1y", headers: h.appAuth })).statusCode).toBe(400);
    const config = (await h.app.inject({ url: "/app/v1/config" })).json() as { features: { starlink: boolean } };
    expect(config.features.starlink).toBe(true);
  });
});

describe("configuration", () => {
  it("defaults, and refuses half-configured OIDC or Starlink", () => {
    const config = loadConfig({});
    expect(config).toMatchObject({ port: 8080, dbPath: "/data/wrthub.db", staleMs: 30_000, offlineMs: 60_000, oidc: null, influx: null });
    expect(() => loadConfig({ WRTHUB_OIDC_ISSUER: "https://id.example" })).toThrow(/OIDC/);
    expect(() => loadConfig({ WRTHUB_INFLUX_URL: "https://influx.example" })).toThrow(/Starlink/);
    expect(() => loadConfig({ WRTHUB_STALE_SECONDS: "90" })).toThrow(/OFFLINE/);
  });

  it("the fixture snapshots are accepted as they are", async () => {
    const h = await setup();
    for (const path of ["mesh-site/router.json", "mesh-site/ap.json", "wired-site/router.json"]) {
      expect(await h.agent.sendSnapshot(fixture(path))).toBe(204);
    }
  });
});
