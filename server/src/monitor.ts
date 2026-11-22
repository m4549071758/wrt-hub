import { and, eq, isNull } from "drizzle-orm";
import type { Db } from "./db/index.js";
import { events, knownClients, nodes, sites } from "./db/schema.js";
import type { Logger, Notification, Notifier } from "./notify.js";
import { clientMacs, wanUp } from "./snapshot.js";

type Kind = Notification["kind"];

/**
 * Turns snapshots and silence into events: a node offline after [offlineMs] without a snapshot (counted from the
 * server's start for nodes not heard from since), back online, its WAN going down or up, and MACs a site has never
 * seen. Each change is reported once. A site's first snapshot only records its devices, or every device would be new.
 */
export class Monitor {
  private readonly lastSeen = new Map<string, number>();
  private readonly offline = new Set<string>();
  private readonly wan = new Map<string, boolean>();
  private readonly startedAt: number;

  constructor(
    private readonly db: Db,
    private readonly notify: Notifier,
    private readonly log: Logger,
    private readonly offlineMs: number,
    private readonly now: () => number = Date.now,
  ) {
    this.startedAt = now();
  }

  onSnapshot(nodeId: string, snapshot: unknown): void {
    const node = this.nodeInfo(nodeId);
    if (!node) return;
    const at = this.now();
    this.lastSeen.set(nodeId, at);
    if (this.offline.delete(nodeId)) this.record("node_online", node, at);

    const up = wanUp(snapshot);
    if (up !== null) {
      const previous = this.wan.get(nodeId);
      this.wan.set(nodeId, up);
      if (previous !== undefined && previous !== up) this.record(up ? "wan_up" : "wan_down", node, at);
    }

    const seen = clientMacs(snapshot);
    if (seen.size === 0) return;
    const known = new Set(
      this.db
        .select({ mac: knownClients.mac })
        .from(knownClients)
        .where(eq(knownClients.siteId, node.siteId))
        .all()
        .map((r) => r.mac),
    );
    const fresh = [...seen].filter((m) => !known.has(m)).sort();
    if (fresh.length === 0) return;
    this.db
      .insert(knownClients)
      .values(fresh.map((mac) => ({ siteId: node.siteId, mac, firstSeenAt: at })))
      .onConflictDoNothing()
      .run();
    if (known.size > 0) this.record("new_client", node, at, fresh);
  }

  /** Marks nodes silent for longer than [offlineMs]; call periodically. */
  tick(): void {
    const at = this.now();
    const active = this.db.select({ id: nodes.id }).from(nodes).where(isNull(nodes.revokedAt)).all();
    for (const { id } of active) {
      if (this.offline.has(id)) continue;
      const last = this.lastSeen.get(id) ?? this.startedAt;
      if (at - last <= this.offlineMs) continue;
      const node = this.nodeInfo(id);
      if (!node) continue;
      this.offline.add(id);
      this.record("node_offline", node, at);
    }
  }

  private nodeInfo(nodeId: string) {
    return this.db
      .select({ id: nodes.id, label: nodes.label, siteId: nodes.siteId, siteName: sites.name })
      .from(nodes)
      .innerJoin(sites, eq(sites.id, nodes.siteId))
      .where(and(eq(nodes.id, nodeId), isNull(nodes.revokedAt)))
      .get();
  }

  private record(kind: Kind, node: { id: string; label: string; siteId: string; siteName: string }, at: number, macs?: string[]): void {
    this.db
      .insert(events)
      .values({ siteId: node.siteId, nodeId: node.id, kind, detail: macs ? { macs } : null, createdAt: at })
      .run();
    const notification: Notification = {
      kind,
      siteId: node.siteId,
      siteName: node.siteName,
      nodeId: node.id,
      nodeLabel: node.label,
      at,
      ...(macs ? { macs } : {}),
    };
    this.notify(notification).catch((error: unknown) => this.log.warn({ err: error }, "notification failed"));
  }
}
