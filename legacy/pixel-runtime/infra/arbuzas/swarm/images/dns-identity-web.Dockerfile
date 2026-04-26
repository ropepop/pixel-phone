FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl \
  && rm -rf /var/lib/apt/lists/*

COPY legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identityctl /usr/local/bin/adguardhome-doh-identityctl
COPY legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py /usr/local/bin/adguardhome-doh-identities.py
COPY legacy/pixel-runtime/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identity-web.py /usr/local/bin/adguardhome-doh-identity-web.py

CMD ["python3", "/usr/local/bin/adguardhome-doh-identity-web.py"]
