#!/usr/bin/env python3
"""Build anonymized snapshot fixtures from raw ubus captures.

Usage:
  build_fixtures.py RAW_DIR OUT_DIR SITE/NODE=HOST [SITE/NODE=HOST ...]

RAW_DIR/HOST/ holds one JSON file per ubus call (see assemble()).
Every MAC, IPv4, IPv6, SSID, host name and DUID is replaced with a
deterministic stand-in shared by all nodes, so cross-node references
(mesh peers, usteer remote keys) stay consistent. The build fails when
an original value is still present in any output file.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

COLLECTED_AT = 1795300000000
MAC_RE = re.compile(r"(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])")
IPV4_RE = re.compile(r"(?<![0-9.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![0-9.])")
HEXCOLON_RE = re.compile(r"(?<![0-9A-Za-z:])[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:]*(?![0-9A-Za-z:])")
BOUNDARY = r"(?<![0-9A-Za-z]){}(?![0-9A-Za-z])"
ZERO_MAC = "00:00:00:00:00:00"
NAME_KINDS = {"ssid": "ssid", "hostname": "host", "duid": "duid"}
NAME_LIST_KINDS = {"dns-search": "domain"}
REDACTED_KEYS = {"passthru"}
KEEP_NAMES = {"OpenWrt"}
OPTIONAL_FILES = (
    ("dhcpLeases", "leases.json"),
    ("hostHints", "hosthints.json"),
    ("usteerClients", "usteer-get_clients.json"),
    ("nlbw", "nlbw.json"),
)


def is_ipv6(token: str) -> bool:
    return "::" in token or token.count(":") == 7


class Anonymizer:
    def __init__(self) -> None:
        self.macs: dict[str, str] = {}
        self.subnets: dict[tuple[str, str, str], int] = {}
        self.ipv6: dict[str, str] = {}
        self.names: dict[tuple[str, str], str] = {}
        self.originals_ci: set[str] = set()
        self.originals_cs: set[str] = set()

    def mac(self, original: str) -> str:
        key = original.lower()
        if key == ZERO_MAC:
            return original
        self.originals_ci.add(key)
        if key not in self.macs:
            n = len(self.macs) + 1
            self.macs[key] = "02:aa:bb:00:%02x:%02x" % (n // 256, n % 256)
        stand_in = self.macs[key]
        return stand_in.upper() if original.isupper() else stand_in

    def ipv4(self, match: re.Match[str]) -> str:
        a, b, c, d = match.groups()
        if a in ("0", "127", "255"):
            return match.group(0)
        self.originals_ci.add(match.group(0))
        n = self.subnets.setdefault((a, b, c), len(self.subnets))
        return f"10.99.{n}.{d}"

    def ipv6_token(self, match: re.Match[str]) -> str:
        token = match.group(0)
        if MAC_RE.fullmatch(token) or not is_ipv6(token) or token.strip(":") == "":
            return token
        key = token.lower()
        self.originals_ci.add(key)
        if key not in self.ipv6:
            self.ipv6[key] = f"fd00:db8::{len(self.ipv6) + 1:x}"
        return self.ipv6[key]

    def name(self, kind: str, value: str) -> str:
        if value == "" or value in KEEP_NAMES:
            return value
        self.originals_cs.add(value)
        key = (kind, value)
        if key not in self.names:
            count = sum(1 for existing in self.names if existing[0] == kind) + 1
            self.names[key] = f"{kind}-{count}"
        return self.names[key]

    def text(self, value: str) -> str:
        value = HEXCOLON_RE.sub(self.ipv6_token, value)
        value = MAC_RE.sub(lambda m: self.mac(m.group(0)), value)
        return IPV4_RE.sub(self.ipv4, value)

    def walk(self, node: Any) -> Any:
        if isinstance(node, dict):
            is_host_hint = "ipaddrs" in node or "ip6addrs" in node
            out: dict[str, Any] = {}
            for key, value in node.items():
                kind = NAME_KINDS.get(key) or ("host" if key == "name" and is_host_hint else None)
                list_kind = NAME_LIST_KINDS.get(key)
                if key in REDACTED_KEYS:
                    out[key] = ""
                elif kind is not None and isinstance(value, str):
                    out[self.text(key)] = self.name(kind, value)
                elif list_kind is not None and isinstance(value, list):
                    out[key] = [self.name(list_kind, v) if isinstance(v, str) else self.walk(v) for v in value]
                else:
                    out[self.text(key)] = self.walk(value)
            return out
        if isinstance(node, list):
            return [self.walk(item) for item in node]
        if isinstance(node, str):
            return self.text(node)
        return node

    def leaks(self, text: str) -> list[str]:
        lowered = text.lower()
        found = [v for v in sorted(self.originals_ci) if re.search(BOUNDARY.format(re.escape(v)), lowered)]
        found += [v for v in sorted(self.originals_cs) if re.search(BOUNDARY.format(re.escape(v)), text)]
        return found


def load(path: Path) -> Any:
    return json.loads(path.read_text())


def load_optional(path: Path) -> Any | None:
    return load(path) if path.exists() else None


def assemble(host_dir: Path) -> dict[str, Any]:
    devices = load_optional(host_dir / "iwinfo-devices.json") or {}
    wireless = []
    for ifname in devices.get("devices", []):
        entry: dict[str, Any] = {
            "ifname": ifname,
            "info": load(host_dir / f"iwinfo-info-{ifname}.json"),
            "assoclist": load(host_dir / f"iwinfo-assoc-{ifname}.json"),
        }
        hostapd = load_optional(host_dir / f"hostapd-{ifname}.json")
        if hostapd is not None:
            entry["hostapdClients"] = hostapd
        wireless.append(entry)
    snapshot: dict[str, Any] = {
        "schemaVersion": 1,
        "collectedAt": COLLECTED_AT,
        "board": load(host_dir / "system-board.json"),
        "info": load(host_dir / "system-info.json"),
        "interfaces": load(host_dir / "network-interface.json"),
        "devices": load(host_dir / "network-device.json"),
        "wireless": wireless,
    }
    for key, filename in OPTIONAL_FILES:
        value = load_optional(host_dir / filename)
        if value is not None:
            snapshot[key] = value
    snapshot["blockedClients"] = []
    return snapshot


def build(raw_dir: Path, out_dir: Path, specs: list[str]) -> list[Path]:
    anonymizer = Anonymizer()
    outputs = []
    for spec in specs:
        target, sep, host = spec.partition("=")
        if not sep or target.count("/") != 1 or not host:
            raise ValueError(f"invalid spec {spec!r}; expected SITE/NODE=HOST")
        snapshot = anonymizer.walk(assemble(raw_dir / host))
        path = out_dir / f"{target}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(snapshot, indent=2, ensure_ascii=False) + "\n")
        outputs.append(path)
    leaks = [(path, value) for path in outputs for value in anonymizer.leaks(path.read_text())]
    if leaks:
        for path in outputs:
            path.unlink()
        details = ", ".join(f"{path.name}: {len([1 for p, _ in leaks if p == path])} value(s)" for path in outputs)
        raise RuntimeError(f"original values survived anonymization; outputs removed ({details})")
    return outputs


def main(argv: list[str]) -> int:
    if len(argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    for path in build(Path(argv[1]), Path(argv[2]), argv[3:]):
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
