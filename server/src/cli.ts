import { Admin } from "./admin.js";
import { openDb } from "./db/index.js";

const USAGE = `usage: wrthub-admin <command>
  site add <name>                    create a site
  site list
  node add <site-id> <label>         register a node; prints its agent token once
  node rotate <node-id>              replace a node's token; prints the new one once
  node revoke <node-id>
  node list
  pair <device-name> [minutes]       one-time pairing code for a phone (default 10 minutes)
  device list
  device revoke <device-id>
Environment: WRTHUB_DB_PATH (default /data/wrthub.db), WRTHUB_PUBLIC_URL (printed in the pairing link).`;

function fail(message: string): never {
  process.stderr.write(`${message}\n`);
  process.exit(2);
}

const [group, action, ...rest] = process.argv.slice(2);
const admin = new Admin(openDb(process.env["WRTHUB_DB_PATH"] ?? "/data/wrthub.db"));
const out = (value: unknown) => process.stdout.write(`${typeof value === "string" ? value : JSON.stringify(value, null, 2)}\n`);

switch (`${group ?? ""} ${action ?? ""}`.trim()) {
  case "site add":
    out(admin.addSite(rest.join(" ") || fail(USAGE)));
    break;
  case "site list":
    out(admin.listSites());
    break;
  case "node add": {
    const [siteId, ...label] = rest;
    if (!siteId || label.length === 0) fail(USAGE);
    out(admin.addNode(siteId, label.join(" ")));
    break;
  }
  case "node rotate":
    out(admin.rotateNodeToken(rest[0] ?? fail(USAGE)));
    break;
  case "node revoke":
    admin.revokeNode(rest[0] ?? fail(USAGE));
    break;
  case "node list":
    out(admin.listNodes());
    break;
  case "device list":
    out(admin.listDevices());
    break;
  case "device revoke":
    admin.revokeDevice(rest[0] ?? fail(USAGE));
    break;
  default:
    if (group === "pair" && action) {
      const minutes = Number(rest[0] ?? "10");
      if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) fail("minutes must be 1 to 1440");
      const { code, expiresAt } = admin.createPairingCode(action, minutes * 60_000);
      out({ code, expiresAt: new Date(expiresAt).toISOString() });
      const publicUrl = process.env["WRTHUB_PUBLIC_URL"];
      // The app reads this link from a QR code; any QR generator can render it.
      if (publicUrl) out(`wrthub://pair?server=${encodeURIComponent(publicUrl)}&code=${encodeURIComponent(code)}`);
    } else {
      fail(USAGE);
    }
}
