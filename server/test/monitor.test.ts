import { describe, expect, it } from "vitest";
import { z } from "zod";
import { openDb } from "../src/db/index.js";
import { pushTargets } from "../src/db/schema.js";
import { generateKeyPair, exportPKCS8, decodeJwt } from "jose";
import { fcmNotifier, type Notification } from "../src/notify.js";
import { clientMacs, wanUp } from "../src/snapshot.js";
import { fixture, perTest } from "./harness.js";

const setup = perTest();

type Snapshot = Record<string, unknown>;

function withExtraLease(snapshot: Snapshot, mac: string): Snapshot {
  const leases = snapshot["dhcpLeases"] as { dhcp_leases: unknown[] };
  return { ...snapshot, dhcpLeases: { ...leases, dhcp_leases: [...leases.dhcp_leases, { macaddr: mac, ipaddr: "192.168.1.250" }] } };
}

function withWan(snapshot: Snapshot, up: boolean): Snapshot {
  const interfaces = snapshot["interfaces"] as { interface: Array<Record<string, unknown>> };
  return {
    ...snapshot,
    interfaces: { interface: interfaces.interface.map((i) => (String(i["interface"]).startsWith("wan") ? { ...i, up } : i)) },
  };
}

const kinds = (notifications: Notification[]) => notifications.map((n) => n.kind);

describe("snapshot fields the server reads", () => {
  it("WAN state and client MACs come out of the fixtures", () => {
    const router = fixture("mesh-site/router.json");
    expect(wanUp(router)).toBe(true);
    expect(wanUp(fixture("mesh-site/ap.json"))).toBeNull();
    expect(wanUp(fixture("wired-site/router.json"))).toBe(true);
    const macs = clientMacs(router);
    expect(macs.size).toBeGreaterThan(0);
    expect([...macs].every((m) => m === m.toLowerCase())).toBe(true);
    expect(clientMacs(fixture("wired-site/router.json")).size).toBeGreaterThan(macs.size);
  });
});

describe("events", () => {
  it("a site's first snapshot records its devices silently; a later new MAC is reported once", async () => {
    const h = await setup();
    const base = fixture("mesh-site/router.json");
    await h.agent.sendSnapshot(base);
    expect(h.notifications).toEqual([]);

    await h.agent.sendSnapshot(withExtraLease(base, "CE:11:22:00:00:99"));
    await h.agent.sendSnapshot(withExtraLease(base, "ce:11:22:00:00:99"));
    expect(kinds(h.notifications)).toEqual(["new_client"]);
    expect(h.notifications[0]!.macs).toEqual(["ce:11:22:00:00:99"]);
    expect(h.notifications[0]!.siteName).toBe("Cabin");
  });

  it("WAN changes are reported on the transition only", async () => {
    const h = await setup();
    const base = fixture("mesh-site/router.json");
    await h.agent.sendSnapshot(base);
    await h.agent.sendSnapshot(withWan(base, false));
    await h.agent.sendSnapshot(withWan(base, false));
    await h.agent.sendSnapshot(base);
    expect(kinds(h.notifications)).toEqual(["wan_down", "wan_up"]);
  });

  it("a silent node goes offline once and comes back online with its next snapshot", async () => {
    const h = await setup();
    await h.agent.sendSnapshot(fixture("mesh-site/router.json"));
    h.clock.now += 60_000;
    h.monitor.tick();
    expect(h.notifications).toEqual([]);
    h.clock.now += 1;
    h.monitor.tick();
    h.monitor.tick();
    expect(kinds(h.notifications)).toEqual(["node_offline"]);
    await h.agent.sendSnapshot(fixture("mesh-site/router.json"));
    expect(kinds(h.notifications)).toEqual(["node_offline", "node_online"]);
  });

  it("a node never heard from since the server started counts from the start", async () => {
    const h = await setup();
    h.clock.now += 60_001;
    h.monitor.tick();
    expect(kinds(h.notifications)).toEqual(["node_offline"]);
    expect(h.notifications[0]!.nodeLabel).toBe("Router");
  });

  it("events are listed newest first", async () => {
    const h = await setup();
    const base = fixture("mesh-site/router.json");
    await h.agent.sendSnapshot(base);
    await h.agent.sendSnapshot(withWan(base, false));
    h.clock.now += 1;
    await h.agent.sendSnapshot(base);
    const body = (await h.app.inject({ url: `/app/v1/sites/${h.site.id}/events`, headers: h.appAuth })).json() as {
      events: Array<{ kind: string }>;
    };
    expect(body.events.map((e) => e.kind)).toEqual(["wan_up", "wan_down"]);
  });
});

describe("FCM", () => {
  const Sent = z.object({ message: z.object({ fid: z.string(), data: z.record(z.string(), z.string()) }) });

  it("signs in once, sends a data message to every token and drops the unregistered ones", async () => {
    const db = openDb(":memory:");
    db.insert(pushTargets)
      .values([
        { token: "tok-a", owner: "device:1", createdAt: 0 },
        { token: "tok-gone", owner: "device:1", createdAt: 0 },
      ])
      .run();
    const { privateKey } = await generateKeyPair("RS256", { extractable: true });
    const account = { project_id: "demo", client_email: "sender@demo.iam.gserviceaccount.com", private_key: await exportPKCS8(privateKey) };
    const assertions: string[] = [];
    const sent: z.infer<typeof Sent>[] = [];
    const fetchFn: typeof fetch = async (input, init) => {
      const url = String(input);
      if (url === "https://oauth2.googleapis.com/token") {
        assertions.push(new URLSearchParams(String(init?.body)).get("assertion") ?? "");
        return Response.json({ access_token: "at", expires_in: 3600 });
      }
      expect(url).toBe("https://fcm.googleapis.com/v1/projects/demo/messages:send");
      expect(new Headers(init?.headers).get("authorization")).toBe("Bearer at");
      const message = Sent.parse(JSON.parse(String(init?.body)));
      sent.push(message);
      return message.message.fid === "tok-gone"
        ? Response.json({ error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } }, { status: 404 })
        : Response.json({ name: "projects/demo/messages/1" });
    };
    const notify = fcmNotifier(db, { warn: () => {} }, account, fetchFn, () => 1_800_000_000_000);
    await notify({ kind: "new_client", siteId: "s", siteName: "Cabin", nodeId: null, nodeLabel: null, macs: ["ce:11:22:00:00:01", "ce:11:22:00:00:02"], at: 5 });
    await notify({ kind: "wan_down", siteId: "s", siteName: "Cabin", nodeId: "n", nodeLabel: "Router", at: 6 });

    expect(assertions).toHaveLength(1);
    const claims = decodeJwt(assertions[0]!);
    expect(claims).toMatchObject({ iss: account.client_email, aud: "https://oauth2.googleapis.com/token", scope: "https://www.googleapis.com/auth/firebase.messaging" });
    expect(sent.map((s) => s.message.fid).sort()).toEqual(["tok-a", "tok-a", "tok-gone"]);
    expect(sent[0]!.message.data).toEqual({ kind: "new_client", siteId: "s", siteName: "Cabin", at: "5", macs: "ce:11:22:00:00:01,ce:11:22:00:00:02" });
    expect(db.select().from(pushTargets).all().map((t) => t.token)).toEqual(["tok-a"]);
  });
});
