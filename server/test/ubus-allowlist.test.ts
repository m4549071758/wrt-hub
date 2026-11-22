import { describe, expect, it } from "vitest";
import { z } from "zod";
import { parseCommand } from "../src/commands.js";
import { fixture } from "./harness.js";

const RelayCases = z.array(
  z.strictObject({ object: z.string(), method: z.string(), args: z.record(z.string(), z.unknown()), allowed: z.boolean() }),
);

// The agent runs the same cases through its own copy of the allowlist (agent/test/test_agent.py).
const cases = RelayCases.parse(fixture("relay-cases.json"));

describe("ubus relay allowlist", () => {
  it.each(cases.map((c) => [`${c.allowed ? "allows" : "refuses"} ${c.object}.${c.method} ${JSON.stringify(c.args)}`, c] as const))("%s", (_name, c) => {
    const parsed = parseCommand("ubus", { object: c.object, method: c.method, args: c.args });
    expect("error" in parsed).toBe(!c.allowed);
  });
});
