import type { InfluxConfig } from "./config.js";

/** The starlink_exporter fields the app shows; written by the exporter as fields of the `starlink` measurement. */
export const STARLINK_FIELDS = [
  "starlink_dish_up",
  "starlink_dish_uptime_seconds",
  "starlink_dish_pop_ping_latency_seconds",
  "starlink_dish_pop_ping_drop_ratio",
  "starlink_dish_downlink_throughput_bps",
  "starlink_dish_uplink_throughput_bps",
  "starlink_dish_currently_obstructed",
  "starlink_dish_fraction_obstruction_ratio",
  "starlink_dish_last_24h_obstructed_seconds",
  "starlink_dish_power_input_watts",
] as const;

/** Range → aggregation window, so every range comes back as about 30 to 100 points per field. */
export const STARLINK_RANGES = { "15m": "30s", "1h": "1m", "6h": "5m", "24h": "15m" } as const;
export type StarlinkRange = keyof typeof STARLINK_RANGES;

export interface StarlinkSeries {
  field: string;
  points: Array<[time: number, value: number]>;
}

/** Reads the series through InfluxDB's v2 query API (Flux, CSV answer) with a read-only token. */
export async function readStarlink(config: InfluxConfig, range: StarlinkRange, fetchFn: typeof fetch = fetch): Promise<StarlinkSeries[]> {
  const fields = STARLINK_FIELDS.map((f) => `r._field == "${f}"`).join(" or ");
  const query = [
    `from(bucket: ${JSON.stringify(config.bucket)})`,
    `  |> range(start: -${range})`,
    `  |> filter(fn: (r) => r._measurement == "starlink" and (${fields}))`,
    `  |> aggregateWindow(every: ${STARLINK_RANGES[range]}, fn: mean, createEmpty: false)`,
    `  |> keep(columns: ["_time", "_value", "_field"])`,
  ].join("\n");
  const url = new URL("/api/v2/query", config.url);
  url.searchParams.set("org", config.org);
  const response = await fetchFn(url, {
    method: "POST",
    headers: { authorization: `Token ${config.token}`, "content-type": "application/json", accept: "application/csv" },
    body: JSON.stringify({ query, type: "flux", dialect: { header: true, annotations: [] } }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error(`InfluxDB answered HTTP ${response.status}`);
  return parseFluxCsv(await response.text());
}

/** Flux CSV: tables separated by blank lines, each starting with its own header row. */
export function parseFluxCsv(csv: string): StarlinkSeries[] {
  const series = new Map<string, Array<[number, number]>>();
  let header: string[] | undefined;
  for (const raw of csv.split(/\r?\n/)) {
    if (raw.trim() === "") {
      header = undefined;
      continue;
    }
    const cells = raw.split(",");
    if (!header) {
      header = cells;
      continue;
    }
    const field = cells[header.indexOf("_field")];
    const time = Date.parse(cells[header.indexOf("_time")] ?? "");
    const value = Number(cells[header.indexOf("_value")]);
    if (!field || Number.isNaN(time) || !Number.isFinite(value)) continue;
    const points = series.get(field) ?? [];
    points.push([time, value]);
    series.set(field, points);
  }
  return [...series].map(([field, points]) => ({ field, points: points.sort((a, b) => a[0] - b[0]) }));
}
