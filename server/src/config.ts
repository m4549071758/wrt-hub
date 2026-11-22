import { z } from "zod";
import { ServiceAccount } from "./notify.js";

const optionalUrl = z.url().optional();

const EnvSchema = z
  .object({
    WRTHUB_LISTEN_HOST: z.string().default("0.0.0.0"),
    WRTHUB_LISTEN_PORT: z.coerce.number().int().min(1).max(65535).default(8080),
    WRTHUB_DB_PATH: z.string().min(1).default("/data/wrthub.db"),
    /** Seconds without a snapshot after which a node is stale and, past [WRTHUB_OFFLINE_SECONDS], offline. */
    WRTHUB_STALE_SECONDS: z.coerce.number().int().positive().default(30),
    WRTHUB_OFFLINE_SECONDS: z.coerce.number().int().positive().default(60),
    WRTHUB_OIDC_ISSUER: optionalUrl,
    WRTHUB_OIDC_AUDIENCE: z.string().min(1).optional(),
    /** Client ID the app uses for the authorization code flow; only published through /app/v1/config. */
    WRTHUB_OIDC_CLIENT_ID: z.string().min(1).optional(),
    WRTHUB_INFLUX_URL: optionalUrl,
    WRTHUB_INFLUX_TOKEN: z.string().min(1).optional(),
    WRTHUB_INFLUX_ORG: z.string().min(1).optional(),
    WRTHUB_INFLUX_BUCKET: z.string().min(1).optional(),
    /** A Google service account key (JSON) allowed to send through FCM; notifications are off without it. */
    WRTHUB_FCM_SERVICE_ACCOUNT: z.string().min(1).optional(),
  })
  .superRefine((env, ctx) => {
    const oidc = [env.WRTHUB_OIDC_ISSUER, env.WRTHUB_OIDC_AUDIENCE, env.WRTHUB_OIDC_CLIENT_ID];
    if (oidc.some((v) => v !== undefined) && oidc.some((v) => v === undefined)) {
      ctx.addIssue({ code: "custom", message: "OIDC needs WRTHUB_OIDC_ISSUER, WRTHUB_OIDC_AUDIENCE and WRTHUB_OIDC_CLIENT_ID together" });
    }
    const influx = [env.WRTHUB_INFLUX_URL, env.WRTHUB_INFLUX_TOKEN, env.WRTHUB_INFLUX_ORG, env.WRTHUB_INFLUX_BUCKET];
    if (influx.some((v) => v !== undefined) && influx.some((v) => v === undefined)) {
      ctx.addIssue({ code: "custom", message: "Starlink needs WRTHUB_INFLUX_URL, _TOKEN, _ORG and _BUCKET together" });
    }
    if (env.WRTHUB_OFFLINE_SECONDS <= env.WRTHUB_STALE_SECONDS) {
      ctx.addIssue({ code: "custom", message: "WRTHUB_OFFLINE_SECONDS must exceed WRTHUB_STALE_SECONDS" });
    }
  });

export interface OidcConfig {
  issuer: string;
  audience: string;
  clientId: string;
}

export interface InfluxConfig {
  url: string;
  token: string;
  org: string;
  bucket: string;
}

export interface Config {
  host: string;
  port: number;
  dbPath: string;
  staleMs: number;
  offlineMs: number;
  oidc: OidcConfig | null;
  influx: InfluxConfig | null;
  fcm: ServiceAccount | null;
}

export function loadConfig(env: NodeJS.ProcessEnv): Config {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    throw new Error(`invalid configuration: ${z.prettifyError(parsed.error)}`);
  }
  const e = parsed.data;
  return {
    host: e.WRTHUB_LISTEN_HOST,
    port: e.WRTHUB_LISTEN_PORT,
    dbPath: e.WRTHUB_DB_PATH,
    staleMs: e.WRTHUB_STALE_SECONDS * 1000,
    offlineMs: e.WRTHUB_OFFLINE_SECONDS * 1000,
    oidc:
      e.WRTHUB_OIDC_ISSUER && e.WRTHUB_OIDC_AUDIENCE && e.WRTHUB_OIDC_CLIENT_ID
        ? { issuer: e.WRTHUB_OIDC_ISSUER, audience: e.WRTHUB_OIDC_AUDIENCE, clientId: e.WRTHUB_OIDC_CLIENT_ID }
        : null,
    influx:
      e.WRTHUB_INFLUX_URL && e.WRTHUB_INFLUX_TOKEN && e.WRTHUB_INFLUX_ORG && e.WRTHUB_INFLUX_BUCKET
        ? { url: e.WRTHUB_INFLUX_URL, token: e.WRTHUB_INFLUX_TOKEN, org: e.WRTHUB_INFLUX_ORG, bucket: e.WRTHUB_INFLUX_BUCKET }
        : null,
    fcm: e.WRTHUB_FCM_SERVICE_ACCOUNT ? parseServiceAccount(e.WRTHUB_FCM_SERVICE_ACCOUNT) : null,
  };
}

/** Errors name only what is wrong: JSON.parse's message would quote the key. */
function parseServiceAccount(text: string): ServiceAccount {
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error("invalid configuration: WRTHUB_FCM_SERVICE_ACCOUNT is not JSON");
  }
  const account = ServiceAccount.safeParse(json);
  if (!account.success) {
    throw new Error("invalid configuration: WRTHUB_FCM_SERVICE_ACCOUNT needs project_id, client_email and private_key");
  }
  return account.data;
}
