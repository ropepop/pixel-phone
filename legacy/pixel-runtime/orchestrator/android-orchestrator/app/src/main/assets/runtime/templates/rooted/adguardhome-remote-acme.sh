#!/bin/bash
set -euo pipefail

PIHOLE_REMOTE_DIR="/etc/pixel-stack/remote-dns"
PIHOLE_REMOTE_SECRETS_DIR="${PIHOLE_REMOTE_DIR}/secrets"
PIHOLE_REMOTE_TLS_DIR="${PIHOLE_REMOTE_DIR}/tls"
PIHOLE_REMOTE_RUNTIME_ENV_FILE="${PIHOLE_REMOTE_DIR}/runtime.env"
PIHOLE_REMOTE_CF_TOKEN_SECRET_FILE="${PIHOLE_REMOTE_SECRETS_DIR}/cloudflare-api-token"
PIHOLE_REMOTE_CF_PLUGIN_FILE="${PIHOLE_REMOTE_SECRETS_DIR}/cloudflare.ini"
PIHOLE_REMOTE_TLS_CERT_FILE="${PIHOLE_REMOTE_TLS_DIR}/fullchain.pem"
PIHOLE_REMOTE_TLS_KEY_FILE="${PIHOLE_REMOTE_TLS_DIR}/privkey.pem"

# Runtime values are written by adguardhome-start before ACME helper execution.
if [[ -f "${PIHOLE_REMOTE_RUNTIME_ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${PIHOLE_REMOTE_RUNTIME_ENV_FILE}"
fi

PIHOLE_REMOTE_DOH_ENABLED="${PIHOLE_REMOTE_DOH_ENABLED:-0}"
PIHOLE_REMOTE_DOT_ENABLED="${PIHOLE_REMOTE_DOT_ENABLED:-0}"
ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED="${ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED:-0}"
PIHOLE_REMOTE_HOSTNAME="${PIHOLE_REMOTE_HOSTNAME:-dns.example.com}"
PIHOLE_REMOTE_DOT_HOSTNAME="${PIHOLE_REMOTE_DOT_HOSTNAME:-${PIHOLE_REMOTE_HOSTNAME}}"
ADGUARDHOME_REMOTE_ADMIN_ENABLED="${ADGUARDHOME_REMOTE_ADMIN_ENABLED:-1}"
PIHOLE_REMOTE_ACME_ENABLED="${PIHOLE_REMOTE_ACME_ENABLED:-1}"
PIHOLE_REMOTE_ACME_EMAIL="${PIHOLE_REMOTE_ACME_EMAIL:-}"
PIHOLE_REMOTE_ACME_RENEW_MIN_DAYS="${PIHOLE_REMOTE_ACME_RENEW_MIN_DAYS:-30}"

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

ts() { date '+%Y-%m-%dT%H:%M:%S%z'; }
log() { printf '[%s] [adguardhome-remote-acme] %s\n' "$(ts)" "$*" >&2; }

cert_lineage_name() {
  if [[ -n "${PIHOLE_REMOTE_HOSTNAME}" ]]; then
    printf '%s\n' "${PIHOLE_REMOTE_HOSTNAME}"
    return 0
  fi
  if [[ -n "${PIHOLE_REMOTE_DOT_HOSTNAME}" ]]; then
    printf '%s\n' "${PIHOLE_REMOTE_DOT_HOSTNAME}"
    return 0
  fi
  printf 'pixel-stack-remote-dns\n'
}

cert_live_dir() {
  printf '/etc/letsencrypt/live/%s\n' "$(cert_lineage_name)"
}

cert_fullchain_live() {
  printf '%s/fullchain.pem\n' "$(cert_live_dir)"
}

cert_privkey_live() {
  printf '%s/privkey.pem\n' "$(cert_live_dir)"
}

sync_tls_links() {
  local live_full live_key
  live_full="$(cert_fullchain_live)"
  live_key="$(cert_privkey_live)"
  mkdir -p "${PIHOLE_REMOTE_TLS_DIR}"
  [[ -r "${live_full}" ]] || return 1
  [[ -r "${live_key}" ]] || return 1
  ln -sfn "${live_full}" "${PIHOLE_REMOTE_TLS_CERT_FILE}"
  ln -sfn "${live_key}" "${PIHOLE_REMOTE_TLS_KEY_FILE}"
}

write_cloudflare_plugin_credentials() {
  if [[ ! -r "${PIHOLE_REMOTE_CF_TOKEN_SECRET_FILE}" ]]; then
    log "Cloudflare API token secret missing: ${PIHOLE_REMOTE_CF_TOKEN_SECRET_FILE}"
    return 1
  fi
  local token
  token="$(tr -d '\r' < "${PIHOLE_REMOTE_CF_TOKEN_SECRET_FILE}" | head -n1)"
  [[ -n "${token}" ]] || {
    log "Cloudflare API token secret file is empty: ${PIHOLE_REMOTE_CF_TOKEN_SECRET_FILE}"
    return 1
  }
  mkdir -p "${PIHOLE_REMOTE_SECRETS_DIR}"
  umask 077
  cat > "${PIHOLE_REMOTE_CF_PLUGIN_FILE}" <<EOF_CF
# Managed by pixel-stack rooted Pi-hole remote ACME helper
# Cloudflare DNS plugin credential file for certbot
dns_cloudflare_api_token = ${token}
EOF_CF
  chmod 600 "${PIHOLE_REMOTE_CF_PLUGIN_FILE}"
}

cert_needs_renewal() {
  local threshold_seconds live_full
  live_full="$(cert_fullchain_live)"
  threshold_seconds="$(( PIHOLE_REMOTE_ACME_RENEW_MIN_DAYS * 86400 ))"
  [[ -r "${live_full}" ]] || return 0
  command -v openssl >/dev/null 2>&1 || return 0
  if openssl x509 -checkend "${threshold_seconds}" -noout -in "${live_full}" >/dev/null 2>&1; then
    return 1
  fi
  return 0
}

desired_cert_domains() {
  local -a domains=()
  if is_true "${PIHOLE_REMOTE_DOH_ENABLED}" || is_true "${ADGUARDHOME_REMOTE_ADMIN_ENABLED}"; then
    [[ -n "${PIHOLE_REMOTE_HOSTNAME}" ]] && domains+=("${PIHOLE_REMOTE_HOSTNAME}")
  fi
  if is_true "${PIHOLE_REMOTE_DOT_ENABLED}"; then
    [[ -n "${PIHOLE_REMOTE_DOT_HOSTNAME}" ]] && domains+=("${PIHOLE_REMOTE_DOT_HOSTNAME}")
  fi
  if is_true "${ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED}"; then
    [[ -n "${PIHOLE_REMOTE_DOT_HOSTNAME}" ]] && domains+=("*.${PIHOLE_REMOTE_DOT_HOSTNAME}")
  fi
  printf '%s\n' "${domains[@]}" | awk 'NF && !seen[$0]++'
}

desired_cert_domains_csv() {
  desired_cert_domains | paste -sd ',' -
}

current_cert_domains() {
  local live_full
  live_full="$(cert_fullchain_live)"
  [[ -r "${live_full}" ]] || return 0
  openssl x509 -in "${live_full}" -noout -ext subjectAltName 2>/dev/null \
    | tr ',' '\n' \
    | sed -n 's/^[[:space:]]*DNS://p' \
    | sed -e 's/[[:space:]]*$//' \
    | awk 'NF && !seen[$0]++'
}

cert_domains_changed() {
  local desired current
  desired="$(desired_cert_domains | sort)"
  current="$(current_cert_domains | sort)"
  [[ "${desired}" != "${current}" ]]
}

run_certbot_issue() {
  local cert_name domain
  local -a cmd=(
    certbot certonly
    --non-interactive
    --agree-tos
    --dns-cloudflare
    --dns-cloudflare-credentials "${PIHOLE_REMOTE_CF_PLUGIN_FILE}"
    --keep-until-expiring
    --preferred-challenges dns
    --cert-name "$(cert_lineage_name)"
    -m "${PIHOLE_REMOTE_ACME_EMAIL}"
  )
  while IFS= read -r domain; do
    [[ -n "${domain}" ]] || continue
    cmd+=( -d "${domain}" )
  done < <(desired_cert_domains)
  run_certbot_cmd "${cmd[@]}"
}

run_certbot_renew_force() {
  local domain
  local -a cmd=(
    certbot certonly
    --non-interactive
    --agree-tos
    --dns-cloudflare
    --dns-cloudflare-credentials "${PIHOLE_REMOTE_CF_PLUGIN_FILE}"
    --preferred-challenges dns
    --force-renewal
    --cert-name "$(cert_lineage_name)"
    -m "${PIHOLE_REMOTE_ACME_EMAIL}"
  )
  while IFS= read -r domain; do
    [[ -n "${domain}" ]] || continue
    cmd+=( -d "${domain}" )
  done < <(desired_cert_domains)
  run_certbot_cmd "${cmd[@]}"
}

run_certbot_cmd() {
  local rc output
  set +e
  output="$("$@" 2>&1)"
  rc=$?
  set -e
  [[ -n "${output}" ]] && printf '%s\n' "${output}"
  if (( rc == 0 )); then
    return 0
  fi
  if [[ "${output}" == *"Another instance of Certbot is already running"* ]]; then
    log "Certbot is already running in another process; deferring ACME action"
    return 11
  fi
  return "${rc}"
}

ensure_cert() {
  if ! is_true "${PIHOLE_REMOTE_DOH_ENABLED}" && ! is_true "${PIHOLE_REMOTE_DOT_ENABLED}" && ! is_true "${ADGUARDHOME_REMOTE_ADMIN_ENABLED}"; then
    log "Remote endpoints disabled; ACME helper no-op"
    return 0
  fi
  if ! is_true "${PIHOLE_REMOTE_ACME_ENABLED}"; then
    if sync_tls_links; then
      return 0
    fi
    log "Remote ACME disabled and no existing certificate found for ${PIHOLE_REMOTE_HOSTNAME}"
    return 1
  fi

  if [[ -z "$(desired_cert_domains_csv)" ]]; then
    log "At least one remote DNS hostname must be configured for ACME"
    return 1
  fi
  [[ -n "${PIHOLE_REMOTE_ACME_EMAIL}" ]] || {
    if sync_tls_links; then
      log "PIHOLE_REMOTE_ACME_EMAIL is unset; using existing certificate for $(desired_cert_domains_csv) without ACME renewal"
      return 0
    fi
    log "PIHOLE_REMOTE_ACME_EMAIL is required when PIHOLE_REMOTE_ACME_ENABLED=1"
    return 1
  }
  [[ "${PIHOLE_REMOTE_ACME_RENEW_MIN_DAYS}" =~ ^[0-9]+$ ]] || {
    log "PIHOLE_REMOTE_ACME_RENEW_MIN_DAYS must be numeric"
    return 1
  }

  command -v certbot >/dev/null 2>&1 || {
    log "certbot is not installed"
    return 1
  }
  command -v openssl >/dev/null 2>&1 || {
    log "openssl is not installed"
    return 1
  }

  write_cloudflare_plugin_credentials || return 1

  local changed=0
  if [[ ! -r "$(cert_fullchain_live)" || ! -r "$(cert_privkey_live)" ]]; then
    log "Issuing initial certificate for $(desired_cert_domains_csv) via DNS-01"
    set +e
    run_certbot_issue
    local issue_rc=$?
    set -e
    case "${issue_rc}" in
      0)
        changed=1
        ;;
      11)
        # Another certbot process may have just refreshed the cert.
        if sync_tls_links; then
          return 11
        fi
        log "Certbot busy and certificate not yet available for $(desired_cert_domains_csv)"
        return 1
        ;;
      *)
        return "${issue_rc}"
        ;;
    esac
  elif cert_domains_changed; then
    log "Certificate SAN set changed; renewing lineage for $(desired_cert_domains_csv)"
    set +e
    run_certbot_renew_force
    local domains_rc=$?
    set -e
    case "${domains_rc}" in
      0)
        changed=1
        ;;
      11)
        if sync_tls_links; then
          return 11
        fi
        log "Certbot busy and updated SAN set could not be synced"
        return 1
        ;;
      *)
        return "${domains_rc}"
        ;;
    esac
  elif cert_needs_renewal; then
    log "Certificate for $(desired_cert_domains_csv) is within renewal window; forcing renewal"
    set +e
    run_certbot_renew_force
    local renew_rc=$?
    set -e
    case "${renew_rc}" in
      0)
        changed=1
        ;;
      11)
        if sync_tls_links; then
          return 11
        fi
        log "Certbot busy and certificate symlinks could not be refreshed"
        return 1
        ;;
      *)
        return "${renew_rc}"
        ;;
    esac
  else
    log "Certificate for $(desired_cert_domains_csv) is present and outside renewal window"
  fi

  sync_tls_links || {
    log "Failed to sync TLS symlinks under ${PIHOLE_REMOTE_TLS_DIR}"
    return 1
  }

  if (( changed == 1 )); then
    return 10
  fi
  return 0
}

usage() {
  cat <<EOH
Usage: adguardhome-remote-acme [ensure|renew-if-needed]
EOH
}

cmd="${1:-ensure}"
case "${cmd}" in
  ensure|renew-if-needed)
    set +e
    ensure_cert
    rc=$?
    set -e
    exit "${rc}"
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
