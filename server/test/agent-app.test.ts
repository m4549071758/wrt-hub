import { describe, expect, it } from "vitest";
import { fixture, perTest } from "./harness.js";

const setup = perTest();

describe("snapshots", () => {
  it("are stored as sent, gzip or not, and age from fresh to stale to offline", async () => {
    const h = await setup();
    const snapshot = fixture("mesh-site/router.json");
    expect(await h.agent.sendSnapshot(snapshot)).toBe(204);

    const read = async () =>
      (await h.app.inject({ url: `/app/v1/sites/${h.site.id}/snapshots`, headers: h.appAuth })).json() as {
        nodes: Array<{ id: string; state: string; snapshot: unknown }>;
      };
    let body = await read();
    expect(body.nodes).toHaveLength(1);
    expect(body.nodes[0]!.state).toBe("fresh");
    expect(body.nodes[0]!.snapshot).toEqual(snapshot);

    h.clock.now += 31_000;
    expect((await read()).nodes[0]!.state).toBe("stale");
    h.clock.now += 30_000;
    expect((await read()).nodes[0]!.state).toBe("offline");

    expect(await h.agent.sendSnapshot(snapshot, false)).toBe(204);
    body = await read();
    expect(body.nodes[0]!.state).toBe("fresh");
  });

  it("are refused without a valid node token, and once the node is revoked", async () => {
    const h = await setup();
    const bad = await h.app.inject({ method: "POST", url: "/agent/v1/snapshot", headers: { authorization: "Bearer nope" }, payload: { schemaVersion: 1 } });
    expect(bad.statusCode).toBe(401);
    h.admin.revokeNode(h.router.id);
    expect(await h.agent.sendSnapshot({ schemaVersion: 1 })).toBe(401);
  });

  it("must carry a schema version", async () => {
    const h = await setup();
    expect(await h.agent.sendSnapshot({ board: {} })).toBe(400);
  });

  it("keep a counter history per node", async () => {
    const h = await setup();
    await h.agent.sendSnapshot(fixture("mesh-site/router.json"));
    h.clock.now += 10_000;
    await h.agent.sendSnapshot(fixture("mesh-site/router.json"));
    const body = (await h.app.inject({ url: `/app/v1/nodes/${h.router.id}/counters`, headers: h.appAuth })).json() as {
      samples: Array<{ at: number; devices: Record<string, { rx: number; tx: number }> }>;
    };
    expect(body.samples).toHaveLength(2);
    expect(body.samples[1]!.at - body.samples[0]!.at).toBe(10_000);
    expect(Object.keys(body.samples[0]!.devices)).toContain("br-lan");
  });
});

describe("commands", () => {
  it("go from the app to the agent and back, waking a waiting agent", async () => {
    const h = await setup();
    const waiting = h.agent.poll(5);
    const issued = await h.app.inject({
      method: "POST",
      url: `/app/v1/nodes/${h.router.id}/commands`,
      headers: h.appAuth,
      payload: { name: "ping", args: { host: "1.1.1.1" } },
    });
    expect(issued.statusCode).toBe(202);
    const { id } = issued.json() as { id: string };

    const commands = await waiting;
    expect(commands).toEqual([{ id, name: "ping", args: { host: "1.1.1.1" } }]);
    expect(await h.agent.poll(0)).toEqual([]);

    const result = h.app.inject({ url: `/app/v1/commands/${id}?wait=5`, headers: h.appAuth });
    expect(await h.agent.report(id, "succeeded", { exitCode: 0, output: "64 bytes" })).toBe(204);
    const done = (await result).json() as { status: string; result: unknown };
    expect(done.status).toBe("succeeded");
    expect(done.result).toEqual({ exitCode: 0, output: "64 bytes" });
  });

  it("outside the allowlist or with bad arguments never reach the agent", async () => {
    const h = await setup();
    const issue = (payload: Record<string, unknown>) =>
      h.app.inject({ method: "POST", url: `/app/v1/nodes/${h.router.id}/commands`, headers: h.appAuth, payload });
    expect((await issue({ name: "exec", args: { cmd: "id" } })).statusCode).toBe(400);
    expect((await issue({ name: "ping", args: { host: "1.1.1.1; reboot" } })).statusCode).toBe(400);
    expect((await issue({ name: "set_wifi", args: { ssid: "home", key: "short" } })).statusCode).toBe(400);
    expect((await issue({ name: "reboot", args: { now: true } })).statusCode).toBe(400);
    expect(await h.agent.poll(0)).toEqual([]);
  });

  it("are recorded with who issued them", async () => {
    const h = await setup();
    await h.app.inject({ method: "POST", url: `/app/v1/nodes/${h.router.id}/commands`, headers: h.appAuth, payload: { name: "reboot" } });
    const row = h.db.query.commands.findFirst().sync();
    expect(row?.issuer).toBe(`device:${h.deviceId}`);
    expect(row?.name).toBe("reboot");
  });

  it("expire when no agent fetches them in time", async () => {
    const h = await setup();
    const { id } = (
      await h.app.inject({ method: "POST", url: `/app/v1/nodes/${h.router.id}/commands`, headers: h.appAuth, payload: { name: "reboot" } })
    ).json() as { id: string };
    h.clock.now += 121_000;
    expect(await h.agent.poll(0)).toEqual([]);
    const view = (await h.app.inject({ url: `/app/v1/commands/${id}`, headers: h.appAuth })).json() as { status: string };
    expect(view.status).toBe("expired");
  });

  it("can only be answered by the node they were sent to", async () => {
    const h = await setup();
    const other = h.admin.addNode(h.site.id, "AP");
    const { id } = (
      await h.app.inject({ method: "POST", url: `/app/v1/nodes/${h.router.id}/commands`, headers: h.appAuth, payload: { name: "reboot" } })
    ).json() as { id: string };
    await h.agent.poll(0);
    const foreign = await h.app.inject({
      method: "POST",
      url: `/agent/v1/commands/${id}/result`,
      headers: { authorization: `Bearer ${other.token}` },
      payload: { status: "succeeded" },
    });
    expect(foreign.statusCode).toBe(409);
    expect(await h.agent.report(id, "succeeded")).toBe(204);
    expect(await h.agent.report(id, "failed")).toBe(409);
  });
});

describe("pairing and app tokens", () => {
  it("a code works once and only before it expires", async () => {
    const h = await setup();
    const { code } = h.admin.createPairingCode("tablet", 60_000);
    const pairWith = (c: string) => h.app.inject({ method: "POST", url: "/app/v1/pair", payload: { code: c } });
    expect((await pairWith(code.toLowerCase().replaceAll("-", " "))).statusCode).toBe(200);
    expect((await pairWith(code)).statusCode).toBe(403);
    const late = h.admin.createPairingCode("tablet", 60_000);
    h.clock.now += 60_001;
    expect((await pairWith(late.code)).statusCode).toBe(403);
  });

  it("a revoked device is refused", async () => {
    const h = await setup();
    expect((await h.app.inject({ url: "/app/v1/sites", headers: h.appAuth })).statusCode).toBe(200);
    h.admin.revokeDevice(h.deviceId);
    expect((await h.app.inject({ url: "/app/v1/sites", headers: h.appAuth })).statusCode).toBe(401);
    expect((await h.app.inject({ url: "/app/v1/sites" })).statusCode).toBe(401);
  });

  it("config is public and says what is enabled", async () => {
    const h = await setup();
    const body = (await h.app.inject({ url: "/app/v1/config" })).json();
    expect(body).toEqual({ auth: { pairing: true, oidc: null }, features: { starlink: false } });
  });
});

describe("push targets", () => {
  it("are registered by token and removed by their owner", async () => {
    const h = await setup();
    const push = (method: "POST" | "DELETE", payload: object) =>
      h.app.inject({ method, url: "/app/v1/push", headers: h.appAuth, payload });
    expect((await push("POST", { endpoint: "https://ntfy.example/up1" })).statusCode).toBe(400);
    expect((await push("POST", { token: "fcm-token-1" })).statusCode).toBe(204);
    expect(h.db.query.pushTargets.findMany().sync()).toHaveLength(1);
    expect((await push("DELETE", { token: "fcm-token-1" })).statusCode).toBe(204);
    expect(h.db.query.pushTargets.findMany().sync()).toHaveLength(0);
  });
});
