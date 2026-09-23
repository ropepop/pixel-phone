#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="${ROOT}/scripts/android/deploy_orchestrator_apk.sh"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
sed -n '/^repair_phone_automation_permissions() {/,/^remote_sha256_file() {/p' "${SOURCE}" |
  sed '$d' > "${TEST_DIR}/functions.sh"
source "${TEST_DIR}/functions.sh"

PKG=lv.jolkins.pixelorchestrator
PROFILE=standard
APK_INSTALLED_THIS_RUN=1
COMPONENT=ticket_screen
ACTION=redeploy_component
accessibility_component="${PKG}/${PKG}.app.phoneautomation.PhoneAutomationAccessibilityService"
notification_component="${PKG}/${PKG}.app.phoneautomation.PhoneAutomationNotificationListenerService"
printf '%s\n' 'other.app/OtherService' > "${TEST_DIR}/services"
printf '%s\n' 'other.app/Listener' > "${TEST_DIR}/listeners"
printf '0\n' > "${TEST_DIR}/enabled"

pixel_transport_selected() { printf 'ssh\n'; }
pixel_transport_single_quote() { printf "'%s'" "$1"; }
sleep() { :; }
pixel_transport_root_shell() {
  case "$1" in
    'settings get secure enabled_accessibility_services; settings get secure accessibility_enabled; settings get secure enabled_notification_listeners')
      cat "${TEST_DIR}/services" "${TEST_DIR}/enabled" "${TEST_DIR}/listeners" ;;
    'settings get secure enabled_accessibility_services') cat "${TEST_DIR}/services" ;;
    'settings get secure enabled_notification_listeners') cat "${TEST_DIR}/listeners" ;;
    'settings get secure accessibility_enabled') cat "${TEST_DIR}/enabled" ;;
    'settings put secure enabled_accessibility_services '*)
      printf '%s\n' "$1" | sed "s/^settings put secure enabled_accessibility_services '//;s/'$//" > "${TEST_DIR}/services" ;;
    'settings put secure enabled_notification_listeners '*)
      printf '%s\n' "$1" | sed "s/^settings put secure enabled_notification_listeners '//;s/'$//" > "${TEST_DIR}/listeners" ;;
    'settings put secure accessibility_enabled 1') printf '1\n' > "${TEST_DIR}/enabled" ;;
    'cmd appops set '*) : ;;
    "dumpsys activity services ${accessibility_component}")
      if [[ -e "${TEST_DIR}/bound" ]]; then
        printf 'app=ProcessRecord{fixture}\nrequested=true received=true hasBound=true doRebind=false\n'
      else printf 'app=null\nrequested=true received=false hasBound=false\n'; fi ;;
    *) echo "Unexpected command: $1" >&2; return 1 ;;
  esac
}

should_repair_phone_automation_permissions
repair_phone_automation_permissions
[[ "$(cat "${TEST_DIR}/services")" == "other.app/OtherService:${accessibility_component}" ]]
[[ "$(cat "${TEST_DIR}/listeners")" == "other.app/Listener" ]]
! phone_automation_permissions_ready
touch "${TEST_DIR}/bound"
phone_automation_permissions_ready
PROFILE=fast
APK_INSTALLED_THIS_RUN=0
! should_repair_phone_automation_permissions
rm "${TEST_DIR}/bound"
should_repair_phone_automation_permissions

sed -n '/^if \[\[ "${ACTION}:${COMPONENT}" == "redeploy_component:ticket_screen" \]\] \&\&/,/^fi$/p' "${SOURCE}" > "${TEST_DIR}/gate.sh"
[[ -s "${TEST_DIR}/gate.sh" ]]
if (source "${TEST_DIR}/gate.sh") 2>/dev/null; then
  echo 'FAIL: Ticket deployment accepted an unbound input service' >&2
  exit 1
fi
touch "${TEST_DIR}/bound"
source "${TEST_DIR}/gate.sh"
ACTION=health
COMPONENT=''
PROFILE=standard
! should_repair_phone_automation_permissions
echo 'PASS: SSH repairs only owned service entries and Ticket deploy requires an enabled, bound input service'
