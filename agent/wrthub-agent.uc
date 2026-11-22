#!/usr/bin/ucode
// wrt-hub agent: pushes this node's snapshot to the hub server and runs the commands it relays.
// Configuration: /etc/config/wrthub (option server, optional ca_file), token in /etc/wrthub/token.
'use strict';

import * as uloop from 'uloop';
import * as ubus from 'ubus';
import { cursor } from 'uci';
import { readfile, writefile, popen, unlink } from 'fs';
import { collect } from 'wrthub.collect';
import { request } from 'wrthub.http';
import * as ops from 'wrthub.ops';

const NORMAL_INTERVAL_MS = 10000;
const FAST_INTERVAL_MS = 1000;
const FAST_LEASE_MS = 60000;
const MAX_BACKOFF_MS = 60000;
const POLL_WAIT_S = 25;

function now_ms() {
	let c = clock();
	return c[0] * 1000 + int(c[1] / 1000000);
}

function log(msg) {
	warn(`wrthub-agent: ${msg}\n`);
}

let uci = cursor(getenv('WRTHUB_UCI_CONFDIR'));
let server = rtrim(uci.get('wrthub', 'main', 'server') ?? '', '/');
let ca_file = uci.get('wrthub', 'main', 'ca_file');
let token = trim(readfile(getenv('WRTHUB_TOKEN_FILE') ?? '/etc/wrthub/token') ?? '');
let tmpdir = getenv('WRTHUB_TMPDIR') ?? '/tmp';
if (server == '' || token == '')
	die('wrthub-agent: set option server in /etc/config/wrthub and the token in /etc/wrthub/token');

uloop.init();
let conn = ubus.connect();
if (!conn)
	die(`wrthub-agent: no ubus: ${ubus.error()}`);

function call_server(method, path, body, headers, timeout, done) {
	request({
		method,
		url: server + path,
		headers: { Authorization: `Bearer ${token}`, ...headers },
		body,
		timeout,
		ca_file,
	}, done);
}

let operations = ops.create(conn, {
	tmpdir,
	reachable: (cb) => request({ url: `${server}/healthz`, timeout: 5000, ca_file }, (status) => cb(status == 200)),
});

// Snapshots ----------------------------------------------------------------------------------------------------
// Declared ahead: strict ucode resolves names where they are used, and these loops call each other.
let send_snapshot, poll;
let fast_until = 0;
let snapshot_backoff = 0;
// The wait before the next snapshot; null while one is being sent.
let snapshot_timer = null;
let snapshot_file = `${tmpdir}/wrthub-snapshot.json`;

function gzip(text) {
	writefile(snapshot_file, text);
	let p = popen(`gzip -c '${snapshot_file}'`, 'r');
	let out = p.read('all');
	p.close();
	unlink(snapshot_file);
	return out;
}

function next_snapshot() {
	let delay = snapshot_backoff > 0 ? snapshot_backoff : (now_ms() < fast_until ? FAST_INTERVAL_MS : NORMAL_INTERVAL_MS);
	snapshot_timer = uloop.timer(delay, send_snapshot);
}

function snapshot_failed(reason) {
	snapshot_backoff = min(max(snapshot_backoff * 2, 1000), MAX_BACKOFF_MS);
	log(`snapshot not delivered (${reason}), retrying in ${snapshot_backoff / 1000}s`);
	next_snapshot();
}

send_snapshot = function() {
	snapshot_timer = null;
	let body;
	try {
		body = gzip(sprintf('%J', collect(conn, now_ms())));
	} catch (e) {
		return snapshot_failed(`collect: ${e.message}`);
	}
	call_server('POST', '/agent/v1/snapshot', body, { 'Content-Type': 'application/json', 'Content-Encoding': 'gzip' }, 15000, (status, text) => {
		if (status != 204)
			return snapshot_failed(status == 0 ? text : `HTTP ${status}`);
		snapshot_backoff = 0;
		next_snapshot();
	});
};

// Commands -----------------------------------------------------------------------------------------------------
let poll_backoff = 0;

function poll_failed(reason) {
	poll_backoff = min(max(poll_backoff * 2, 1000), MAX_BACKOFF_MS);
	log(`command poll failed (${reason}), retrying in ${poll_backoff / 1000}s`);
	uloop.timer(poll_backoff, poll);
}

function report(command, outcome, next) {
	call_server('POST', `/agent/v1/commands/${command.id}/result`, sprintf('%J', outcome), { 'Content-Type': 'application/json' }, 15000, (status) => {
		if (status != 204)
			log(`result of ${command.name} ${command.id} not accepted: HTTP ${status}`);
		next();
	});
}

function run_all(commands, i) {
	if (i >= length(commands))
		return poll();
	let command = commands[i];
	let next = () => run_all(commands, i + 1);
	if (command.name == 'set_fast_mode') {
		fast_until = now_ms() + FAST_LEASE_MS;
		if (snapshot_timer && snapshot_backoff == 0)
			snapshot_timer.set(FAST_INTERVAL_MS);
		return report(command, { status: 'succeeded', result: { until: fast_until } }, next);
	}
	operations.run(command, (outcome) => report(command, outcome, next));
}

poll = function() {
	call_server('GET', `/agent/v1/commands?wait=${POLL_WAIT_S}`, null, {}, (POLL_WAIT_S + 10) * 1000, (status, text) => {
		if (status != 200)
			return poll_failed(status == 0 ? text : `HTTP ${status}`);
		let commands = json(text)?.commands;
		if (type(commands) != 'array')
			return poll_failed('malformed answer');
		poll_backoff = 0;
		run_all(commands, 0);
	});
};

send_snapshot();
poll();
uloop.run();
