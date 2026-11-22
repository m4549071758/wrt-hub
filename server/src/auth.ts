import { and, eq, gt, isNull } from "drizzle-orm";
import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";
import { z } from "zod";
import type { OidcConfig } from "./config.js";
import type { Db } from "./db/index.js";
import { devices, nodes, pairingCodes } from "./db/schema.js";
import { hashSecret, newId, newToken, normalizeCode } from "./tokens.js";

export function bearer(header: string | undefined): string | undefined {
  const match = header?.match(/^Bearer\s+(\S+)$/i);
  return match?.[1];
}

export interface AgentPrincipal {
  nodeId: string;
  siteId: string;
}

export function authenticateNode(db: Db, token: string): AgentPrincipal | undefined {
  return db
    .select({ nodeId: nodes.id, siteId: nodes.siteId })
    .from(nodes)
    .where(and(eq(nodes.tokenHash, hashSecret(token)), isNull(nodes.revokedAt)))
    .get();
}

/** Who issued an app request, as recorded in the audit log: `device:<id>` or `oidc:<sub>`. */
export interface AppPrincipal {
  issuer: string;
}

export function authenticateDevice(db: Db, token: string, now: number): AppPrincipal | undefined {
  const device = db
    .select({ id: devices.id })
    .from(devices)
    .where(and(eq(devices.tokenHash, hashSecret(token)), isNull(devices.revokedAt)))
    .get();
  if (!device) return undefined;
  db.update(devices).set({ lastSeenAt: now }).where(eq(devices.id, device.id)).run();
  return { issuer: `device:${device.id}` };
}

/** Trades a one-time pairing code for a device token; undefined when the code is unknown, used or expired. */
export function pair(db: Db, code: string, now: number): { deviceId: string; token: string } | undefined {
  return db.transaction((tx) => {
    const codeHash = hashSecret(normalizeCode(code));
    const row = tx
      .select()
      .from(pairingCodes)
      .where(and(eq(pairingCodes.codeHash, codeHash), isNull(pairingCodes.usedAt), gt(pairingCodes.expiresAt, now)))
      .get();
    if (!row) return undefined;
    tx.update(pairingCodes).set({ usedAt: now }).where(eq(pairingCodes.codeHash, codeHash)).run();
    const deviceId = newId();
    const token = newToken();
    tx.insert(devices).values({ id: deviceId, name: row.deviceName, tokenHash: hashSecret(token), createdAt: now }).run();
    return { deviceId, token };
  });
}

/**
 * Verifies OIDC access tokens (JWTs) against the issuer's keys: signature, issuer, audience and expiry. The key set
 * comes from the issuer's discovery document unless [keys] is given (tests).
 */
export class OidcVerifier {
  private keys: Promise<JWTVerifyGetKey> | undefined;

  constructor(
    private readonly config: OidcConfig,
    keys?: JWTVerifyGetKey,
    private readonly fetchFn: typeof fetch = fetch,
  ) {
    if (keys) this.keys = Promise.resolve(keys);
  }

  async verify(token: string): Promise<AppPrincipal | undefined> {
    try {
      const { payload } = await jwtVerify(token, await this.keySet(), {
        issuer: this.config.issuer,
        audience: this.config.audience,
      });
      return payload.sub ? { issuer: `oidc:${payload.sub}` } : undefined;
    } catch {
      return undefined;
    }
  }

  private keySet(): Promise<JWTVerifyGetKey> {
    this.keys ??= this.discover().catch((error: unknown) => {
      this.keys = undefined;
      throw error;
    });
    return this.keys;
  }

  private async discover(): Promise<JWTVerifyGetKey> {
    const base = this.config.issuer.replace(/\/$/, "");
    const response = await this.fetchFn(`${base}/.well-known/openid-configuration`, { signal: AbortSignal.timeout(10_000) });
    if (!response.ok) throw new Error(`OIDC discovery failed: HTTP ${response.status}`);
    const document = DiscoveryDocument.safeParse(await response.json());
    if (!document.success) throw new Error("OIDC discovery document has no jwks_uri");
    return createRemoteJWKSet(new URL(document.data.jwks_uri));
  }
}

const DiscoveryDocument = z.looseObject({ jwks_uri: z.url() });
