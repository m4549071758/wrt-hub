import { and, eq, isNull } from "drizzle-orm";
import type { Db } from "./db/index.js";
import { devices, nodes, pairingCodes, sites } from "./db/schema.js";
import { hashSecret, newId, newPairingCode, newToken, normalizeCode } from "./tokens.js";

/** Operations of the admin CLI. Secrets are returned once here and stored only as hashes. */
export class Admin {
  constructor(
    private readonly db: Db,
    private readonly now: () => number = Date.now,
  ) {}

  addSite(name: string): { id: string } {
    const id = newId();
    this.db.insert(sites).values({ id, name, createdAt: this.now() }).run();
    return { id };
  }

  listSites() {
    return this.db.select().from(sites).all();
  }

  addNode(siteId: string, label: string): { id: string; token: string } {
    if (!this.db.select().from(sites).where(eq(sites.id, siteId)).get()) throw new Error(`no site ${siteId}`);
    const id = newId();
    const token = newToken();
    this.db.insert(nodes).values({ id, siteId, label, tokenHash: hashSecret(token), createdAt: this.now() }).run();
    return { id, token };
  }

  /** A new token for [nodeId]; the old one stops working at once. */
  rotateNodeToken(nodeId: string): { token: string } {
    const token = newToken();
    const changed = this.db
      .update(nodes)
      .set({ tokenHash: hashSecret(token), revokedAt: null })
      .where(eq(nodes.id, nodeId))
      .run().changes;
    if (changed === 0) throw new Error(`no node ${nodeId}`);
    return { token };
  }

  revokeNode(nodeId: string): void {
    const changed = this.db.update(nodes).set({ revokedAt: this.now() }).where(eq(nodes.id, nodeId)).run().changes;
    if (changed === 0) throw new Error(`no node ${nodeId}`);
  }

  listNodes() {
    return this.db
      .select({ id: nodes.id, siteId: nodes.siteId, label: nodes.label, revokedAt: nodes.revokedAt })
      .from(nodes)
      .all();
  }

  /** A one-time code for pairing a phone called [deviceName], valid for [ttlMs]. */
  createPairingCode(deviceName: string, ttlMs: number): { code: string; expiresAt: number } {
    const code = newPairingCode();
    const expiresAt = this.now() + ttlMs;
    this.db.insert(pairingCodes).values({ codeHash: hashSecret(normalizeCode(code)), deviceName, expiresAt }).run();
    return { code, expiresAt };
  }

  listDevices() {
    return this.db
      .select({ id: devices.id, name: devices.name, lastSeenAt: devices.lastSeenAt, revokedAt: devices.revokedAt })
      .from(devices)
      .all();
  }

  revokeDevice(deviceId: string): void {
    const changed = this.db
      .update(devices)
      .set({ revokedAt: this.now() })
      .where(and(eq(devices.id, deviceId), isNull(devices.revokedAt)))
      .run().changes;
    if (changed === 0) throw new Error(`no active device ${deviceId}`);
  }
}
