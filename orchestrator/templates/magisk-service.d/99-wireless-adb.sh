#!/system/bin/sh
# One-shot boot setup; adbd remains owned by Android init.
set -eu

# Install both guards before enabling TCP. Never expose unauthenticated ADB.
[ "$(getprop ro.adb.secure)" = "1" ]
guard_adb() {
for ipt in iptables ip6tables; do
  chain=PIXEL_ADB_GUARD
  "$ipt" -w 5 -N "$chain" 2>/dev/null || "$ipt" -w 5 -S "$chain" >/dev/null
  "$ipt" -w 5 -C "$chain" -i tailscale0 -j ACCEPT 2>/dev/null ||
    "$ipt" -w 5 -I "$chain" 1 -i tailscale0 -j ACCEPT
  "$ipt" -w 5 -C "$chain" -i wlan0 -j ACCEPT 2>/dev/null ||
    "$ipt" -w 5 -I "$chain" 1 -i wlan0 -j ACCEPT
  "$ipt" -w 5 -C "$chain" -j DROP 2>/dev/null ||
    "$ipt" -w 5 -A "$chain" -j DROP
  "$ipt" -w 5 -C INPUT -p tcp --dport 5555 -j "$chain" 2>/dev/null ||
    "$ipt" -w 5 -I INPUT 1 -p tcp --dport 5555 -j "$chain"
done
}
guard_adb

attempt=0
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  [ "$attempt" -lt 90 ] || exit 1
  attempt=$((attempt + 1))
  sleep 2
done
guard_adb

# Preserve remembered host approvals; authentication itself stays mandatory.
if [ "$(settings get global adb_allowed_connection_time)" != "0" ]; then
  settings put global adb_allowed_connection_time 0
fi

# A second, dynamically allocated wireless-debug listener is unnecessary.
if [ "$(settings get global adb_wifi_enabled)" != "0" ]; then
  settings put global adb_wifi_enabled 0
fi
if [ "$(getprop persist.adb.tls_server.enable)" != "0" ]; then
  setprop persist.adb.tls_server.enable 0
fi
if [ "$(getprop service.adb.tcp.port)" != "5555" ] ||
   [ "$(getprop init.svc.adbd)" != "running" ]; then
  setprop service.adb.tcp.port 5555
  setprop ctl.restart adbd
fi
