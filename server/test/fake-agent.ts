import { gzipSync } from "node:zlib";
import type { FastifyInstance } from "fastify";

export interface AgentCommand {
  id: string;
  name: string;
  args: unknown;
}

/**
 * Speaks the agent side of the API as the real agent will: gzip snapshots, long-poll for commands, answer them.
 * [answer] decides the outcome of each command.
 */
export class FakeAgent {
  constructor(
    private readonly app: FastifyInstance,
    private readonly token: string,
    private readonly answer: (command: AgentCommand) => { status: "succeeded" | "failed"; result?: unknown } = () => ({
      status: "succeeded",
    }),
  ) {}

  async sendSnapshot(snapshot: unknown, gzip = true): Promise<number> {
    const json = JSON.stringify(snapshot);
    const response = await this.app.inject({
      method: "POST",
      url: "/agent/v1/snapshot",
      headers: {
        authorization: `Bearer ${this.token}`,
        "content-type": "application/json",
        ...(gzip ? { "content-encoding": "gzip" } : {}),
      },
      payload: gzip ? gzipSync(json) : json,
    });
    return response.statusCode;
  }

  async poll(waitSeconds = 0): Promise<AgentCommand[]> {
    const response = await this.app.inject({
      method: "GET",
      url: `/agent/v1/commands?wait=${waitSeconds}`,
      headers: { authorization: `Bearer ${this.token}` },
    });
    if (response.statusCode !== 200) throw new Error(`poll failed: HTTP ${response.statusCode}`);
    return (response.json() as { commands: AgentCommand[] }).commands;
  }

  async report(id: string, status: "succeeded" | "failed", result?: unknown): Promise<number> {
    const response = await this.app.inject({
      method: "POST",
      url: `/agent/v1/commands/${id}/result`,
      headers: { authorization: `Bearer ${this.token}` },
      payload: { status, ...(result === undefined ? {} : { result }) },
    });
    return response.statusCode;
  }

  /** One long-poll round: fetch the pending commands and answer each. */
  async serveOnce(waitSeconds = 0): Promise<AgentCommand[]> {
    const commands = await this.poll(waitSeconds);
    for (const command of commands) {
      const { status, result } = this.answer(command);
      await this.report(command.id, status, result);
    }
    return commands;
  }
}
