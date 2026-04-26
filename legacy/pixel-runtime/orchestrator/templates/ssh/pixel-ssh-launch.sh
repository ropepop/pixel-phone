#!/system/bin/sh
set -eu

PIXEL_SSH_ROOT="${PIXEL_SSH_ROOT:-/data/local/pixel-stack/ssh}"
CONF_FILE="${PIXEL_SSH_ROOT}/conf/dropbear.env"
PASSWD_FILE="${PIXEL_SSH_ROOT}/etc/passwd"
DROPBEAR_BIN="${PIXEL_SSH_ROOT}/bin/dropbear"
DROPBEARKEY_BIN="${PIXEL_SSH_ROOT}/bin/dropbearkey"
AUTH_KEYS_SRC="${PIXEL_SSH_ROOT}/home/root/.ssh/authorized_keys"
AUTH_KEYS_DST_DIR="/debug_ramdisk/pixel-ssh-auth"
AUTH_KEYS_DST="${AUTH_KEYS_DST_DIR}/authorized_keys"
RUN_DIR="${PIXEL_SSH_ROOT}/run"
LOG_DIR="${PIXEL_SSH_ROOT}/logs"
HOST_KEY_DIR="${PIXEL_SSH_ROOT}/etc/dropbear"
ED25519_KEY="${HOST_KEY_DIR}/dropbear_ed25519_host_key"
RSA_KEY="${HOST_KEY_DIR}/dropbear_rsa_host_key"
PID_FILE="${RUN_DIR}/dropbear.pid"
AUTH_MODE_FILE="${RUN_DIR}/auth-mode"

if [ -r "${CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${CONF_FILE}"
  set +a
fi

: "${SSH_PORT:=2222}"
: "${SSH_BIND_ADDRESS:=0.0.0.0}"
: "${SSH_PASSWORD_AUTH:=1}"
: "${SSH_ALLOW_KEY_AUTH:=1}"
: "${SSH_KEEPALIVE_SEC:=30}"
: "${SSH_IDLE_TIMEOUT_SEC:=0}"
: "${SSH_RECV_WINDOW_BYTES:=262144}"

mkdir -p "${RUN_DIR}" "${LOG_DIR}" "${HOST_KEY_DIR}" "${AUTH_KEYS_DST_DIR}"
chmod 0700 "${AUTH_KEYS_DST_DIR}" >/dev/null 2>&1 || true

if [ -f "${PASSWD_FILE}" ]; then
  if ! grep -F "${PASSWD_FILE} /system/etc/passwd " /proc/mounts >/dev/null 2>&1; then
    umount /system/etc/passwd >/dev/null 2>&1 || true
    mount -o bind "${PASSWD_FILE}" /system/etc/passwd
  fi
fi

if [ ! -f "${ED25519_KEY}" ]; then
  "${DROPBEARKEY_BIN}" -t ed25519 -f "${ED25519_KEY}" >/dev/null 2>&1
fi
if [ ! -f "${RSA_KEY}" ]; then
  "${DROPBEARKEY_BIN}" -t rsa -f "${RSA_KEY}" >/dev/null 2>&1
fi
chmod 0600 "${ED25519_KEY}" "${RSA_KEY}" >/dev/null 2>&1 || true

AUTH_KEYS_ARG=""
if [ "${SSH_ALLOW_KEY_AUTH}" = "1" ]; then
  if [ -f "${AUTH_KEYS_SRC}" ]; then
    cp "${AUTH_KEYS_SRC}" "${AUTH_KEYS_DST}"
    chmod 0600 "${AUTH_KEYS_DST}" >/dev/null 2>&1 || true
    AUTH_KEYS_ARG="-D ${AUTH_KEYS_DST_DIR}"
  elif [ "${SSH_PASSWORD_AUTH}" != "1" ]; then
    echo "missing authorized_keys source for key-only mode: ${AUTH_KEYS_SRC}" >&2
    exit 1
  fi
else
  rm -f "${AUTH_KEYS_DST}" >/dev/null 2>&1 || true
fi

if [ "${SSH_PASSWORD_AUTH}" != "1" ] && [ "${SSH_ALLOW_KEY_AUTH}" != "1" ]; then
  echo "invalid SSH auth configuration: password and key auth are both disabled" >&2
  exit 1
fi

resolved_auth_mode="key_password"
if [ "${SSH_PASSWORD_AUTH}" = "1" ] && [ "${SSH_ALLOW_KEY_AUTH}" != "1" ]; then
  resolved_auth_mode="password_only"
elif [ "${SSH_PASSWORD_AUTH}" != "1" ] && [ "${SSH_ALLOW_KEY_AUTH}" = "1" ]; then
  resolved_auth_mode="key_only"
fi
printf '%s\n' "${resolved_auth_mode}" > "${AUTH_MODE_FILE}"

AUTH_MODE_ARG=""
if [ "${SSH_PASSWORD_AUTH}" != "1" ]; then
  AUTH_MODE_ARG="-s"
fi

WINDOW_ARG=""
case "${SSH_RECV_WINDOW_BYTES}" in
  ''|*[!0-9]*)
    ;;
  *)
    if [ "${SSH_RECV_WINDOW_BYTES}" -ge 16384 ] && [ "${SSH_RECV_WINDOW_BYTES}" -le 10485760 ]; then
      WINDOW_ARG="-W ${SSH_RECV_WINDOW_BYTES}"
    fi
    ;;
esac

exec "${DROPBEAR_BIN}" \
  -F \
  -p "${SSH_BIND_ADDRESS}:${SSH_PORT}" \
  -P "${PID_FILE}" \
  ${AUTH_KEYS_ARG} \
  -r "${ED25519_KEY}" \
  -r "${RSA_KEY}" \
  ${WINDOW_ARG} \
  -K "${SSH_KEEPALIVE_SEC}" \
  -I "${SSH_IDLE_TIMEOUT_SEC}" \
  ${AUTH_MODE_ARG}
