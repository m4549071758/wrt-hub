import { and, eq, inArray, lt, or } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "./db/index.js";
import { commands } from "./db/schema.js";
import { newId } from "./tokens.js";

const Mac = z.string().regex(/^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$/).transform((m) => m.toLowerCase());

/** A host name or an IPv4/IPv6 address, as the app's diagnostics accept; nothing a shell could interpret. */
const Host = z
  .string()
  .min(1)
  .max(253)
  .regex(/^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$|^[0-9A-Fa-f:.]+$/);

const Ssid = z.string().refine((s) => {
  const bytes = Buffer.byteLength(s, "utf8");
  return bytes >= 1 && bytes <= 32;
}, "the SSID must be 1 to 32 bytes");

const Psk = z.string().regex(/^[\x20-\x7e]{8,63}$/, "the password must be 8 to 63 printable ASCII characters");

/**
 * The allowlist: the only operations relayed to agents, with their arguments. Agents check them again; anything
 * else is refused here so it never reaches a router.
 */
export const COMMANDS = {
  block_client: z.strictObject({ mac: Mac }),
  unblock_client: z.strictObject({ mac: Mac }),
  set_wifi: z.strictObject({ ssid: Ssid, key: Psk }),
  reboot: z.strictObject({}),
  ping: z.strictObject({ host: Host }),
  traceroute: z.strictObject({ host: Host }),
  /** Snapshots every second for the next 60 seconds; the app renews the lease while it shows live rates. */
  set_fast_mode: z.strictObject({}),
} as const;

export type CommandName = keyof typeof COMMANDS;

export const CommandRequest = z.strictObject({ name: z.string(), args: z.unknown().default({}) });

export function parseCommand(name: string, args: unknown): { name: CommandName; args: unknown } | { error: string } {
  if (!Object.hasOwn(COMMANDS, name)) return { error: `unknown command ${name}` };
  const parsed = COMMANDS[name as CommandName].safeParse(args);
  if (!parsed.success) return { error: z.prettifyError(parsed.error) };
  return { name: name as CommandName, args: parsed.data };
}

export const CommandResult = z.strictObject({
  status: z.enum(["succeeded", "failed"]),
  result: z.unknown().optional(),
});

export interface CommandView {
  id: string;
  nodeId: string;
  name: string;
  args: unknown;
  status: "pending" | "sent" | "succeeded" | "failed" | "expired";
  result: unknown;
  createdAt: number;
  sentAt: number | null;
  finishedAt: number | null;
}

type Waiter = () => void;

/**
 * Commands live in SQLite (they are also the audit log); waiters are in memory. A command an agent did not fetch
 * within [pickupMs], or did not answer within [answerMs] after fetching, expires.
 */
export class CommandQueue {
  private readonly agentWaiters = new Map<string, Set<Waiter>>();
  private readonly resultWaiters = new Map<string, Set<Waiter>>();

  constructor(
    private readonly db: Db,
    private readonly now: () => number = Date.now,
    private readonly pickupMs = 120_000,
    private readonly answerMs = 300_000,
  ) {}

  enqueue(nodeId: string, issuer: string, name: CommandName, args: unknown): CommandView {
    const row = {
      id: newId(),
      nodeId,
      issuer,
      name,
      args,
      status: "pending" as const,
      result: null,
      createdAt: this.now(),
      sentAt: null,
      finishedAt: null,
    };
    this.db.insert(commands).values(row).run();
    this.wake(this.agentWaiters, nodeId);
    return row;
  }

  get(id: string): CommandView | undefined {
    this.expire();
    return this.db.select().from(commands).where(eq(commands.id, id)).get();
  }

  /** Pending commands of [nodeId], marked sent; waits up to [waitMs] for one when there is none. */
  async take(nodeId: string, waitMs: number, signal?: AbortSignal): Promise<CommandView[]> {
    const grab = () => {
      this.expire();
      return this.db.transaction((tx) => {
        const rows = tx
          .select()
          .from(commands)
          .where(and(eq(commands.nodeId, nodeId), eq(commands.status, "pending")))
          .orderBy(commands.createdAt)
          .all();
        if (rows.length === 0) return rows;
        const sentAt = this.now();
        tx.update(commands)
          .set({ status: "sent", sentAt })
          .where(inArray(commands.id, rows.map((r) => r.id)))
          .run();
        return rows.map((r) => ({ ...r, status: "sent" as const, sentAt }));
      });
    };
    const ready = grab();
    if (ready.length > 0 || waitMs <= 0) return ready;
    await this.waitFor(this.agentWaiters, nodeId, waitMs, signal);
    return grab();
  }

  /** Records the agent's answer; false when the command is not one this node was sent and still owes. */
  finish(nodeId: string, id: string, status: "succeeded" | "failed", result: unknown): boolean {
    this.expire();
    const changed = this.db
      .update(commands)
      .set({ status, result: result ?? null, finishedAt: this.now() })
      .where(and(eq(commands.id, id), eq(commands.nodeId, nodeId), eq(commands.status, "sent")))
      .run().changes;
    if (changed > 0) this.wake(this.resultWaiters, id);
    return changed > 0;
  }

  /** The command once finished or expired, waiting up to [waitMs] for that. */
  async awaitResult(id: string, waitMs: number, signal?: AbortSignal): Promise<CommandView | undefined> {
    const current = this.get(id);
    if (!current || waitMs <= 0 || isFinal(current.status)) return current;
    await this.waitFor(this.resultWaiters, id, waitMs, signal);
    return this.get(id);
  }

  /** Gives up on commands no agent fetched within [pickupMs], or answered within [answerMs] of fetching. */
  private expire(): void {
    const now = this.now();
    this.db
      .update(commands)
      .set({ status: "expired", finishedAt: now })
      .where(
        or(
          and(eq(commands.status, "pending"), lt(commands.createdAt, now - this.pickupMs)),
          and(eq(commands.status, "sent"), lt(commands.sentAt, now - this.answerMs)),
        ),
      )
      .run();
  }

  private waitFor(map: Map<string, Set<Waiter>>, key: string, waitMs: number, signal?: AbortSignal): Promise<void> {
    const { promise, resolve } = Promise.withResolvers<void>();
    const set = map.get(key) ?? new Set<Waiter>();
    map.set(key, set);
    const done = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", done);
      set.delete(done);
      if (set.size === 0) map.delete(key);
      resolve();
    };
    const timer = setTimeout(done, waitMs);
    signal?.addEventListener("abort", done, { once: true });
    set.add(done);
    return promise;
  }

  private wake(map: Map<string, Set<Waiter>>, key: string): void {
    for (const waiter of [...(map.get(key) ?? [])]) waiter();
  }
}

export function isFinal(status: CommandView["status"]): boolean {
  return status === "succeeded" || status === "failed" || status === "expired";
}
