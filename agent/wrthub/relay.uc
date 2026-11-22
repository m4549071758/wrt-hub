// Relays the ubus calls of the app's LuCI-replacement screens (the same calls it makes in direct mode), restricted
// the way LuCI's rpcd ACL restricts them: listed objects and methods, five uci configs, two files, a few command
// lines. uci calls run in one rpcd session the agent keeps, so a write staged, applied and confirmed by the app over
// several relayed calls stays in one change set with one rollback timer.
'use strict';

import * as uloop from 'uloop';
import { readfile, writefile, unlink } from 'fs';

const UBUS_INVALID_ARGUMENT = 2;
const UBUS_NOT_FOUND = 4;
const UBUS_PERMISSION_DENIED = 6;

export const UCI_CONFIGS = [ 'dhcp', 'firewall', 'wireless', 'network', 'system' ];
const UCI_METHODS = [ 'get', 'add', 'set', 'delete', 'revert', 'changes', 'apply', 'confirm' ];
// Methods that act on the whole session rather than on one config.
const UCI_SESSION_METHODS = [ 'changes', 'apply', 'confirm' ];

const PLAIN = {
	system: [ 'info', 'board' ],
	log: [ 'read' ],
	luci: [ 'getConntrackList', 'getTimezones', 'getLEDs', 'getMountPoints' ],
	rc: [ 'list', 'init' ],
	iwinfo: [ 'info', 'freqlist', 'txpowerlist' ],
};

const FILE_READ = [ '/etc/crontabs/root', '/proc/sys/net/netfilter/nf_conntrack_count', '/proc/sys/net/netfilter/nf_conntrack_max' ];
const FILE_WRITE = [ '/etc/crontabs/root' ];

const SERVICE = /^[A-Za-z0-9._-]{1,64}$/;
const RC_ACTIONS = [ 'start', 'stop', 'restart', 'enable', 'disable' ];
// Stopping these cuts the node off its users or this agent off the server.
const ESSENTIAL_SERVICES = [ 'rpcd', 'uhttpd', 'network', 'firewall', 'dnsmasq', 'wrthub-agent' ];

const PACKAGE = /^[a-z0-9][a-z0-9._+-]{0,127}$/;
const ESSENTIAL_PACKAGES = [
	'rpcd', 'uhttpd', 'luci-base', 'luci-app-package-manager', 'dropbear', 'base-files', 'busybox', 'kernel', 'libc',
	'netifd', 'procd', 'ubus', 'ubusd', 'uci', 'ucode', 'uclient-fetch', 'ca-bundle', 'ca-certificates',
];
const ESSENTIAL_PREFIXES = [ 'rpcd-mod-', 'apk-', 'uhttpd-mod-', 'ucode-mod-', 'libustream-' ];

const HOST = /^[A-Za-z0-9.:-]{1,253}$/;

function is_host(h) {
	return type(h) == 'string' && match(h, HOST) && substr(h, 0, 1) != '-';
}

function same(a, b) {
	return length(a) == length(b) && length(filter(a, (v, i) => v === b[i])) == length(a);
}

function essential_package(name) {
	return name in ESSENTIAL_PACKAGES || length(filter(ESSENTIAL_PREFIXES, (p) => index(name, p) == 0)) > 0;
}

// The command lines the app runs, by program: LuCI grants the same ones.
const EXEC = {
	'/bin/dmesg': (p) => same(p, [ '-r' ]),
	'/sbin/ip': (p) => (p[0] == '-4' || p[0] == '-6') &&
		(same(slice(p, 1), [ '-j', 'route', 'show', 'table', 'all' ]) || same(slice(p, 1), [ 'route', 'show', 'table', 'all' ])),
	'/etc/init.d/cron': (p) => same(p, [ 'reload' ]),
	'/bin/ping': (p) => length(p) == 5 && same(slice(p, 0, 4), p[1] == '1' ? [ '-c', '1', '-W', '2' ] : [ '-c', '4', '-W', '2' ]) && is_host(p[4]),
	'/bin/traceroute': (p) => length(p) == 7 && same(slice(p, 0, 6), [ '-q', '1', '-w', '2', '-m', '20' ]) && is_host(p[6]),
	'/usr/libexec/package-manager-call': (p) =>
		(length(p) == 1 && p[0] in [ 'list-installed', 'list-available', 'update' ]) ||
		(length(p) == 2 && p[0] == 'install' && type(p[1]) == 'string' && match(p[1], PACKAGE)) ||
		(length(p) == 2 && p[0] == 'remove' && type(p[1]) == 'string' && match(p[1], PACKAGE) && !essential_package(p[1])),
};

// Why a call is refused, or null when it may run.
export function refusal(object, method, args) {
	if (type(args) != 'object')
		return 'args must be an object';
	if (object == 'uci') {
		if (!(method in UCI_METHODS))
			return `uci.${method} is not allowed`;
		if (method in UCI_SESSION_METHODS)
			return args.config != null && !(args.config in UCI_CONFIGS) ? `config ${args.config} is not allowed` : null;
		return args.config in UCI_CONFIGS ? null : `config ${args.config} is not allowed`;
	}
	if (object == 'file') {
		if (method == 'read')
			return args.path in FILE_READ ? null : `reading ${args.path} is not allowed`;
		if (method == 'write')
			return args.path in FILE_WRITE && type(args.data) == 'string' ? null : `writing ${args.path} is not allowed`;
		if (method == 'exec') {
			let check = EXEC[args.command];
			let params = args.params ?? [];
			return check && type(params) == 'array' && check(params) ? null : `running ${args.command} with these arguments is not allowed`;
		}
		return `file.${method} is not allowed`;
	}
	if (!(method in (PLAIN[object] ?? [])))
		return `${object}.${method} is not allowed`;
	if (object == 'rc' && method == 'init') {
		if (type(args.name) != 'string' || !match(args.name, SERVICE) || !(args.action in RC_ACTIONS))
			return 'invalid service or action';
		if (args.name in ESSENTIAL_SERVICES && (args.action == 'stop' || args.action == 'disable'))
			return `${args.action} of ${args.name} would cut the node off`;
	}
	return null;
};

export function create(conn, opts) {
	let tmpdir = opts?.tmpdir ?? '/tmp';
	let sid = null;

	function session() {
		if (sid)
			return sid;
		sid = conn.call('session', 'create', { timeout: 3600 })?.ubus_rpc_session;
		if (!sid)
			die('session.create failed');
		conn.call('session', 'grant', { ubus_rpc_session: sid, scope: 'uci', objects: map(UCI_CONFIGS, (c) => [ c, 'read' ]) });
		conn.call('session', 'grant', { ubus_rpc_session: sid, scope: 'uci', objects: map(UCI_CONFIGS, (c) => [ c, 'write' ]) });
		return sid;
	}

	function answer(data) {
		let code = conn.error(true);
		return { code: code ?? 0, data: code == null ? data : null };
	}

	function call_uci(method, args) {
		let reply = answer(conn.call('uci', method, { ...args, ubus_rpc_session: session() }));
		// An expired session: start a new one and try once more (staged changes of the old one are gone anyway).
		if (reply.code == UBUS_PERMISSION_DENIED || (reply.code == UBUS_NOT_FOUND && conn.call('session', 'access', { ubus_rpc_session: sid }) == null)) {
			sid = null;
			reply = answer(conn.call('uci', method, { ...args, ubus_rpc_session: session() }));
		}
		return reply;
	}

	function exec(args, done) {
		let base = `${tmpdir}/wrthub-relay-${clock()[1]}`;
		let argv = [ '-c', 'o="$1"; e="$2"; shift 2; exec "$@" >"$o" 2>"$e"', 'sh', `${base}.out`, `${base}.err`, args.command, ...(args.params ?? []) ];
		uloop.process('/bin/sh', argv, {}, (code) => {
			let stdout = readfile(`${base}.out`) ?? '', stderr = readfile(`${base}.err`) ?? '';
			unlink(`${base}.out`);
			unlink(`${base}.err`);
			done({ code: 0, data: { code, stdout, stderr } });
		});
	}

	// done({ code, data }): code is the ubus status (0 for success), as the app's direct mode would get it.
	return function(args, done) {
		let object = args?.object, method = args?.method, call_args = args?.args ?? {};
		let reason = refusal(object, method, call_args);
		if (reason)
			return done({ code: UBUS_PERMISSION_DENIED, data: null, error: reason });
		if (object == 'uci')
			return done(call_uci(method, call_args));
		if (object == 'file') {
			if (method == 'exec')
				return exec(call_args, done);
			if (method == 'read') {
				let data = readfile(call_args.path);
				return done(data == null ? { code: UBUS_NOT_FOUND, data: null } : { code: 0, data: { data } });
			}
			return done(writefile(call_args.path, call_args.data) == null ? { code: UBUS_INVALID_ARGUMENT, data: null } : { code: 0, data: null });
		}
		done(answer(conn.call(object, method, call_args)));
	};
};
