import { index, integer, primaryKey, sqliteTable, text } from "drizzle-orm/sqlite-core";

/** Times are epoch milliseconds. Tokens and codes are stored only as SHA-256 hex hashes. */

export const sites = sqliteTable("sites", {
  id: text("id").primaryKey(),
  name: text("name").notNull(),
  createdAt: integer("created_at").notNull(),
});

export const nodes = sqliteTable(
  "nodes",
  {
    id: text("id").primaryKey(),
    siteId: text("site_id")
      .notNull()
      .references(() => sites.id, { onDelete: "cascade" }),
    label: text("label").notNull(),
    tokenHash: text("token_hash").notNull().unique(),
    createdAt: integer("created_at").notNull(),
    revokedAt: integer("revoked_at"),
  },
  (t) => [index("nodes_site").on(t.siteId)],
);

/** Phones paired with a code; [name] is what the user calls the phone in the audit log. */
export const devices = sqliteTable("devices", {
  id: text("id").primaryKey(),
  name: text("name").notNull(),
  tokenHash: text("token_hash").notNull().unique(),
  createdAt: integer("created_at").notNull(),
  lastSeenAt: integer("last_seen_at"),
  revokedAt: integer("revoked_at"),
});

export const pairingCodes = sqliteTable("pairing_codes", {
  codeHash: text("code_hash").primaryKey(),
  deviceName: text("device_name").notNull(),
  expiresAt: integer("expires_at").notNull(),
  usedAt: integer("used_at"),
});

/** Every command, read or write, with who issued it: the audit log. */
export const commands = sqliteTable(
  "commands",
  {
    id: text("id").primaryKey(),
    nodeId: text("node_id")
      .notNull()
      .references(() => nodes.id, { onDelete: "cascade" }),
    /** `device:<id>` or `oidc:<sub>`. */
    issuer: text("issuer").notNull(),
    name: text("name").notNull(),
    args: text("args", { mode: "json" }).notNull(),
    status: text("status", { enum: ["pending", "sent", "succeeded", "failed", "expired"] }).notNull(),
    result: text("result", { mode: "json" }),
    createdAt: integer("created_at").notNull(),
    sentAt: integer("sent_at"),
    finishedAt: integer("finished_at"),
  },
  (t) => [index("commands_node_status").on(t.nodeId, t.status), index("commands_created").on(t.createdAt)],
);

export const events = sqliteTable(
  "events",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    siteId: text("site_id")
      .notNull()
      .references(() => sites.id, { onDelete: "cascade" }),
    nodeId: text("node_id"),
    kind: text("kind", { enum: ["node_offline", "node_online", "wan_down", "wan_up", "new_client"] }).notNull(),
    detail: text("detail", { mode: "json" }),
    createdAt: integer("created_at").notNull(),
  },
  (t) => [index("events_site_created").on(t.siteId, t.createdAt)],
);

export const knownClients = sqliteTable(
  "known_clients",
  {
    siteId: text("site_id")
      .notNull()
      .references(() => sites.id, { onDelete: "cascade" }),
    mac: text("mac").notNull(),
    firstSeenAt: integer("first_seen_at").notNull(),
  },
  (t) => [primaryKey({ columns: [t.siteId, t.mac] })],
);

/** UnifiedPush endpoints; [owner] is the issuer that registered it, so it can only remove its own. */
export const pushTargets = sqliteTable("push_targets", {
  endpoint: text("endpoint").primaryKey(),
  owner: text("owner").notNull(),
  createdAt: integer("created_at").notNull(),
});
