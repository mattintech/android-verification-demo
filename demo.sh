#!/usr/bin/env bash
# Simulate Android developer verification with the Acme demo apps.
#
#   ./demo.sh setup  [-s SERIAL]   install Acme Apps, force: Timesheets=pass, Field Agent=reject
#   ./demo.sh reset  [-s SERIAL]   uninstall the two test apps and re-apply the forced results
#   ./demo.sh clean  [-s SERIAL]   clear the forced results and uninstall everything
#   ./demo.sh status [-s SERIAL]   show the device's verification policy and what's installed
#
# Use an emulator with Google Play (Android 16 QPR2 / Android 17 or later).
# On retail phones outside the enforcement regions, the forced results are ignored.
set -euo pipefail
cd "$(dirname "$0")"

CMD="${1:-}"; shift || true
ADB=(adb)
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then ADB=(adb -s "$2"); fi

STORE=com.mattintech.acme.store
TS=com.mattintech.acme.timesheets
FA=com.mattintech.acme.fieldagent
APKS=apks
[ -f out/acme-store.apk ] && APKS=out

force_results() {
  # policy 3 = closed; results queue for the next 5 install attempts of each app
  "${ADB[@]}" shell pm set-developer-verification-result "$TS" 3 1 1 1 1 1   # 1 = pass
  "${ADB[@]}" shell pm set-developer-verification-result "$FA" 3 2 2 2 2 2   # 2 = reject
  echo "Forced: Timesheets = pass, Field Agent = reject (next 5 installs each)"
}

uninstall_targets() {
  "${ADB[@]}" shell pm uninstall "$TS" >/dev/null 2>&1 || true
  "${ADB[@]}" shell pm uninstall "$FA" >/dev/null 2>&1 || true
}

case "$CMD" in
  setup)
    sdk=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
    "${ADB[@]}" shell pm help 2>/dev/null | grep -q set-developer-verification-result \
      || { echo "This device doesn't have the verification test command (needs Android 16 QPR2+). SDK=$sdk"; exit 1; }
    # adb installs are exempt from verification, like an admin sideloading the store
    "${ADB[@]}" install -r "$APKS/acme-store.apk"
    "${ADB[@]}" shell appops set "$STORE" REQUEST_INSTALL_PACKAGES allow
    uninstall_targets
    force_results
    "${ADB[@]}" push "$APKS/acme-fieldagent.apk" /sdcard/Download/acme-fieldagent.apk >/dev/null 2>&1
    "${ADB[@]}" shell am start -n "$STORE/.MainActivity" >/dev/null
    echo "Acme Apps is open. Tap Install on each app. Field Agent is also in Downloads to try from the Files app."
    ;;
  reset)
    uninstall_targets
    force_results
    ;;
  clean)
    "${ADB[@]}" shell pm clear-developer-verification-result "$TS" || true
    "${ADB[@]}" shell pm clear-developer-verification-result "$FA" || true
    uninstall_targets
    "${ADB[@]}" shell pm uninstall "$STORE" >/dev/null 2>&1 || true
    "${ADB[@]}" shell rm -f /sdcard/Download/acme-fieldagent.apk
    echo "Cleared forced results and removed the demo apps."
    ;;
  status)
    echo "Device policy: $("${ADB[@]}" shell pm get-developer-verification-policy 2>&1 | tr -d '\r')"
    echo "Verifier: $("${ADB[@]}" shell pm get-developer-verification-service-provider 2>&1 | tr -d '\r')"
    "${ADB[@]}" shell pm list packages com.mattintech.acme
    ;;
  *)
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
