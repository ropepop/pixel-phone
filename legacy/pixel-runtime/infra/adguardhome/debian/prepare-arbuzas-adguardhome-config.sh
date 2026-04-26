#!/usr/bin/env bash
set -euo pipefail

src="${1:-}"
dst="${2:-}"

if [[ -z "${src}" || -z "${dst}" ]]; then
  echo "usage: prepare-arbuzas-adguardhome-config.sh SOURCE_YAML DEST_YAML" >&2
  exit 2
fi

if [[ ! -r "${src}" ]]; then
  echo "source config missing: ${src}" >&2
  exit 1
fi

RUNTIME_ENV_FILE="${ARBUZAS_DNS_RUNTIME_ENV_FILE:-/etc/arbuzas/dns/runtime.env}"
if [[ ! -r "${RUNTIME_ENV_FILE}" && -r /etc/pixel-stack/remote-dns/runtime.env ]]; then
  RUNTIME_ENV_FILE="/etc/pixel-stack/remote-dns/runtime.env"
fi
if [[ -r "${RUNTIME_ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "${RUNTIME_ENV_FILE}"
  set +a
fi

remote_dot_enabled="${PIHOLE_REMOTE_DOT_ENABLED:-1}"
remote_dot_identity_enabled="${PIHOLE_REMOTE_DOT_IDENTITY_ENABLED:-1}"
web_port="${PIHOLE_WEB_PORT:-8080}"
web_bind_host="${ARBUZAS_DNS_WEB_BIND_HOST:-127.0.0.1}"
web_bind="${web_bind_host}:${web_port}"
trusted_proxies="${ARBUZAS_DNS_TRUSTED_PROXIES:-127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,fc00::/7,fe80::/10}"
dot_port="${ADGUARDHOME_REMOTE_DOT_INTERNAL_PORT:-8853}"
server_name="${PIHOLE_REMOTE_DOT_HOSTNAME:-${PIHOLE_REMOTE_HOSTNAME:-dns.example.com}}"
cert_file="${PIHOLE_REMOTE_TLS_CERT_FILE:-/etc/arbuzas/dns/tls/fullchain.pem}"
key_file="${PIHOLE_REMOTE_TLS_KEY_FILE:-/etc/arbuzas/dns/tls/privkey.pem}"

mkdir -p "$(dirname "${dst}")"

awk \
  -v web_bind="${web_bind}" \
  -v trusted_proxies="${trusted_proxies}" \
  -v remote_dot_enabled="${remote_dot_enabled}" \
  -v remote_dot_identity_enabled="${remote_dot_identity_enabled}" \
  -v dot_port="${dot_port}" \
  -v server_name="${server_name}" \
  -v cert_file="${cert_file}" \
  -v key_file="${key_file}" \
  '
  BEGIN {
    in_http = 0
    in_dns = 0
    in_tls = 0
    saw_dns = 0
    saw_tls = 0
    http_address_emitted = 0
    skip_http_trusted = 0
    dns_trusted_emitted = 0
    skip_dns_trusted = 0
    trusted_proxy_count = split(trusted_proxies, trusted_proxy_list, ",")
  }

  function trim(value) {
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    return value
  }

  function emit_trusted_proxies(indent,    i, value, emitted_any) {
    print indent "trusted_proxies:"
    emitted_any = 0
    for (i = 1; i <= trusted_proxy_count; i++) {
      value = trim(trusted_proxy_list[i])
      if (!value) {
        continue
      }
      print indent "  - " value
      emitted_any = 1
    }
    if (!emitted_any) {
      print indent "  - 127.0.0.0/8"
      print indent "  - ::1/128"
    }
  }

  function emit_tls_block() {
    print "tls:"
    if (remote_dot_enabled ~ /^(1|true|TRUE|yes|YES|on|ON)$/) {
      print "  enabled: true"
      print "  server_name: " server_name
      if (remote_dot_identity_enabled ~ /^(1|true|TRUE|yes|YES|on|ON)$/) {
        print "  strict_sni_check: true"
      }
      print "  force_https: false"
      print "  port_https: 0"
      print "  port_dns_over_tls: " dot_port
      print "  port_dns_over_quic: 0"
      print "  allow_unencrypted_doh: true"
      print "  certificate_path: \"" cert_file "\""
      print "  private_key_path: \"" key_file "\""
    } else {
      print "  enabled: false"
      print "  force_https: false"
      print "  port_https: 0"
      print "  port_dns_over_tls: 0"
      print "  port_dns_over_quic: 0"
      print "  allow_unencrypted_doh: true"
    }
  }

  {
    if ($0 == "http:") {
      in_http = 1
      http_address_emitted = 0
      skip_http_trusted = 0
      print
      next
    }

    if (in_http) {
      if (skip_http_trusted) {
        if ($0 ~ /^  - / || $0 ~ /^    / || $0 ~ /^[[:space:]]*$/) {
          next
        }
        skip_http_trusted = 0
      }

      if ($0 ~ /^  trusted_proxies:[[:space:]]*$/) {
        skip_http_trusted = 1
        next
      }

      if ($0 ~ /^  address:[[:space:]]*/) {
        print "  address: " web_bind
        http_address_emitted = 1
        next
      }

      if ($0 ~ /^[^[:space:]]/) {
        if (!http_address_emitted) {
          print "  address: " web_bind
        }
        in_http = 0
      }
    }

    if ($0 == "dns:") {
      in_dns = 1
      saw_dns = 1
      dns_trusted_emitted = 0
      skip_dns_trusted = 0
      print
      next
    }

    if (in_dns) {
      if (skip_dns_trusted) {
        if ($0 ~ /^  - / || $0 ~ /^    / || $0 ~ /^[[:space:]]*$/) {
          next
        }
        skip_dns_trusted = 0
      }

      if ($0 ~ /^  trusted_proxies:[[:space:]]*$/) {
        emit_trusted_proxies("  ")
        dns_trusted_emitted = 1
        skip_dns_trusted = 1
        next
      }

      if ($0 ~ /^  port:[[:space:]]*/) {
        print
        if (!dns_trusted_emitted) {
          emit_trusted_proxies("  ")
          dns_trusted_emitted = 1
        }
        next
      }

      if ($0 ~ /^[^[:space:]]/) {
        if (!dns_trusted_emitted) {
          emit_trusted_proxies("  ")
          dns_trusted_emitted = 1
        }
        in_dns = 0
      }
    }

    if ($0 == "tls:") {
      saw_tls = 1
      in_tls = 1
      emit_tls_block()
      next
    }

    if (in_tls) {
      if ($0 ~ /^[^[:space:]]/) {
        in_tls = 0
      } else {
        next
      }
    }

    print
  }

  END {
    if (in_http) {
      if (!http_address_emitted) {
        print "  address: " web_bind
      }
    }
    if (in_dns && !dns_trusted_emitted) {
      emit_trusted_proxies("  ")
    }
    if (!saw_tls) {
      emit_tls_block()
    }
  }
' "${src}" > "${dst}"

chmod 600 "${dst}" || true
