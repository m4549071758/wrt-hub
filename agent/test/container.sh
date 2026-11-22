#!/bin/sh
# Runs inside openwrt/rootfs: starts ubusd and rpcd (for the real uci and session objects) and a fake node answering
# from the fixture given as $1, then runs the remaining arguments. rpcd's iwinfo and luci plugins are removed first,
# as the fake node publishes those objects itself.
set -e
fixture="$1"
shift
rm -f /usr/lib/rpcd/iwinfo.so /usr/lib/rpcd/luci.so /usr/share/rpcd/ucode/luci
mkdir -p /var/lock /var/run /tmp/run
ubusd &
until ubus list >/dev/null 2>&1; do sleep 1; done
rpcd &
until ubus list uci >/dev/null 2>&1; do sleep 1; done
ucode /src/agent/test/fake-node.uc "/src/fixtures/$fixture" &
until ubus list system >/dev/null 2>&1; do sleep 1; done
exec "$@"
