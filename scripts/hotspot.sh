#!/usr/bin/env bash
# M1 — one-click hotspot from the Mac side. WIP, not yet validated on device.
#
# Prerequisites:
#   brew install blueutil
#   Phone paired with this Mac over Bluetooth (System Settings > Bluetooth)
#   Samsung Modes & Routines rule on the phone:
#     IF   "Bluetooth device connected" = this Mac
#     THEN "Turn on Mobile Hotspot"
#     (optional reverse rule: disconnected -> hotspot off)
#
# Config via env or edit the defaults below.
set -euo pipefail

PHONE_BT_MAC="${PHONE_BT_MAC:-XX:XX:XX:XX:XX:XX}"   # blueutil --paired
HOTSPOT_SSID="${HOTSPOT_SSID:-Galaxy S25}"
WIFI_IF="${WIFI_IF:-en0}"
JOIN_WAIT="${JOIN_WAIT:-5}"                          # seconds for hotspot to come up

case "${1:-}" in
  on)
    blueutil --connect "$PHONE_BT_MAC"
    echo "BT connected — waiting ${JOIN_WAIT}s for hotspot to come up..."
    sleep "$JOIN_WAIT"
    # macOS auto-joins known networks; this forces it if it doesn't.
    networksetup -setairportnetwork "$WIFI_IF" "$HOTSPOT_SSID" || true
    ;;
  off)
    blueutil --disconnect "$PHONE_BT_MAC"
    ;;
  status)
    blueutil --is-connected "$PHONE_BT_MAC"
    networksetup -getairportnetwork "$WIFI_IF"
    ;;
  *)
    echo "usage: $0 on|off|status" >&2
    exit 1
    ;;
esac
