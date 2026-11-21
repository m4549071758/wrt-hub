import json
import tempfile
import unittest
from pathlib import Path

import build_fixtures as bf

RAW = {
    "system-board.json": {
        "hostname": "secret-router",
        "model": "Vendor X1",
        "release": {"version": "25.12.6", "distribution": "OpenWrt"},
    },
    "system-info.json": {"uptime": 10},
    "network-interface.json": {
        "interface": [
            {
                "interface": "lan",
                "dns-search": ["isp-secret.example"],
                "data": {"passthru": "0a666c657473"},
                "ipv4-address": [{"address": "192.168.77.1", "mask": 24}],
                "ipv6-prefix": [{"address": "2001:db8:77:900::", "mask": 56}],
            }
        ]
    },
    "network-device.json": {
        "wl1-mesh0": {"macaddr": "ce:11:22:33:44:01"},
        "lo": {"macaddr": "00:00:00:00:00:00"},
    },
    "iwinfo-devices.json": {"devices": ["wl1-mesh0"]},
    "iwinfo-info-wl1-mesh0.json": {"mode": "Mesh Point", "ssid": "secret-mesh", "bssid": "CE:11:22:33:44:01"},
    "iwinfo-assoc-wl1-mesh0.json": {"results": [{"mac": "CE:11:22:33:44:02", "signal": -60}]},
    "leases.json": {
        "dhcp_leases": [{"macaddr": "ce:11:22:33:44:03", "ipaddr": "192.168.77.23", "hostname": "Alice-Phone"}],
        "dhcp6_leases": [{"duid": "000100012e", "ip6addrs": ["2001:db8:77:901::836/128"]}],
    },
    "hosthints.json": {"ce:11:22:33:44:03": {"ipaddrs": ["192.168.77.23"], "name": "Alice-Phone"}},
    "ip-neigh.txt": "192.168.77.23 dev br-lan lladdr ce:11:22:33:44:03 REACHABLE \n192.168.77.9 dev br-lan  FAILED \n",
    "usteer-get_clients.json": {
        "ce:11:22:33:44:03": {"192.168.77.2#hostapd.wl1-ap0": {"connected": True, "signal": -50}}
    },
}

ORIGINALS = [
    "ce:11:22:33:44:01",
    "ce:11:22:33:44:02",
    "ce:11:22:33:44:03",
    "192.168.77",
    "2001:db8:77",
    "secret-router",
    "secret-mesh",
    "isp-secret",
    "0a666c657473",
    "alice-phone",
    "000100012e",
]


class BuildFixturesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.raw = root / "raw"
        host = self.raw / "r1"
        host.mkdir(parents=True)
        for name, data in RAW.items():
            (host / name).write_text(data if isinstance(data, str) else json.dumps(data))
        self.out = root / "out"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def build(self) -> tuple[str, dict]:
        [path] = bf.build(self.raw, self.out, ["site/router=r1"])
        text = path.read_text()
        return text, json.loads(text)

    def test_writes_snapshot_shape(self) -> None:
        _, snap = self.build()
        self.assertEqual(1, snap["schemaVersion"])
        self.assertEqual(bf.COLLECTED_AT, snap["collectedAt"])
        self.assertEqual(["wl1-mesh0"], [w["ifname"] for w in snap["wireless"]])
        self.assertNotIn("hostapdClients", snap["wireless"][0])
        self.assertEqual([], snap["blockedClients"])
        self.assertNotIn("nlbw", snap)

    def test_no_original_value_survives(self) -> None:
        text, _ = self.build()
        for original in ORIGINALS:
            self.assertNotIn(original, text.lower())

    def test_same_mac_maps_to_same_value_regardless_of_case(self) -> None:
        _, snap = self.build()
        device_mac = snap["devices"]["wl1-mesh0"]["macaddr"]
        bssid = snap["wireless"][0]["info"]["bssid"]
        self.assertEqual(device_mac.upper(), bssid)
        self.assertTrue(bssid.isupper())

    def test_ipv4_keeps_subnet_grouping_inside_usteer_keys(self) -> None:
        _, snap = self.build()
        self.assertEqual("10.99.0.1", snap["interfaces"]["interface"][0]["ipv4-address"][0]["address"])
        self.assertEqual("10.99.0.23", snap["dhcpLeases"]["dhcp_leases"][0]["ipaddr"])
        client = next(iter(snap["usteerClients"].values()))
        self.assertEqual(["10.99.0.2#hostapd.wl1-ap0"], list(client))

    def test_ipv6_prefix_suffix_is_kept(self) -> None:
        _, snap = self.build()
        self.assertEqual(["fd00:db8::2/128"], snap["dhcpLeases"]["dhcp6_leases"][0]["ip6addrs"])

    def test_neighbor_table_is_kept_as_text_and_anonymized(self) -> None:
        _, snap = self.build()
        self.assertEqual("10.99.0.23 dev br-lan lladdr 02:aa:bb:00:00:03 REACHABLE \n10.99.0.9 dev br-lan  FAILED \n", snap["neighbors"])

    def test_search_domains_and_dhcp_passthru_are_replaced(self) -> None:
        _, snap = self.build()
        lan = snap["interfaces"]["interface"][0]
        self.assertEqual(["domain-1"], lan["dns-search"])
        self.assertEqual("", lan["data"]["passthru"])

    def test_zero_mac_and_default_names_are_kept(self) -> None:
        _, snap = self.build()
        self.assertEqual("00:00:00:00:00:00", snap["devices"]["lo"]["macaddr"])
        self.assertEqual("OpenWrt", snap["board"]["release"]["distribution"])

    def test_host_hint_and_lease_share_the_same_stand_in(self) -> None:
        _, snap = self.build()
        hint = next(iter(snap["hostHints"].values()))["name"]
        self.assertEqual("host-2", hint)
        self.assertEqual(hint, snap["dhcpLeases"]["dhcp_leases"][0]["hostname"])
        self.assertEqual("host-1", snap["board"]["hostname"])
        self.assertEqual("ssid-1", snap["wireless"][0]["info"]["ssid"])

    def test_leak_detection(self) -> None:
        anonymizer = bf.Anonymizer()
        anonymizer.text("aa:bb:cc:dd:ee:ff")
        anonymizer.name("host", "Mac")
        self.assertEqual(["aa:bb:cc:dd:ee:ff"], anonymizer.leaks('"AA:BB:CC:DD:EE:FF"'))
        self.assertEqual([], anonymizer.leaks('{"mac": "02:00:00:00:00:01"}'))
        self.assertEqual(["Mac"], anonymizer.leaks('{"name": "Mac"}'))

    def test_rejects_malformed_spec(self) -> None:
        with self.assertRaises(ValueError):
            bf.build(self.raw, self.out, ["router=r1"])


if __name__ == "__main__":
    unittest.main()
