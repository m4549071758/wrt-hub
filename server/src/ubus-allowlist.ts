/**
 * The ubus calls relayed for the app's LuCI-replacement screens: the server's copy of the agent's allowlist
 * (agent/wrthub/relay.uc). Both are checked against the same cases in fixtures/relay-cases.json.
 */

export const UCI_CONFIGS = ["dhcp", "firewall", "wireless", "network", "system"];
const UCI_METHODS = ["get", "add", "set", "delete", "revert", "changes", "apply", "confirm"];
const UCI_SESSION_METHODS = ["changes", "apply", "confirm"];

const PLAIN: Record<string, string[]> = {
  system: ["info", "board"],
  log: ["read"],
  luci: ["getConntrackList", "getTimezones", "getLEDs", "getMountPoints"],
  rc: ["list", "init"],
  iwinfo: ["info", "freqlist", "txpowerlist"],
};

const FILE_READ = ["/etc/crontabs/root", "/proc/sys/net/netfilter/nf_conntrack_count", "/proc/sys/net/netfilter/nf_conntrack_max"];
const FILE_WRITE = ["/etc/crontabs/root"];

const SERVICE = /^[A-Za-z0-9._-]{1,64}$/;
const RC_ACTIONS = ["start", "stop", "restart", "enable", "disable"];
const ESSENTIAL_SERVICES = ["rpcd", "uhttpd", "network", "firewall", "dnsmasq", "wrthub-agent"];

const PACKAGE = /^[a-z0-9][a-z0-9._+-]{0,127}$/;
const ESSENTIAL_PACKAGES = [
  "rpcd", "uhttpd", "luci-base", "luci-app-package-manager", "dropbear", "base-files", "busybox", "kernel", "libc",
  "netifd", "procd", "ubus", "ubusd", "uci", "ucode", "uclient-fetch", "ca-bundle", "ca-certificates",
];
const ESSENTIAL_PREFIXES = ["rpcd-mod-", "apk-", "uhttpd-mod-", "ucode-mod-", "libustream-"];

const HOST = /^[A-Za-z0-9.:-]{1,253}$/;

function isHost(value: unknown): boolean {
  return typeof value === "string" && HOST.test(value) && !value.startsWith("-");
}

function same(a: unknown[], b: string[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i]);
}

function isPackage(value: unknown): value is string {
  return typeof value === "string" && PACKAGE.test(value);
}

function essentialPackage(name: string): boolean {
  return ESSENTIAL_PACKAGES.includes(name) || ESSENTIAL_PREFIXES.some((p) => name.startsWith(p));
}

const EXEC: Record<string, (p: unknown[]) => boolean> = {
  "/bin/dmesg": (p) => same(p, ["-r"]),
  "/sbin/ip": (p) =>
    (p[0] === "-4" || p[0] === "-6") && (same(p.slice(1), ["-j", "route", "show", "table", "all"]) || same(p.slice(1), ["route", "show", "table", "all"])),
  "/etc/init.d/cron": (p) => same(p, ["reload"]),
  "/bin/ping": (p) => p.length === 5 && same(p.slice(0, 4), p[1] === "1" ? ["-c", "1", "-W", "2"] : ["-c", "4", "-W", "2"]) && isHost(p[4]),
  "/bin/traceroute": (p) => p.length === 7 && same(p.slice(0, 6), ["-q", "1", "-w", "2", "-m", "20"]) && isHost(p[6]),
  "/usr/libexec/package-manager-call": (p) =>
    (p.length === 1 && ["list-installed", "list-available", "update"].includes(String(p[0]))) ||
    (p.length === 2 && p[0] === "install" && isPackage(p[1])) ||
    (p.length === 2 && p[0] === "remove" && isPackage(p[1]) && !essentialPackage(p[1])),
};

function inList(value: unknown, list: string[]): boolean {
  return typeof value === "string" && list.includes(value);
}

/** Why the call is refused, or null when it may be relayed. */
export function ubusRefusal(object: string, method: string, args: Record<string, unknown>): string | null {
  if (object === "uci") {
    if (!UCI_METHODS.includes(method)) return `uci.${method} is not allowed`;
    if (UCI_SESSION_METHODS.includes(method)) {
      return args["config"] !== undefined && !inList(args["config"], UCI_CONFIGS) ? `config ${String(args["config"])} is not allowed` : null;
    }
    return inList(args["config"], UCI_CONFIGS) ? null : `config ${String(args["config"])} is not allowed`;
  }
  if (object === "file") {
    if (method === "read") return inList(args["path"], FILE_READ) ? null : `reading ${String(args["path"])} is not allowed`;
    if (method === "write") {
      return inList(args["path"], FILE_WRITE) && typeof args["data"] === "string" ? null : `writing ${String(args["path"])} is not allowed`;
    }
    if (method === "exec") {
      const command = args["command"];
      const check = typeof command === "string" && Object.hasOwn(EXEC, command) ? EXEC[command] : undefined;
      const params = args["params"] ?? [];
      return check && Array.isArray(params) && check(params) ? null : `running ${String(command)} with these arguments is not allowed`;
    }
    return `file.${method} is not allowed`;
  }
  if (!(PLAIN[object] ?? []).includes(method)) return `${object}.${method} is not allowed`;
  if (object === "rc" && method === "init") {
    const name = args["name"];
    const action = args["action"];
    if (typeof name !== "string" || !SERVICE.test(name) || !inList(action, RC_ACTIONS)) return "invalid service or action";
    if (ESSENTIAL_SERVICES.includes(name) && (action === "stop" || action === "disable")) return `${String(action)} of ${name} would cut the node off`;
  }
  return null;
}
