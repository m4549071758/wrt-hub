"""Runs the agent in an OpenWrt container against a fake node and a real hub server, acting as the app.

Needs docker and a built server (cd server && npm ci --ignore-scripts && npm run build).
"""

import json
import os
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "openwrt/rootfs:x86-64-25.12.6"
FIXTURE = "mesh-site/router.json"
# Fields the fake node serves from the fixture; the rest (neighbours, uci) come from the container itself.
FROM_FIXTURE = ["schemaVersion", "board", "info", "interfaces", "devices", "wireless", "dhcpLeases", "hostHints", "usteerClients"]
BLOCK_MAC = "ce:11:22:00:00:77"

WIRELESS_CONFIG = """
config wifi-device 'radio0'
	option type 'mac80211'

config wifi-iface 'ap0'
	option device 'radio0'
	option mode 'ap'
	option ssid 'old-ssid'
	option key 'old-password'

config wifi-iface 'mesh0'
	option device 'radio0'
	option mode 'mesh'
	option mesh_id 'mesh'
	option key 'mesh-password'
"""

# openwrt/rootfs has no system config; the relayed write test edits its host name.
SYSTEM_CONFIG = """
config system
	option hostname 'OpenWrt'
"""


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(predicate, timeout, interval=0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError("timed out")


@unittest.skipUnless(shutil.which("docker"), "docker is not available")
class AgentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server = ROOT / "server"
        if not (server / "dist" / "main.js").exists():
            raise unittest.SkipTest("server is not built")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.port = free_port()
        env = {**os.environ, "WRTHUB_DB_PATH": str(tmp / "hub.db"), "WRTHUB_LISTEN_HOST": "127.0.0.1", "WRTHUB_LISTEN_PORT": str(cls.port)}

        def admin(*args):
            out = subprocess.run(["node", "dist/cli.js", *args], cwd=server, env=env, check=True, capture_output=True, text=True).stdout
            return json.loads(out[: out.rindex("}") + 1])

        cls.site = admin("site", "add", "Cabin")["id"]
        node = admin("node", "add", cls.site, "Router")
        cls.node = node["id"]
        code = admin("pair", "test")["code"]
        cls.server = subprocess.Popen(["node", "dist/main.js"], cwd=server, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.base = f"http://127.0.0.1:{cls.port}"
        wait_for(lambda: cls._get_status("/healthz") == 200, 15)
        cls.app_token = cls._request("POST", "/app/v1/pair", {"code": code})["token"]

        (tmp / "token").write_text(node["token"])
        (tmp / "config").mkdir()
        (tmp / "config" / "wrthub").write_text(f"config agent 'main'\n\toption server '{cls.base}'\n")
        (tmp / "wireless").write_text(WIRELESS_CONFIG)
        (tmp / "system").write_text(SYSTEM_CONFIG)
        cls.container = subprocess.run(
            [
                "docker", "run", "-d", "--rm", "--network", "host",
                "-v", f"{ROOT}:/src:ro", "-v", f"{tmp}:/work:ro",
                "-e", "WRTHUB_UCI_CONFDIR=/work/config", "-e", "WRTHUB_TOKEN_FILE=/work/token",
                IMAGE, "/bin/sh", "-c",
                f"cp /work/wireless /etc/config/wireless && cp /work/system /etc/config/system && /src/agent/test/container.sh {FIXTURE} "
                "ucode -L /src/agent /src/agent/wrthub-agent.uc",
            ],
            check=True, capture_output=True, text=True,
        ).stdout.strip()
        # The container starts ubusd, rpcd and the fake node before the agent; its first snapshot means all are up.
        sites = f"/app/v1/sites/{cls.site}/snapshots"
        wait_for(lambda: cls._request("GET", sites, token=cls.app_token)["nodes"][0]["snapshot"], 60)

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["docker", "rm", "-f", cls.container], capture_output=True)
        cls.server.terminate()
        cls.server.wait(10)
        cls.tmp.cleanup()

    @classmethod
    def _get_status(cls, path):
        try:
            with urllib.request.urlopen(cls.base + path, timeout=5) as r:
                return r.status
        except OSError:
            return 0

    @classmethod
    def _request(cls, method, path, body=None, token=None):
        headers = {"content-type": "application/json"} if body is not None else {}
        if token:
            headers["authorization"] = f"Bearer {token}"
        data = json.dumps(body).encode() if body is not None else None
        with urllib.request.urlopen(urllib.request.Request(cls.base + path, data, headers, method=method), timeout=40) as r:
            return json.loads(r.read() or b"null")

    def app(self, method, path, body=None):
        return self._request(method, path, body, self.app_token)

    def snapshot(self):
        return self.app("GET", f"/app/v1/sites/{self.site}/snapshots")["nodes"][0]

    def command(self, name, args=None, wait=25):
        issued = self.app("POST", f"/app/v1/nodes/{self.node}/commands", {"name": name, "args": args or {}})
        deadline = time.time() + 60
        while time.time() < deadline:
            view = self.app("GET", f"/app/v1/commands/{issued['id']}?wait={wait}")
            if view["status"] in ("succeeded", "failed", "expired"):
                return view
        raise AssertionError(f"{name} did not finish")

    def test_snapshot_matches_the_fixture_and_hides_wifi_keys(self):
        node = wait_for(lambda: (s := self.snapshot())["snapshot"] and s, 30)
        self.assertEqual(node["state"], "fresh")
        expected = json.loads((ROOT / "fixtures" / FIXTURE).read_text())
        got = node["snapshot"]
        for field in FROM_FIXTURE:
            self.assertEqual(got.get(field), expected.get(field), field)
        self.assertIn("neighbors", got)
        self.assertNotIn("must-not-leak", json.dumps(got))
        self.assertNotIn("old-password", json.dumps(got))

    def test_ping_runs_on_the_node(self):
        view = self.command("ping", {"host": "127.0.0.1"})
        self.assertEqual(view["status"], "succeeded")
        self.assertEqual(view["result"]["exitCode"], 0)
        self.assertIn("127.0.0.1", view["result"]["stdout"])

    def test_block_is_confirmed_and_shows_up_in_snapshots_then_unblock(self):
        view = self.command("block_client", {"mac": BLOCK_MAC.upper()})
        self.assertEqual(view["result"], {"outcome": "applied"})
        wait_for(lambda: BLOCK_MAC in (self.snapshot()["snapshot"] or {}).get("blockedClients", []), 30)
        self.assertEqual(self.command("block_client", {"mac": BLOCK_MAC})["result"], {"outcome": "unchanged"})
        self.assertEqual(self.command("unblock_client", {"mac": BLOCK_MAC})["result"], {"outcome": "applied"})
        wait_for(lambda: BLOCK_MAC not in (self.snapshot()["snapshot"] or {}).get("blockedClients", []), 30)

    def test_set_wifi_changes_access_points_only(self):
        view = self.command("set_wifi", {"ssid": "new-ssid", "key": "new-password"})
        self.assertEqual(view["result"], {"accessPoints": 1, "changed": 1})
        wireless = subprocess.run(["docker", "exec", self.container, "uci", "show", "wireless"], capture_output=True, text=True).stdout
        self.assertIn("wireless.ap0.ssid='new-ssid'", wireless)
        self.assertIn("wireless.mesh0.key='mesh-password'", wireless)
        self.assertEqual(self.command("set_wifi", {"ssid": "new-ssid", "key": "new-password"})["result"]["changed"], 0)

    def test_fast_mode_speeds_up_snapshots(self):
        self.assertEqual(self.command("set_fast_mode")["status"], "succeeded")
        first = wait_for(lambda: self.snapshot()["receivedAt"], 30)
        second = wait_for(lambda: (r := self.snapshot()["receivedAt"]) != first and r, 10, 0.2)
        self.assertLess(second - first, 5000)

    def test_a_change_is_rolled_back_when_the_server_is_unreachable(self):
        script = (
            "import * as ubus from 'ubus'; import * as uloop from 'uloop'; import * as ops from 'wrthub.ops';"
            "uloop.init(); let o = ops.create(ubus.connect(), { rollback_s: 10, reachable: (cb) => cb(false) });"
            "o.run({ name: 'block_client', args: { mac: 'ce:11:22:00:00:88' } }, (r) => { print(sprintf('%J\\n', r)); uloop.end(); });"
            "uloop.run();"
        )
        out = subprocess.run(
            ["docker", "exec", self.container, "ucode", "-L", "/src/agent", "-e", script], capture_output=True, text=True, timeout=60
        ).stdout
        self.assertEqual(json.loads(out)["result"], {"outcome": "rolled_back"})
        firewall = wait_for(
            lambda: (f := subprocess.run(["docker", "exec", self.container, "uci", "show", "firewall"], capture_output=True, text=True).stdout)
            and "ce:11:22:00:00:88" not in f and f,
            20,
        )
        self.assertNotIn("wrthub_block_ce1122000088", firewall)

    def test_relay_allowlist_agrees_with_the_server(self):
        script = (
            "import { readfile } from 'fs'; import { refusal } from 'wrthub.relay';"
            "for (let c in json(readfile('/src/fixtures/relay-cases.json')))"
            "  print(sprintf('%J\\n', refusal(c.object, c.method, c.args) == null));"
        )
        out = subprocess.run(["docker", "exec", self.container, "ucode", "-L", "/src/agent", "-e", script], capture_output=True, text=True, timeout=30)
        cases = json.loads((ROOT / "fixtures" / "relay-cases.json").read_text())
        verdicts = [json.loads(line) for line in out.stdout.splitlines()]
        self.assertEqual(len(verdicts), len(cases), out.stderr)
        for case, allowed in zip(cases, verdicts):
            self.assertEqual(allowed, case["allowed"], case)

    def relay(self, obj, method, args=None):
        view = self.command("ubus", {"object": obj, "method": method, "args": args or {}})
        self.assertEqual(view["status"], "succeeded", view)
        return view["result"]

    def test_relayed_write_is_staged_applied_and_confirmed_like_direct_mode(self):
        values = self.relay("uci", "get", {"config": "system"})["data"]["values"]
        section = next(name for name, s in values.items() if s[".type"] == "system")
        pending = self.relay("uci", "changes")
        self.assertEqual(pending["code"], 0)
        self.assertFalse(any((pending["data"] or {}).get("changes", {}).values()))
        self.assertEqual(self.relay("uci", "set", {"config": "system", "section": section, "values": {"hostname": "relayed"}})["code"], 0)
        self.assertEqual(self.relay("uci", "apply", {"rollback": True, "timeout": 30})["code"], 0)
        self.assertEqual(self.relay("system", "info")["code"], 0)
        self.assertEqual(self.relay("uci", "confirm")["code"], 0)
        shown = subprocess.run(["docker", "exec", self.container, "uci", "get", f"system.{section}.hostname"], capture_output=True, text=True).stdout
        self.assertEqual(shown.strip(), "relayed")

    def test_relayed_exec_returns_output_and_refusals_never_reach_the_node(self):
        dmesg = self.relay("file", "exec", {"command": "/bin/dmesg", "params": ["-r"]})
        self.assertEqual(dmesg["code"], 0)
        self.assertEqual(set(dmesg["data"]), {"code", "stdout", "stderr"})
        self.assertIn(self.relay("file", "read", {"path": "/etc/crontabs/root"})["code"], (0, 4))
        with self.assertRaises(urllib.error.HTTPError) as refused:
            self.app("POST", f"/app/v1/nodes/{self.node}/commands", {"name": "ubus", "args": {"object": "file", "method": "read", "args": {"path": "/etc/shadow"}}})
        self.assertEqual(refused.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
