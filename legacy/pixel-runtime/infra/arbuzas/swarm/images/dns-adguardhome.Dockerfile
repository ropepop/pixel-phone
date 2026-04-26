FROM debian:bookworm-slim

ARG ADGUARDHOME_RELEASE_URL="https://static.adguard.com/adguardhome/release/AdGuardHome_linux_amd64.tar.gz"

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl python3 netcat-openbsd tar \
  && rm -rf /var/lib/apt/lists/*

RUN tmpdir="$(mktemp -d)" \
  && curl -fsSL "${ADGUARDHOME_RELEASE_URL}" -o "${tmpdir}/adguardhome.tar.gz" \
  && tar -xzf "${tmpdir}/adguardhome.tar.gz" -C "${tmpdir}" \
  && install -d -m 0755 /opt/adguardhome/conf /opt/adguardhome/work /opt/adguardhome/work/data /opt/adguardhome/work/filters \
  && install -m 0755 "${tmpdir}/AdGuardHome/AdGuardHome" /opt/adguardhome/AdGuardHome \
  && rm -rf "${tmpdir}"

COPY legacy/pixel-runtime/infra/adguardhome/debian/prepare-arbuzas-adguardhome-config.sh /usr/local/bin/prepare-arbuzas-adguardhome-config.sh
COPY legacy/pixel-runtime/infra/arbuzas/swarm/images/dns-adguardhome-entrypoint.sh /usr/local/bin/dns-adguardhome-entrypoint.sh

WORKDIR /opt/adguardhome

CMD ["/usr/local/bin/dns-adguardhome-entrypoint.sh"]
