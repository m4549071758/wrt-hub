import { eq } from "drizzle-orm";
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

/**
 * Sends to every registered UnifiedPush endpoint: a POST whose body is the message, here JSON. An endpoint that
 * answers 404 or 410 is gone (the distributor dropped the registration) and is removed.
 */
export function unifiedPushNotifier(db: Db, log: Logger, fetchFn: typeof fetch = fetch, timeoutMs = 10_000): Notifier {
  return async (notification) => {
    const targets = db.select().from(pushTargets).all();
    const body = JSON.stringify(notification);
    await Promise.all(
      targets.map(async ({ endpoint }) => {
        try {
          const response = await fetchFn(endpoint, {
            method: "POST",
            headers: { "content-type": "application/json", ttl: "86400", urgency: "high" },
            body,
            signal: AbortSignal.timeout(timeoutMs),
          });
          if (response.status === 404 || response.status === 410) {
            db.delete(pushTargets).where(eq(pushTargets.endpoint, endpoint)).run();
          } else if (!response.ok) {
            log.warn({ status: response.status }, "push endpoint refused the message");
          }
        } catch (error) {
          log.warn({ err: error }, "push endpoint unreachable");
        }
      }),
    );
  };
}
