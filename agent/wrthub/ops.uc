// The allowlisted operations. Arguments are checked here again, whatever the server let through.
'use strict';

import * as uloop from 'uloop';
import { readfile, unlink } from 'fs';
import { BLOCK_PREFIX } from 'wrthub.collect';
import * as relay from 'wrthub.relay';

const MAC = /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/;
const HOST = /^[A-Za-z0-9.:-]{1,253}$/;
const PSK = /^[ -~]{8,63}$/;

// Stop confirming this long before rpcd's rollback fires, so a late confirm never races the revert.
const CONFIRM_MARGIN_MS = 5000;
const CONFIRM_INTERVAL_MS = 2000;

function mac_of(value) {
	let mac = type(value) == 'string' ? lc(trim(value)) : null;
	return mac != null && match(mac, MAC) ? mac : null;
}

function host_of(value) {
	return type(value) == 'string' && match(value, HOST) && substr(value, 0, 1) != '-' ? value : null;
}

function failed(message) {
	return { status: 'failed', result: { error: message } };
}

// opts: rollback_s (rpcd rollback timeout), reachable(cb) (calls cb(true) once the server answers), tmpdir.
export function create(conn, opts) {
	let rollback_s = opts.rollback_s ?? 30;
	let tmpdir = opts.tmpdir ?? '/tmp';

	// A fresh rpcd session per write, so changes staged by anything else never get applied along.
	function open_session() {
		let sid = conn.call('session', 'create', { timeout: rollback_s + 60 })?.ubus_rpc_session;
		if (!sid)
			die(`session.create failed: ${conn.error()}`);
		conn.call('session', 'grant', { ubus_rpc_session: sid, scope: 'uci', objects: [ [ '*', 'read' ], [ '*', 'write' ] ] });
		return sid;
	}

	// ucode's ubus call answers data or null; failure shows only in error(), which reading clears.
	function uci(sid, method, args) {
		let res = conn.call('uci', method, { ...args, ubus_rpc_session: sid });
		let code = conn.error(true);
		if (code != null)
			die(`uci.${method} failed with ubus status ${code}`);
		return res;
	}

	// Applies with rpcd's rollback timer and confirms once the server is reachable again; otherwise rpcd reverts.
	function apply_with_rollback(sid, done) {
		uci(sid, 'apply', { rollback: true, timeout: rollback_s });
		let deadline = clock()[0] * 1000 + rollback_s * 1000 - CONFIRM_MARGIN_MS;
		function attempt() {
			opts.reachable((ok) => {
				let confirmed = false;
				if (ok) {
					conn.call('uci', 'confirm', { ubus_rpc_session: sid });
					confirmed = conn.error(true) == null;
				}
				if (confirmed) {
					conn.call('session', 'destroy', { ubus_rpc_session: sid });
					done({ status: 'succeeded', result: { outcome: 'applied' } });
				} else if (clock()[0] * 1000 + CONFIRM_INTERVAL_MS < deadline) {
					uloop.timer(CONFIRM_INTERVAL_MS, attempt);
				} else {
					done({ status: 'succeeded', result: { outcome: 'rolled_back' } });
				}
			});
		}
		uloop.timer(CONFIRM_INTERVAL_MS, attempt);
	}

	function block(args, done) {
		let mac = mac_of(args?.mac);
		if (!mac)
			return done(failed('invalid MAC address'));
		let name = BLOCK_PREFIX + replace(mac, /:/g, '');
		let sid = open_session();
		if (uci(sid, 'get', { config: 'firewall', section: name }) != null) {
			conn.call('session', 'destroy', { ubus_rpc_session: sid });
			return done({ status: 'succeeded', result: { outcome: 'unchanged' } });
		}
		uci(sid, 'add', {
			config: 'firewall', type: 'rule', name,
			values: { name: `wrt-hub block ${mac}`, src: '*', dest: '*', src_mac: mac, target: 'REJECT' },
		});
		apply_with_rollback(sid, done);
	}

	function unblock(args, done) {
		let mac = mac_of(args?.mac);
		if (!mac)
			return done(failed('invalid MAC address'));
		let name = BLOCK_PREFIX + replace(mac, /:/g, '');
		let sid = open_session();
		if (conn.call('uci', 'get', { ubus_rpc_session: sid, config: 'firewall', section: name }) == null) {
			conn.call('session', 'destroy', { ubus_rpc_session: sid });
			return done({ status: 'succeeded', result: { outcome: 'unchanged' } });
		}
		uci(sid, 'delete', { config: 'firewall', section: name });
		apply_with_rollback(sid, done);
	}

	// Without rollback, as in the app: whoever is on this Wi-Fi drops off at once, which would trigger it.
	function set_wifi(args, done) {
		let ssid = args?.ssid, key = args?.key;
		if (type(ssid) != 'string' || length(ssid) < 1 || length(ssid) > 32)
			return done(failed('the SSID must be 1 to 32 bytes'));
		if (type(key) != 'string' || !match(key, PSK))
			return done(failed('the password must be 8 to 63 printable ASCII characters'));
		let sid = open_session();
		let sections = uci(sid, 'get', { config: 'wireless' })?.values ?? {};
		let aps = filter(keys(sections), (n) => sections[n]['.type'] == 'wifi-iface' && sections[n].mode == 'ap');
		let changed = filter(aps, (n) => sections[n].ssid != ssid || sections[n].key != key);
		for (let n in changed)
			uci(sid, 'set', { config: 'wireless', section: n, values: { ssid, key } });
		if (length(changed) > 0)
			uci(sid, 'apply', { rollback: false });
		conn.call('session', 'destroy', { ubus_rpc_session: sid });
		done({ status: 'succeeded', result: { accessPoints: length(aps), changed: length(changed) } });
	}

	// Runs [argv] without a shell interpreting it (the arguments are passed as positional parameters).
	function exec(argv, done) {
		let out = `${tmpdir}/wrthub-exec-${clock()[1]}.txt`;
		uloop.process('/bin/sh', [ '-c', 'out="$1"; shift; exec "$@" >"$out" 2>&1', 'sh', out, ...argv ], {}, (code) => {
			let output = readfile(out) ?? '';
			unlink(out);
			done({ status: 'succeeded', result: { exitCode: code, stdout: output, stderr: '' } });
		});
	}

	function ping(args, done) {
		let host = host_of(args?.host);
		if (!host)
			return done(failed('invalid host'));
		exec([ '/bin/ping', '-c', '4', '-W', '2', host ], done);
	}

	function traceroute(args, done) {
		let host = host_of(args?.host);
		if (!host)
			return done(failed('invalid host'));
		exec([ '/bin/traceroute', '-q', '1', '-w', '2', '-m', '20', host ], done);
	}

	// Reported first, then done after a pause: the result has to reach the server before the node goes down.
	function reboot(_args, done) {
		done({ status: 'succeeded', result: {} });
		uloop.timer(3000, () => conn.call('system', 'reboot', {}));
	}

	let relay_call = relay.create(conn, { tmpdir });

	// The relay answers with the ubus status in the result: a refused or failed call is still a delivered answer.
	function ubus_call(args, done) {
		relay_call(args, (reply) => done({ status: 'succeeded', result: reply }));
	}

	let handlers = { block_client: block, unblock_client: unblock, set_wifi, ping, traceroute, reboot, ubus: ubus_call };

	return {
		names: keys(handlers),
		run: function(command, done) {
			let handler = handlers[command.name];
			if (!handler)
				return done(failed(`not allowed: ${command.name}`));
			try {
				handler(command.args, done);
			} catch (e) {
				done(failed(e.message));
			}
		},
	};
};
