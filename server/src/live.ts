import { deviceCounters, type DeviceCounter } from "./snapshot.js";

export interface NodeSnapshot {
  nodeId: string;
  siteId: string;
  receivedAt: number;
  snapshot: unknown;
}

export interface CounterSample {
  at: number;
  devices: Record<string, DeviceCounter>;
}

export type SiteListener = (update: NodeSnapshot) => void;

/**
 * Per-node latest snapshot and the last [historyMs] of device counters, in memory only: after a restart the next
 * snapshot of each agent restores them.
 */
export class LiveState {
  private readonly latest = new Map<string, NodeSnapshot>();
  private readonly counters = new Map<string, CounterSample[]>();
  private readonly listeners = new Map<string, Set<SiteListener>>();

  constructor(private readonly historyMs = 10 * 60_000) {}

  put(update: NodeSnapshot): void {
    this.latest.set(update.nodeId, update);
    const history = this.counters.get(update.nodeId) ?? [];
    history.push({ at: update.receivedAt, devices: deviceCounters(update.snapshot) });
    const cutoff = update.receivedAt - this.historyMs;
    while (history.length > 0 && history[0]!.at < cutoff) history.shift();
    this.counters.set(update.nodeId, history);
    for (const listener of [...(this.listeners.get(update.siteId) ?? [])]) listener(update);
  }

  get(nodeId: string): NodeSnapshot | undefined {
    return this.latest.get(nodeId);
  }

  counterHistory(nodeId: string): CounterSample[] {
    return [...(this.counters.get(nodeId) ?? [])];
  }

  /** Calls [listener] with every new snapshot of the site's nodes until the returned function is called. */
  subscribe(siteId: string, listener: SiteListener): () => void {
    const set = this.listeners.get(siteId) ?? new Set<SiteListener>();
    set.add(listener);
    this.listeners.set(siteId, set);
    return () => {
      set.delete(listener);
      if (set.size === 0) this.listeners.delete(siteId);
    };
  }

  forget(nodeId: string): void {
    this.latest.delete(nodeId);
    this.counters.delete(nodeId);
  }
}
