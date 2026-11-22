import { eq } from "drizzle-orm";
import { importPKCS8, SignJWT } from "jose";
import { z } from "zod";
import type { Db } from "./db/index.js";
import { pushTargets } from "./db/schema.js";

export interface Notification {
  kind: "node_offline" | "node_online" | "wan_down" | "wan_up" | "new_client";
  siteId: string;
  siteName: string;
  nodeId: string | null;
  nodeLabel: string | null;
  /** For new_client: the MACs first seen. */
  macs?: string[];
  at: number;
}

export type Notifier = (notification: Notification) => Promise<void>;

export interface Logger {
  warn(obj: object, msg: string): void;
}

/** The fields of a Google service account key that sending needs. */
export const ServiceAccount = z.looseObject({
  project_id: z.string().min(1),
  client_email: z.string().min(1),
  private_key: z.string().min(1),
});
export type ServiceAccount = z.infer<typeof ServiceAccount>;

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TokenResponse = z.object({ access_token: z.string().min(1), expires_in: z.number().int().positive() });
const FcmError = z.object({
  error: z.object({ details: z.array(z.looseObject({ errorCode: z.string().optional() })).optional() }),
});

/**
 * OAuth access tokens for the service account (JWT bearer grant), reused until a minute before they expire.
 */
function accessTokens(account: ServiceAccount, fetchFn: typeof fetch, now: () => number, timeoutMs: number) {
  let cached: { token: string; expiresAt: number } | undefined;
  return async (): Promise<string> => {
    if (cached && cached.expiresAt > now() + 60_000) return cached.token;
    const issuedAt = Math.floor(now() / 1000);
    const assertion = await new SignJWT({ scope: SCOPE })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(account.client_email)
      .setAudience(TOKEN_URL)
      .setIssuedAt(issuedAt)
      .setExpirationTime(issuedAt + 3600)
      .sign(await importPKCS8(account.private_key, "RS256"));
    const response = await fetchFn(TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) throw new Error(`token endpoint answered ${response.status}`);
    const body = TokenResponse.parse(await response.json());
    cached = { token: body.access_token, expiresAt: now() + body.expires_in * 1000 };
    return cached.token;
  };
}

/**
 * Sends to every registered app instance (its Firebase Installation ID; FCM's `token` field is deprecated) as a data
 * message, so the app words it in the phone's language. An ID that
 * FCM reports as unregistered (the app was removed or its data cleared) is dropped.
 */
export function fcmNotifier(
  db: Db,
  log: Logger,
  account: ServiceAccount,
  fetchFn: typeof fetch = fetch,
  now: () => number = Date.now,
  timeoutMs = 10_000,
): Notifier {
  const token = accessTokens(account, fetchFn, now, timeoutMs);
  const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`;
  return async (notification) => {
    const targets = db.select().from(pushTargets).all();
    if (targets.length === 0) return;
    // FCM data values must be strings.
    const data: Record<string, string> = {
      kind: notification.kind,
      siteId: notification.siteId,
      siteName: notification.siteName,
      at: String(notification.at),
    };
    if (notification.nodeId !== null) data["nodeId"] = notification.nodeId;
    if (notification.nodeLabel !== null) data["nodeLabel"] = notification.nodeLabel;
    if (notification.macs) data["macs"] = notification.macs.join(",");
    const bearer = await token();
    await Promise.all(
      targets.map(async (target) => {
        try {
          const response = await fetchFn(url, {
            method: "POST",
            headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
            body: JSON.stringify({ message: { fid: target.token, data, android: { priority: "high", ttl: "86400s" } } }),
            signal: AbortSignal.timeout(timeoutMs),
          });
          if (response.ok) return;
          const error = FcmError.safeParse(await response.json().catch(() => null));
          const unregistered =
            response.status === 404 ||
            (error.success && (error.data.error.details ?? []).some((d) => d.errorCode === "UNREGISTERED"));
          if (unregistered) {
            db.delete(pushTargets).where(eq(pushTargets.token, target.token)).run();
          } else {
            log.warn({ status: response.status }, "FCM refused the message");
          }
        } catch (error) {
          log.warn({ err: error }, "FCM unreachable");
        }
      }),
    );
  };
}
