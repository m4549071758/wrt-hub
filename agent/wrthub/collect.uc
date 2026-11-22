// Builds the node snapshot: the same JSON the app's direct mode collects (see fixtures/), from local ubus.
'use strict';

import { popen, access } from 'fs';

export const SCHEMA_VERSION = 1;
export const BLOCK_PREFIX = 'wrthub_block_';

// ubus status codes for an object or method that is not there; those calls are optional.
const UBUS_METHOD_NOT_FOUND = 3;
const UBUS_NOT_FOUND = 4;
const UBUS_PERMISSION_DENIED = 6;

function command_output(argv) {
	let quoted = map(argv, (a) => `'${replace(a, "'", "'\\''")}'`);
	let p = popen(join(' ', quoted) + ' 2>/dev/null', 'r');
	if (!p)
		return null;
	let out = p.read('all');
	return p.close() == 0 ? out : null;
}

// Only the interface names: the rest of getWirelessDevices is the wireless config, Wi-Fi keys included.
function wireless_ifnames(devices) {
	let names = [];
	for (let radio in devices)
		for (let iface in devices[radio]?.interfaces ?? [])
			if (type(iface?.ifname) == 'string')
				push(names, iface.ifname);
	return names;
}

export function blocked_macs(firewall) {
	let macs = [];
	for (let name, section in firewall?.values ?? {}) {
		if (index(name, BLOCK_PREFIX) != 0)
			continue;
		let mac = section?.src_mac;
		for (let m in (type(mac) == 'array' ? mac : (mac == null ? [] : [ mac ])))
			push(macs, lc(m));
	}
	return sort(macs);
};

// conn: a ubus connection. now: epoch milliseconds.
export function collect(conn, now) {
	let denied = [];

	function optional(obj, method, args, label) {
		let res = conn.call(obj, method, args ?? {});
		if (res != null)
			return res;
		let code = conn.error(true);
		if (code == UBUS_PERMISSION_DENIED)
			push(denied, label ?? `${obj}.${method}`);
		else if (code != UBUS_METHOD_NOT_FOUND && code != UBUS_NOT_FOUND && code != null)
			die(`${obj}.${method} failed: ${conn.error()}`);
		return null;
	}

	function required(obj, method) {
		let res = conn.call(obj, method, {});
		if (res == null)
			die(`${obj}.${method} returned no data: ${conn.error()}`);
		return res;
	}

	let devices = optional('luci-rpc', 'getWirelessDevices');
	let ifnames = devices != null ? wireless_ifnames(devices) : (optional('iwinfo', 'devices')?.devices ?? []);

	let wireless = map(ifnames, (ifname) => {
		let info = conn.call('iwinfo', 'info', { device: ifname });
		if (info == null)
			die(`iwinfo.info returned no data for ${ifname}`);
		let entry = {
			ifname,
			info,
			assoclist: conn.call('iwinfo', 'assoclist', { device: ifname }) ?? { results: [] },
		};
		// The agent runs as root, so unlike the app it may read hostapd; only AP interfaces have the object.
		let clients = info.mode == 'Master' ? conn.call(`hostapd.${ifname}`, 'get_clients', {}) : null;
		if (clients != null)
			entry.hostapdClients = clients;
		return entry;
	});

	let snapshot = {
		schemaVersion: SCHEMA_VERSION,
		collectedAt: now,
		board: required('system', 'board'),
		info: required('system', 'info'),
		interfaces: required('network.interface', 'dump'),
		devices: required('network.device', 'status'),
		wireless,
	};

	let optionals = {
		dhcpLeases: optional('luci-rpc', 'getDHCPLeases'),
		hostHints: optional('luci-rpc', 'getHostHints'),
		usteerClients: length(ifnames) > 0 ? optional('usteer', 'get_clients') : null,
		dhcpConfig: optional('uci', 'get', { config: 'dhcp' }, 'uci.get dhcp'),
	};
	for (let key, value in optionals)
		if (value != null)
			snapshot[key] = value;

	if (access('/usr/sbin/nlbw')) {
		let nlbw = command_output([ '/usr/sbin/nlbw', '-c', 'json', '-g', 'mac' ]);
		let parsed = nlbw != null ? json(nlbw) : null;
		if (type(parsed) == 'object')
			snapshot.nlbw = parsed;
	}

	let neighbors = command_output([ '/sbin/ip', '-4', 'neigh', 'show' ]);
	if (neighbors != null)
		snapshot.neighbors = neighbors;

	snapshot.blockedClients = blocked_macs(optional('uci', 'get', { config: 'firewall' }));
	snapshot.deniedCalls = denied;
	return snapshot;
};
