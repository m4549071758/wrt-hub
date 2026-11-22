// Asynchronous HTTP(S) requests on uloop through ucode-mod-uclient.
'use strict';

import * as uclient from 'uclient';
import { access } from 'fs';

const SYSTEM_CA_BUNDLE = '/etc/ssl/certs/ca-certificates.crt';

// Sends one request and calls done(status, body) once, with status 0 and a message as body when the connection
// failed or timed out. opts: method, url, headers, body (string), timeout (ms), ca_file (PEM to trust instead of
// the system bundle).
export function request(opts, done) {
	let finished = false;
	let chunks = [];
	let cl;

	function finish(status, body) {
		if (finished)
			return;
		finished = true;
		cl?.disconnect();
		cl?.free();
		done(status, body);
	}

	function drain() {
		let data;
		while (length(data = cl.read()) > 0)
			push(chunks, data);
	}

	cl = uclient.new(opts.url, null, {
		header_done: () => null,
		data_read: drain,
		data_eof: () => {
			drain();
			finish(cl.status()?.status ?? 0, join('', chunks));
		},
		error: (_cb, code) => finish(0, `connection error ${code}`),
	});
	if (!cl) {
		finish(0, 'invalid URL');
		return;
	}

	if (substr(opts.url, 0, 6) == 'https:') {
		let ca = opts.ca_file ?? (access(SYSTEM_CA_BUNDLE) ? SYSTEM_CA_BUNDLE : null);
		if (!ca || !cl.ssl_init({ ca_files: [ ca ], verify: true })) {
			finish(0, 'TLS setup failed: no CA certificates to verify the server with');
			return;
		}
	}

	cl.set_timeout(opts.timeout ?? 30000);
	if (!cl.connect()) {
		finish(0, 'connect failed');
		return;
	}

	let req = { headers: opts.headers ?? {} };
	if (opts.body != null)
		req.post_data = opts.body;
	if (!cl.request(opts.method ?? 'GET', req))
		finish(0, 'request failed');
};
