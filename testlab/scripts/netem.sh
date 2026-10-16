#!/usr/bin/env bash
# Adds packet loss, delay and jitter on the lab PC's network interface (Linux, needs root),
# to imitate weak 2.4 GHz Wi-Fi between camera and TV. On Windows use the "clumsy" tool instead.
# Usage: sudo ./netem.sh start eth0 [loss%] [delay_ms] [jitter_ms]   |   sudo ./netem.sh stop eth0
set -euo pipefail
action=${1:-}; dev=${2:-}
case "$action" in
  start)
    loss=${3:-2}; delay=${4:-40}; jitter=${5:-20}
    tc qdisc replace dev "$dev" root netem loss "${loss}%" delay "${delay}ms" "${jitter}ms" distribution normal
    tc qdisc show dev "$dev" ;;
  stop)
    tc qdisc del dev "$dev" root 2>/dev/null || true
    echo "netem removed from $dev" ;;
  *)
    echo "Usage: sudo $0 start <interface> [loss%] [delay_ms] [jitter_ms] | sudo $0 stop <interface>" >&2
    exit 2 ;;
esac
