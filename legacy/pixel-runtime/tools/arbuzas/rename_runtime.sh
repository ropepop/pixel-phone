#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

service_user="pixelops"
service_group="pixelops"

move_dir_once() {
  local old_path="$1"
  local new_path="$2"

  if [[ -L "${old_path}" ]] && [[ "$(readlink -f "${old_path}")" == "${new_path}" ]]; then
    return 0
  fi

  if [[ -d "${old_path}" ]] && [[ ! -e "${new_path}" ]]; then
    mv "${old_path}" "${new_path}"
  elif [[ -d "${old_path}" ]] && [[ -d "${new_path}" ]]; then
    cp -a "${old_path}/." "${new_path}/"
    rm -rf "${old_path}"
  elif [[ ! -e "${new_path}" ]]; then
    install -d -m 0755 "${new_path}"
  fi
}

write_unit() {
  local path="$1"
  local body="$2"
  printf '%s\n' "${body}" > "${path}"
}

install -d -m 0755 /opt /etc /var/lib /var/log /run
move_dir_once /opt/pixel-ops /opt/arbuzas
move_dir_once /etc/pixel-ops /etc/arbuzas
move_dir_once /var/lib/pixel-ops /var/lib/arbuzas
install -d -m 0755 /var/log/arbuzas /run/arbuzas

chown "${service_user}:${service_group}" /run/arbuzas
chown -R "${service_user}:${service_group}" /var/log/arbuzas /var/lib/arbuzas /etc/arbuzas /opt/arbuzas/site-notifications

ln -sfn /opt/arbuzas /opt/pixel-ops
ln -sfn /etc/arbuzas /etc/pixel-ops
ln -sfn /var/lib/arbuzas /var/lib/pixel-ops

cat > /etc/tmpfiles.d/arbuzas.conf <<'EOF_TMPFILES'
d /run/arbuzas 0755 pixelops pixelops -
EOF_TMPFILES
systemd-tmpfiles --create /etc/tmpfiles.d/arbuzas.conf

if compgen -G "/etc/arbuzas/env/*.env" > /dev/null; then
  sed -i \
    -e 's#/etc/pixel-ops#/etc/arbuzas#g' \
    -e 's#/var/lib/pixel-ops#/var/lib/arbuzas#g' \
    -e 's#/run/pixel-ops#/run/arbuzas#g' \
    /etc/arbuzas/env/*.env
fi

if compgen -G "/etc/arbuzas/cloudflared/*.yml" > /dev/null; then
  sed -i 's#/etc/pixel-ops#/etc/arbuzas#g' /etc/arbuzas/cloudflared/*.yml
fi

write_unit /etc/systemd/system/arbuzas-train.service "[Unit]
Description=Train
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=${service_user}
Group=${service_group}
WorkingDirectory=/var/lib/arbuzas/train-bot
EnvironmentFile=/etc/arbuzas/env/train-bot.env
ExecStart=/opt/arbuzas/bin/train-bot
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

write_unit /etc/systemd/system/arbuzas-satiksme.service "[Unit]
Description=Satiksme
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=${service_user}
Group=${service_group}
WorkingDirectory=/var/lib/arbuzas/satiksme-bot
EnvironmentFile=/etc/arbuzas/env/satiksme-bot.env
ExecStart=/opt/arbuzas/bin/satiksme-bot
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

write_unit /etc/systemd/system/arbuzas-subscription.service "[Unit]
Description=Subscription
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=${service_user}
Group=${service_group}
WorkingDirectory=/var/lib/arbuzas/subscription-bot
EnvironmentFile=/etc/arbuzas/env/subscription-bot.env
ExecStart=/opt/arbuzas/bin/subscription-bot
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

write_unit /etc/systemd/system/arbuzas-notifications.service "[Unit]
Description=Notifications
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=${service_user}
Group=${service_group}
WorkingDirectory=/opt/arbuzas/site-notifications
Environment=RUNTIME_CONTEXT_POLICY=systemd_service
ExecStart=/opt/arbuzas/site-notifications/.venv/bin/python /opt/arbuzas/site-notifications/app.py daemon
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

write_unit /etc/systemd/system/arbuzas-cloudflared-train.service "[Unit]
Description=arbuzas Cloudflare tunnel for Train
Wants=network-online.target arbuzas-train.service
After=network-online.target arbuzas-train.service
Requires=arbuzas-train.service

[Service]
Type=simple
User=${service_user}
Group=${service_group}
ExecStart=/usr/local/bin/cloudflared --config /etc/arbuzas/cloudflared/train-bot.yml --no-autoupdate tunnel run
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

write_unit /etc/systemd/system/arbuzas-cloudflared-satiksme.service "[Unit]
Description=arbuzas Cloudflare tunnel for Satiksme
Wants=network-online.target arbuzas-satiksme.service
After=network-online.target arbuzas-satiksme.service
Requires=arbuzas-satiksme.service

[Service]
Type=simple
User=${service_user}
Group=${service_group}
ExecStart=/usr/local/bin/cloudflared --config /etc/arbuzas/cloudflared/satiksme-bot.yml --no-autoupdate tunnel run
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

write_unit /etc/systemd/system/arbuzas-cloudflared-subscription.service "[Unit]
Description=arbuzas Cloudflare tunnel for Subscription
Wants=network-online.target arbuzas-subscription.service
After=network-online.target arbuzas-subscription.service
Requires=arbuzas-subscription.service

[Service]
Type=simple
User=${service_user}
Group=${service_group}
ExecStart=/usr/local/bin/cloudflared --config /etc/arbuzas/cloudflared/subscription-bot.yml --no-autoupdate tunnel run
Restart=always
RestartSec=5
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target"

old_units=(
  train-bot.service
  satiksme-bot.service
  subscription-bot.service
  site-notifications.service
  cloudflared-train-bot.service
  cloudflared-satiksme-bot.service
  cloudflared-subscription-bot.service
)

new_units=(
  arbuzas-train.service
  arbuzas-satiksme.service
  arbuzas-subscription.service
  arbuzas-notifications.service
  arbuzas-cloudflared-train.service
  arbuzas-cloudflared-satiksme.service
  arbuzas-cloudflared-subscription.service
)

systemctl daemon-reload

for unit in "${old_units[@]}"; do
  systemctl stop "${unit}" 2>/dev/null || true
done

for unit in "${old_units[@]}"; do
  systemctl disable "${unit}" 2>/dev/null || true
done

systemctl enable arbuzas-train.service arbuzas-satiksme.service arbuzas-subscription.service arbuzas-notifications.service
systemctl start arbuzas-train.service arbuzas-satiksme.service arbuzas-subscription.service arbuzas-notifications.service

systemctl enable arbuzas-cloudflared-train.service arbuzas-cloudflared-satiksme.service arbuzas-cloudflared-subscription.service
systemctl start arbuzas-cloudflared-train.service arbuzas-cloudflared-satiksme.service arbuzas-cloudflared-subscription.service

systemctl is-active --quiet "${new_units[@]}"
echo "arbuzas runtime rename complete"
