// Publishes the ubus objects a node has (system, network.*, iwinfo, luci-rpc, usteer) answering from a fixture
// snapshot, so the agent can be run against them in an OpenWrt container. uci and file come from the real rpcd.
'use strict';

import * as ubus from 'ubus';
import * as uloop from 'uloop';
import { readfile } from 'fs';

let snapshot = json(readfile(ARGV[0]));
let byIfname = {};
for (let w in snapshot.wireless)
	byIfname[w.ifname] = w;

function constant(value) {
	return { call: () => value };
}

function per_device(field) {
	return {
		args: { device: '' },
		call: (req) => byIfname[req.args.device]?.[field] ?? ubus.STATUS_NOT_FOUND,
	};
}

uloop.init();
let conn = ubus.connect();

let objects = {
	system: { board: constant(snapshot.board), info: constant(snapshot.info) },
	'network.interface': { dump: constant(snapshot.interfaces) },
	'network.device': { status: constant(snapshot.devices) },
	iwinfo: {
		devices: constant({ devices: map(snapshot.wireless, (w) => w.ifname) }),
		info: per_device('info'),
		assoclist: per_device('assoclist'),
	},
	'luci-rpc': {
		// Shaped like the real answer, including a key the agent must not forward.
		getWirelessDevices: constant({ radio0: { interfaces: map(snapshot.wireless, (w) => ({ ifname: w.ifname, config: { key: 'must-not-leak' } })) } }),
	},
};
if (snapshot.dhcpLeases)
	objects['luci-rpc'].getDHCPLeases = constant(snapshot.dhcpLeases);
if (snapshot.hostHints)
	objects['luci-rpc'].getHostHints = constant(snapshot.hostHints);
if (snapshot.usteerClients)
	objects.usteer = { get_clients: constant(snapshot.usteerClients) };
for (let w in snapshot.wireless)
	if (w.hostapdClients)
		objects[`hostapd.${w.ifname}`] = { get_clients: constant(w.hostapdClients) };

for (let name, methods in objects)
	if (!conn.publish(name, methods))
		die(`publish ${name}: ${ubus.error()}`);

uloop.run();
