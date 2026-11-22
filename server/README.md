# wrt-hub server

The relay for hub mode: agents on the routers push snapshots and long-poll for commands, the app reads the
snapshots (also as a server-sent event stream) and issues commands. It also sends push notifications and, if
configured, serves Starlink telemetry from InfluxDB. Snapshots are kept in memory only; SQLite holds sites, nodes,
tokens, the command audit log, events and push endpoints.

## Run

```sh
docker build -t wrthub-server server
docker run -d --name wrthub -p 8080:8080 -v wrthub-data:/data wrthub-server
```

Put it behind a reverse proxy that terminates TLS. The app and the agents only speak HTTPS to it. The event stream
needs the proxy not to buffer (`/app/v1/sites/{id}/stream`; the server sends `X-Accel-Buffering: no` for nginx).

## Set up sites, nodes and phones

```sh
docker exec wrthub node dist/cli.js site add Home
docker exec wrthub node dist/cli.js node add <site-id> Router     # prints the agent token once
docker exec -e WRTHUB_PUBLIC_URL=https://hub.example.org wrthub node dist/cli.js pair "My phone"
```

`pair` prints a one-time code (valid 10 minutes unless given another number of minutes) and, with
`WRTHUB_PUBLIC_URL`, a `wrthub://pair?…` link to show as a QR code. Tokens and codes are stored only as hashes;
`node rotate`, `node revoke` and `device revoke` take effect at once. Run `node dist/cli.js` for all commands.

## Configuration

| Variable | Default | |
|---|---|---|
| `WRTHUB_LISTEN_HOST` / `WRTHUB_LISTEN_PORT` | `0.0.0.0` / `8080` | |
| `WRTHUB_DB_PATH` | `/data/wrthub.db` | |
| `WRTHUB_STALE_SECONDS` / `WRTHUB_OFFLINE_SECONDS` | `30` / `60` | Age after which a node's snapshot is stale, and after which the node counts as offline (and a notification goes out). |
| `WRTHUB_OIDC_ISSUER`, `WRTHUB_OIDC_AUDIENCE`, `WRTHUB_OIDC_CLIENT_ID` | unset | Set all three to accept OIDC access tokens (JWT) besides paired phones. The client ID is only handed to the app. |
| `WRTHUB_INFLUX_URL`, `WRTHUB_INFLUX_TOKEN`, `WRTHUB_INFLUX_ORG`, `WRTHUB_INFLUX_BUCKET` | unset | Set all four to serve Starlink telemetry: [starlink_exporter](https://github.com/joshuasing/starlink_exporter) metrics stored in InfluxDB as fields of the measurement `starlink`. Use a read-only token. |

## Commands

The server relays only these, with their arguments checked: `block_client`, `unblock_client` (`mac`), `set_wifi`
(`ssid`, `key`), `reboot`, `ping`, `traceroute` (`host`), `set_fast_mode`, and `ubus` (`object`, `method`, `args`):
one ubus call of the app's LuCI-replacement screens, within the allowlist in `src/ubus-allowlist.ts` (listed objects
and methods, five uci configs, two files, fixed command lines; never stopping the services or removing the packages
the node or the agent needs). Every command is kept with who issued it (`device:<id>` or `oidc:<sub>`). A command no
agent fetches within 2 minutes, or answers within 5 minutes of fetching, expires.

## Develop

```sh
cd server
npm ci --ignore-scripts
npm test
npm run dev                 # WRTHUB_DB_PATH=./dev.db npm run dev for a local database
npm run db:generate         # after changing src/db/schema.ts
```
