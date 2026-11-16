#!/bin/sh
# Optional Linux container-only real packet loss/retransmission.
# Never modifies the host's network adapter or qdisc.
set -eu
: "${RTT_MS:=800}"
: "${JITTER_MS:=200}"
: "${LOSS_PCT:=3}"
: "${RATE_KBIT:=32}"
: "${UPSTREAM_HOST:=host.docker.internal}"
: "${UPSTREAM_PORT:=5000}"
for val in "$RTT_MS" "$JITTER_MS" "$LOSS_PCT" "$RATE_KBIT"; do
    case "$val" in *[!0-9.]*|'') echo "Invalid network profile number" >&2; exit 2;; esac
done
tc qdisc add dev eth0 root netem delay "${RTT_MS}ms" "${JITTER_MS}ms" distribution normal loss "${LOSS_PCT}%" rate "${RATE_KBIT}kbit"
exec socat TCP-LISTEN:15000,reuseaddr,fork TCP:"$UPSTREAM_HOST":"$UPSTREAM_PORT"
