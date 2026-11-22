# wrt-hub agent

Runs on each OpenWrt node of a hub-mode site. Every 10 seconds (every second while the app shows live rates) it
sends the node's snapshot to the [server](../server/README.md), gzipped, and it long-polls the server for commands,
which it runs only if they are on its own allowlist with valid arguments. Writes go through rpcd's `uci apply` with a
rollback timer and are confirmed only once the server answers again, so a change that cuts the node off is undone.

The LuCI-replacement screens of the app make the same ubus calls as in direct mode, relayed by the agent (command
`ubus`) within an allowlist shaped like LuCI's rpcd ACL (`wrthub/relay.uc`; the server holds the same list and
`fixtures/relay-cases.json` keeps both in step). uci calls share one rpcd session, so the app stages, applies with
rollback and confirms a change over several relayed calls, and a change that cuts the agent off is never confirmed.

It needs nothing beyond OpenWrt 25.12's `ucode` with the `ubus`, `uci`, `fs`, `uloop` and `uclient` modules
(`ucode-mod-uclient`) and busybox `gzip`. It takes about 20 KB on the router.

## Install

On the server, register the node and keep the printed token:

```sh
docker exec wrthub node dist/cli.js node add <site-id> "Living AP"
```

On the node (as root), with the files of this directory copied over, for example with `scp -O -r agent root@router:/tmp/`:

```sh
mkdir -p /usr/share/wrthub/wrthub /etc/wrthub
cp /tmp/agent/wrthub-agent.uc /usr/share/wrthub/
cp /tmp/agent/wrthub/*.uc /usr/share/wrthub/wrthub/
cp /tmp/agent/wrthub-agent.init /etc/init.d/wrthub-agent
cp /tmp/agent/wrthub.config.example /etc/config/wrthub
vi /etc/config/wrthub                                  # set option server
printf '%s' '<token>' > /etc/wrthub/token && chmod 600 /etc/wrthub/token
printf '%s\n' /etc/config/wrthub /etc/wrthub/ /usr/share/wrthub/ /etc/init.d/wrthub-agent >> /etc/sysupgrade.conf
/etc/init.d/wrthub-agent enable
/etc/init.d/wrthub-agent start
logread -e wrthub-agent
```

The server's certificate is checked against `/etc/ssl/certs/ca-certificates.crt` (package `ca-bundle`); for a
private CA, set `option ca_file` to its PEM file.

## Test

`python3 -m unittest agent/test/test_agent.py` (from the repository root, with docker and a built server) runs the
agent in an `openwrt/rootfs` container. A fake node (`test/fake-node.uc`) answers the ubus calls from a fixture; rpcd
provides the real `uci` and `session` objects, so rollback and confirm are the real ones.
