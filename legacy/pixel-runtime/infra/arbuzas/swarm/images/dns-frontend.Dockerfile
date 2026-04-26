FROM nginx:1.27-bookworm

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 netcat-openbsd \
  && rm -rf /var/lib/apt/lists/*

COPY legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-frontctl.sh /usr/local/bin/arbuzas-dns-frontctl.sh
COPY legacy/pixel-runtime/infra/adguardhome/debian/arbuzas-dns-nginx.conf.template /usr/local/share/arbuzas-dns/arbuzas-dns-nginx.conf.template
COPY legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identityctl /usr/local/bin/adguardhome-doh-identityctl
COPY legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py /usr/local/bin/adguardhome-doh-identities.py
COPY legacy/pixel-runtime/infra/arbuzas/swarm/images/dns-frontend-entrypoint.sh /usr/local/bin/dns-frontend-entrypoint.sh

RUN install -d -m 0755 /etc/arbuzas/dns /usr/local/share/arbuzas-dns /var/lib/arbuzas/dns/runtime /var/log/arbuzas/dns

CMD ["/usr/local/bin/dns-frontend-entrypoint.sh"]
