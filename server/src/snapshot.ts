/**
 * The server keeps snapshots opaque. These are the only fields it reads, for notifications and the counter history;
 * everything else is the app's to interpret.
 */

type Json = unknown;

function record(value: Json): Record<string, Json> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, Json>) : undefined;
}

function list(value: Json): Json[] {
  return Array.isArray(value) ? value : [];
}

const MAC = /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/;

function mac(value: Json): string | undefined {
  if (typeof value !== "string") return undefined;
  const lower = value.toLowerCase();
  return MAC.test(lower) ? lower : undefined;
}

/** True when an upstream interface (`wan*`, `wwan*`) is up, false when all are down, null when the node has none. */
export function wanUp(snapshot: Json): boolean | null {
  const upstream = list(record(record(snapshot)?.["interfaces"])?.["interface"])
    .map(record)
    .filter((i): i is Record<string, Json> => {
      const name = i?.["interface"];
      return typeof name === "string" && (name.startsWith("wan") || name.startsWith("wwan"));
    });
  if (upstream.length === 0) return null;
  return upstream.some((i) => i["up"] === true);
}

/** MACs of the devices the node sees: DHCP leases, Wi-Fi clients of its APs and live neighbours. */
export function clientMacs(snapshot: Json): Set<string> {
  const root = record(snapshot);
  const macs = new Set<string>();
  for (const lease of list(record(root?.["dhcpLeases"])?.["dhcp_leases"])) {
    const m = mac(record(lease)?.["macaddr"]);
    if (m) macs.add(m);
  }
  for (const iface of list(root?.["wireless"])) {
    const w = record(iface);
    if (record(w?.["info"])?.["mode"] === "Mesh Point") continue;
    for (const station of list(record(w?.["assoclist"])?.["results"])) {
      const m = mac(record(station)?.["mac"]);
      if (m) macs.add(m);
    }
  }
  const neighbors = root?.["neighbors"];
  if (typeof neighbors === "string") {
    for (const line of neighbors.split("\n")) {
      const tokens = line.trim().split(/\s+/);
      const state = tokens[tokens.length - 1];
      if (state === "FAILED" || state === "INCOMPLETE") continue;
      const at = tokens.indexOf("lladdr");
      const m = at >= 0 ? mac(tokens[at + 1]) : undefined;
      if (m) macs.add(m);
    }
  }
  return macs;
}

export interface DeviceCounter {
  rx: number;
  tx: number;
}

/** rx/tx byte counters per network device, from `network.device status`. */
export function deviceCounters(snapshot: Json): Record<string, DeviceCounter> {
  const out: Record<string, DeviceCounter> = {};
  const devices = record(record(snapshot)?.["devices"]) ?? {};
  for (const [name, device] of Object.entries(devices)) {
    const stats = record(record(device)?.["statistics"]);
    const rx = stats?.["rx_bytes"];
    const tx = stats?.["tx_bytes"];
    if (typeof rx === "number" && typeof tx === "number") out[name] = { rx, tx };
  }
  return out;
}
