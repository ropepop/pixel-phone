#![allow(non_snake_case)]
use crate::{Config, trim, whitespace};

fn quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

pub fn build(config: &Config) -> Result<String, String> {
    let rootfsPath = quote(config.s("runtime.rootfsPath")?);
    let trainEnvFile = quote(config.s("trainBot.envFile")?);
    let trainRuntimeRoot = quote(config.s("trainBot.runtimeRoot")?);
    let trainScheduleDir = quote(config.s("trainBot.scheduleDir")?);
    let satiksmeEnvFile = quote(config.s("satiksmeBot.envFile")?);
    let trainPidFile = quote(&format!(
        "{}/run/train-bot.pid",
        config.s("trainBot.runtimeRoot")?
    ));
    let trainTunnelSupervisorPidFile = quote(&format!(
        "{}/run/train-web-tunnel-service-loop.pid",
        config.s("trainBot.runtimeRoot")?
    ));
    let trainTunnelPidFile = quote(&format!(
        "{}/run/train-bot-cloudflared.pid",
        config.s("trainBot.runtimeRoot")?
    ));
    let trainHeartbeatFile = quote(&format!(
        "{}/run/heartbeat.epoch",
        config.s("trainBot.runtimeRoot")?
    ));
    let satiksmePidFile = quote(&format!(
        "{}/run/satiksme-bot.pid",
        config.s("satiksmeBot.runtimeRoot")?
    ));
    let satiksmeTunnelSupervisorPidFile = quote(&format!(
        "{}/run/satiksme-web-tunnel-service-loop.pid",
        config.s("satiksmeBot.runtimeRoot")?
    ));
    let satiksmeTunnelPidFile = quote(&format!(
        "{}/run/satiksme-bot-cloudflared.pid",
        config.s("satiksmeBot.runtimeRoot")?
    ));
    let satiksmeHeartbeatFile = quote(&format!(
        "{}/run/heartbeat.epoch",
        config.s("satiksmeBot.runtimeRoot")?
    ));
    let notifierPidFile = quote(&format!(
        "{}/run/site-notifier.pid",
        config.s("siteNotifier.runtimeRoot")?
    ));
    let notifierHeartbeatFile = quote(&format!(
        "{}/run/heartbeat.epoch",
        config.s("siteNotifier.runtimeRoot")?
    ));
    let subscriptionPidFile = quote(&format!(
        "{}/run/subscription-bot.pid",
        config.s("subscriptionBot.runtimeRoot")?
    ));
    let subscriptionHeartbeatFile = quote(&format!(
        "{}/run/heartbeat.epoch",
        config.s("subscriptionBot.runtimeRoot")?
    ));

    let supervisorStateFile = quote(
        "/data/data/lv.jolkins.pixelorchestrator/files/stack-store/orchestrator-state-v1.json",
    );
    let notifierHealthScript = quote("/data/local/pixel-stack/bin/pixel-notifier-health.sh");
    let dohEndpointMode = quote(&config.mode()?);
    let dohPathToken = quote(trim(config.s("remote.dohPathToken")?));
    let httpsPort = config.i("remote.httpsPort")?;
    let hostname = config.s("remote.hostname")?;
    let remotePublicBaseUrl = quote(&if trim(hostname).is_empty() {
        String::new()
    } else if httpsPort == 443 {
        format!("https://{hostname}")
    } else {
        format!("https://{hostname}:{httpsPort}")
    });
    let expectedEnabled = i32::from(
        config.b("vpn.enabled")?
            || config.0["modules"]["vpn"]["enabled"]
                .as_bool()
                .unwrap_or(false),
    );
    let dohEnabled = i32::from(config.b("remote.dohEnabled")?);
    let remoteEnabled = i32::from(config.b("remote.dohEnabled")? || config.b("remote.dotEnabled")?);
    let mut output = String::new();
    output.push_str(&format!(r###"
      set +e
      rootfs_path={rootfsPath}
      resolve_probe_curl() {{
        if command -v curl >/dev/null 2>&1; then
          printf 'native:%s\n' "$(command -v curl 2>/dev/null || true)"
          return 0
        fi
        if [ -n "$rootfs_path" ] && [ -x "$rootfs_path/usr/bin/curl" ] && [ -x "$rootfs_path/usr/bin/env" ] && chroot "$rootfs_path" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
          printf 'chroot:%s\n' "$rootfs_path"
          return 0
        fi
        return 1
      }}
      probe_http_code() {{
        probe_spec="$1"
        probe_url="$2"
        probe_timeout="$3"
        probe_resolve_host="${{4:-}}"
        probe_resolve_port="${{5:-}}"
        probe_resolve_ip="${{6:-}}"
        probe_resolve_args=""
        if [ -n "$probe_resolve_host" ] && [ -n "$probe_resolve_port" ] && [ -n "$probe_resolve_ip" ]; then
          probe_resolve_args="--resolve $probe_resolve_host:$probe_resolve_port:$probe_resolve_ip"
        fi
        case "$probe_spec" in
          native:*)
            probe_bin=${{probe_spec#native:}}
            probe_code=$("$probe_bin" -ksS -o /dev/null -w '%{{http_code}}' --max-time "$probe_timeout" $probe_resolve_args "$probe_url" 2>/dev/null || true)
            ;;
          chroot:*)
            probe_rootfs=${{probe_spec#chroot:}}
            probe_code=$(chroot "$probe_rootfs" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -ksS -o /dev/null -w '%{{http_code}}' --max-time "$probe_timeout" $probe_resolve_args "$probe_url" 2>/dev/null || true)
            ;;
          *)
            probe_code="000"
            ;;
        esac
        case "$probe_code" in
          ""|"000000") probe_code="000" ;;
        esac
        printf '%s' "$probe_code"
      }}
      scan_pid_by_target() {{
        scan_target="$1"
        scan_target_base=$(basename "$scan_target")
        ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v pat="$scan_target" -v target_base="$scan_target_base" '
          function starts_with(value, prefix) {{ return index(value, prefix) == 1 }}
          function next_is_boundary(value, prefix_len) {{
            c = substr(value, prefix_len + 1, 1)
            return c == "" || c == " "
          }}
          {{
            pid = $1
            name = $2
            args = ""
            if (NF >= 3) {{
              args = substr($0, index($0, $3))
            }}
            if (name == target_base ||
              args == pat ||
              (starts_with(args, pat) && next_is_boundary(args, length(pat))) ||
              (starts_with(args, "sh " pat) && next_is_boundary(args, length("sh " pat))) ||
              (starts_with(args, target_base) && next_is_boundary(args, length(target_base)))) {{
              print pid
              exit
            }}
          }}
        ' | tr -d '\r'
      }}
      printf '__PIXEL_HEALTH_ID_U__\n'
      id -u 2>/dev/null || true
      printf '__PIXEL_HEALTH_LISTENERS__\n'
      ss -ltn 2>/dev/null || true
      "###));
    if config.enabled("ddns")? && config.b("ddns.enabled")? {
        output.push_str(
            r###"
      printf '__PIXEL_HEALTH_DDNS_EPOCH__\n'
      if [ -f /data/local/pixel-stack/run/ddns-last-sync-epoch ]; then
        cat /data/local/pixel-stack/run/ddns-last-sync-epoch 2>/dev/null || true
      fi
      printf '__PIXEL_HEALTH_DDNS_LAST_IPV4__\n'
      if [ -f /data/local/pixel-stack/run/ddns-last-ipv4 ]; then
        cat /data/local/pixel-stack/run/ddns-last-ipv4 2>/dev/null || true
      fi
      "###,
        );
    }
    output.push_str(&format!(r###"
      printf '__PIXEL_HEALTH_SUPERVISOR_LOOP_HEARTBEAT__\n'
      if [ -r {supervisorStateFile} ]; then
        sed -n 's/.*"supervisorLoopHeartbeatEpochSeconds"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' {supervisorStateFile} 2>/dev/null | sed -n '1p'
      fi
      "###));
    if config.enabled("train_bot")? {
        output.push_str(&format!(r###"
      printf '__PIXEL_HEALTH_TRAIN_BOT_PID__\n'
      train_pid=""
      if [ -r {trainPidFile} ]; then
        train_pid=$(sed -n '1p' {trainPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ -z "$train_pid" ] || ! kill -0 "$train_pid" >/dev/null 2>&1; then
        train_pid=$(
          ps -A 2>/dev/null | awk '(($NF=="train-bot") || index($NF,"train-bot.")==1) {{print $2; exit}}' | tr -d '\r'
        )
      fi
      if [ -n "$train_pid" ] && kill -0 "$train_pid" >/dev/null 2>&1; then
        printf '%s\n' "$train_pid"
      fi
      printf '__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_ENABLED__\n'
      train_tunnel_enabled="0"
      train_tunnel_public_base_url=""
      if [ -r {trainEnvFile} ]; then
        while IFS= read -r line || [ -n "$line" ]; do
          case "$line" in
            ''|'#'*) continue ;;
            *=*) ;;
            *) continue ;;
          esac
          key=${{line%%=*}}
          value=${{line#*=}}
          case "$value" in
            \"*\") value=${{value#\"}}; value=${{value%\"}} ;;
            \'*\') value=${{value#\'}}; value=${{value%\'}} ;;
          esac
          case "$key" in
            TRAIN_WEB_TUNNEL_ENABLED)
              case "$value" in
                1|true|TRUE|yes|YES|on|ON) train_tunnel_enabled="1" ;;
                *) train_tunnel_enabled="0" ;;
              esac
              ;;
            TRAIN_WEB_PUBLIC_BASE_URL)
              train_tunnel_public_base_url="$value"
              ;;
          esac
        done < {trainEnvFile}
      fi
      printf '%s\n' "$train_tunnel_enabled"
      printf '__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_SUPERVISOR_PID__\n'
      train_tunnel_supervisor_pid=""
      if [ "$train_tunnel_enabled" = "1" ] && [ -r {trainTunnelSupervisorPidFile} ]; then
        train_tunnel_supervisor_pid=$(sed -n '1p' {trainTunnelSupervisorPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ "$train_tunnel_enabled" = "1" ] && {{ [ -z "$train_tunnel_supervisor_pid" ] || ! kill -0 "$train_tunnel_supervisor_pid" >/dev/null 2>&1; }}; then
        train_tunnel_supervisor_pid=$(scan_pid_by_target /data/local/pixel-stack/apps/train-bot/bin/train-web-tunnel-service-loop)
      fi
      if [ -n "$train_tunnel_supervisor_pid" ] && kill -0 "$train_tunnel_supervisor_pid" >/dev/null 2>&1; then
        printf '%s\n' "$train_tunnel_supervisor_pid"
      fi
      printf '__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PID__\n'
      train_tunnel_pid=""
      if [ "$train_tunnel_enabled" = "1" ] && [ -r {trainTunnelPidFile} ]; then
        train_tunnel_pid=$(sed -n '1p' {trainTunnelPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ "$train_tunnel_enabled" = "1" ] && {{ [ -z "$train_tunnel_pid" ] || ! kill -0 "$train_tunnel_pid" >/dev/null 2>&1; }}; then
        train_tunnel_pid=$(scan_pid_by_target /data/local/pixel-stack/apps/train-bot/bin/cloudflared)
      fi
      if [ -n "$train_tunnel_pid" ] && kill -0 "$train_tunnel_pid" >/dev/null 2>&1; then
        printf '%s\n' "$train_tunnel_pid"
      fi
      printf '__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL__\n'
      printf '%s\n' "$train_tunnel_public_base_url"
      train_public_root_code="000"
      train_public_app_code="000"
      train_tunnel_probe_available="0"
      train_public_curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
      if [ "$train_tunnel_enabled" = "1" ] && [ -n "$train_tunnel_public_base_url" ] && [ -n "$train_public_curl_spec" ]; then
        train_tunnel_probe_available="1"
        train_public_root_url=$(printf '%s' "$train_tunnel_public_base_url" | sed 's#/*$##')
        train_public_app_url="$train_public_root_url/app"
        train_public_root_code=$(probe_http_code "$train_public_curl_spec" "$train_public_root_url/" 8)
        train_public_app_code=$(probe_http_code "$train_public_curl_spec" "$train_public_app_url" 8)
      fi
      printf '__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_ROOT_CODE__\n'
      printf '%s\n' "$train_public_root_code"
      printf '__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_APP_CODE__\n'
      printf '%s\n' "$train_public_app_code"
      printf '__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE__\n'
      printf '%s\n' "$train_tunnel_probe_available"
      printf '__PIXEL_HEALTH_TRAIN_BOT_HEARTBEAT__\n'
      if [ -f {trainHeartbeatFile} ]; then
        cat {trainHeartbeatFile} 2>/dev/null || true
      fi
      train_tz="Europe/Riga"
      train_scraper_daily_hour="3"
      train_db_path=""
      if [ -r {trainEnvFile} ]; then
        while IFS= read -r line || [ -n "$line" ]; do
          case "$line" in
            ''|'#'*) continue ;;
            *=*) ;;
            *) continue ;;
          esac
          key=${{line%%=*}}
          value=${{line#*=}}
          case "$value" in
            \"*\") value=${{value#\"}}; value=${{value%\"}} ;;
            \'*\') value=${{value#\'}}; value=${{value%\'}} ;;
          esac
          case "$key" in
            TZ) train_tz="$value" ;;
            SCRAPER_DAILY_HOUR) train_scraper_daily_hour="$value" ;;
            DB_PATH) train_db_path="$value" ;;
          esac
        done < {trainEnvFile}
      fi
      case "$train_tz" in
        "") train_tz="Europe/Riga" ;;
      esac
      case "$train_scraper_daily_hour" in
        ''|*[!0-9]*) train_scraper_daily_hour=3 ;;
      esac
      if [ "$train_scraper_daily_hour" -lt 0 ] || [ "$train_scraper_daily_hour" -gt 23 ]; then
        train_scraper_daily_hour=3
      fi
      train_service_date=$(TZ="$train_tz" date +%F 2>/dev/null || TZ=Europe/Riga date +%F 2>/dev/null || date +%F)
      train_local_hour=$(TZ="$train_tz" date +%H 2>/dev/null || TZ=Europe/Riga date +%H 2>/dev/null || date +%H)
      case "$train_local_hour" in
        ''|*[!0-9]*) train_local_hour=0 ;;
      esac
      train_schedule_required=0
      if [ "$train_local_hour" -ge "$train_scraper_daily_hour" ]; then
        train_schedule_required=1
      fi
      train_schedule_path={trainScheduleDir}/"${{train_service_date}}.json"
      train_schedule_fresh=0
      if [ -s "$train_schedule_path" ]; then
        train_schedule_fresh=1
      fi
      if [ -n "$train_db_path" ]; then
        case "$train_db_path" in
          /*) train_runtime_db_path="$train_db_path" ;;
          ./*) train_runtime_db_path={trainRuntimeRoot}/"${{train_db_path#./}}" ;;
          *) train_runtime_db_path={trainRuntimeRoot}/"$train_db_path" ;;
        esac
      else
        train_runtime_db_path={trainRuntimeRoot}/"train_bot.db"
      fi
      train_sqlite3_bin="$(command -v sqlite3 2>/dev/null || true)"
      train_schedule_rows="unknown"
      if [ -n "$train_sqlite3_bin" ] && [ -f "$train_runtime_db_path" ]; then
        train_schedule_rows=$("$train_sqlite3_bin" "$train_runtime_db_path" "select count(*) from train_instances where service_date='$train_service_date';" 2>/dev/null || printf 'unknown\n')
        train_schedule_rows=$(printf '%s' "$train_schedule_rows" | tr -d '\r')
        case "$train_schedule_rows" in
          "") train_schedule_rows="unknown" ;;
        esac
      fi
      printf '__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_REQUIRED__\n'
      printf '%s\n' "$train_schedule_required"
      printf '__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_FRESH__\n'
      printf '%s\n' "$train_schedule_fresh"
      printf '__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_SERVICE_DATE__\n'
      printf '%s\n' "$train_service_date"
      printf '__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_ROWS__\n'
      printf '%s\n' "$train_schedule_rows"
      "###));
    }
    output.push_str(
        r###"
      "###,
    );
    if config.enabled("satiksme_bot")? {
        output.push_str(&format!(r###"
      printf '__PIXEL_HEALTH_SATIKSME_BOT_PID__\n'
      satiksme_pid=""
      if [ -r {satiksmePidFile} ]; then
        satiksme_pid=$(sed -n '1p' {satiksmePidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ -z "$satiksme_pid" ] || ! kill -0 "$satiksme_pid" >/dev/null 2>&1; then
        satiksme_pid=$(
          ps -A 2>/dev/null | awk '(($NF=="satiksme-bot") || index($NF,"satiksme-bot.")==1) {{print $2; exit}}' | tr -d '\r'
        )
      fi
      if [ -n "$satiksme_pid" ] && kill -0 "$satiksme_pid" >/dev/null 2>&1; then
        printf '%s\n' "$satiksme_pid"
      fi
      printf '__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_ENABLED__\n'
      satiksme_tunnel_enabled="0"
      satiksme_tunnel_public_base_url=""
      if [ -r {satiksmeEnvFile} ]; then
        while IFS= read -r line || [ -n "$line" ]; do
          case "$line" in
            ''|'#'*) continue ;;
            *=*) ;;
            *) continue ;;
          esac
          key=${{line%%=*}}
          value=${{line#*=}}
          case "$value" in
            \"*\") value=${{value#\"}}; value=${{value%\"}} ;;
            \'*\') value=${{value#\'}}; value=${{value%\'}} ;;
          esac
          case "$key" in
            SATIKSME_WEB_TUNNEL_ENABLED)
              case "$value" in
                1|true|TRUE|yes|YES|on|ON) satiksme_tunnel_enabled="1" ;;
                *) satiksme_tunnel_enabled="0" ;;
              esac
              ;;
            SATIKSME_WEB_PUBLIC_BASE_URL)
              satiksme_tunnel_public_base_url="$value"
              ;;
          esac
        done < {satiksmeEnvFile}
      fi
      printf '%s\n' "$satiksme_tunnel_enabled"
      printf '__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID__\n'
      satiksme_tunnel_supervisor_pid=""
      if [ "$satiksme_tunnel_enabled" = "1" ] && [ -r {satiksmeTunnelSupervisorPidFile} ]; then
        satiksme_tunnel_supervisor_pid=$(sed -n '1p' {satiksmeTunnelSupervisorPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ "$satiksme_tunnel_enabled" = "1" ] && {{ [ -z "$satiksme_tunnel_supervisor_pid" ] || ! kill -0 "$satiksme_tunnel_supervisor_pid" >/dev/null 2>&1; }}; then
        satiksme_tunnel_supervisor_pid=$(scan_pid_by_target /data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-web-tunnel-service-loop)
      fi
      if [ -n "$satiksme_tunnel_supervisor_pid" ] && kill -0 "$satiksme_tunnel_supervisor_pid" >/dev/null 2>&1; then
        printf '%s\n' "$satiksme_tunnel_supervisor_pid"
      fi
      printf '__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PID__\n'
      satiksme_tunnel_pid=""
      if [ "$satiksme_tunnel_enabled" = "1" ] && [ -r {satiksmeTunnelPidFile} ]; then
        satiksme_tunnel_pid=$(sed -n '1p' {satiksmeTunnelPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ "$satiksme_tunnel_enabled" = "1" ] && {{ [ -z "$satiksme_tunnel_pid" ] || ! kill -0 "$satiksme_tunnel_pid" >/dev/null 2>&1; }}; then
        satiksme_tunnel_pid=$(scan_pid_by_target /data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared)
      fi
      if [ -n "$satiksme_tunnel_pid" ] && kill -0 "$satiksme_tunnel_pid" >/dev/null 2>&1; then
        printf '%s\n' "$satiksme_tunnel_pid"
      fi
      printf '__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL__\n'
      printf '%s\n' "$satiksme_tunnel_public_base_url"
      satiksme_public_root_code="000"
      satiksme_public_app_code="000"
      satiksme_tunnel_probe_available="0"
      satiksme_public_curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
      if [ "$satiksme_tunnel_enabled" = "1" ] && [ -n "$satiksme_tunnel_public_base_url" ] && [ -n "$satiksme_public_curl_spec" ]; then
        satiksme_tunnel_probe_available="1"
        satiksme_public_root_url=$(printf '%s' "$satiksme_tunnel_public_base_url" | sed 's#/*$##')
        satiksme_public_app_url="$satiksme_public_root_url/app"
        satiksme_public_root_code=$(probe_http_code "$satiksme_public_curl_spec" "$satiksme_public_root_url/" 8)
        satiksme_public_app_code=$(probe_http_code "$satiksme_public_curl_spec" "$satiksme_public_app_url" 8)
      fi
      printf '__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_ROOT_CODE__\n'
      printf '%s\n' "$satiksme_public_root_code"
      printf '__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_APP_CODE__\n'
      printf '%s\n' "$satiksme_public_app_code"
      printf '__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE__\n'
      printf '%s\n' "$satiksme_tunnel_probe_available"
      printf '__PIXEL_HEALTH_SATIKSME_BOT_HEARTBEAT__\n'
      if [ -f {satiksmeHeartbeatFile} ]; then
        cat {satiksmeHeartbeatFile} 2>/dev/null || true
      fi
      "###));
    }
    output.push_str(
        r###"
      "###,
    );
    if config.enabled("site_notifier")? {
        output.push_str(&format!(
            r###"
      printf '__PIXEL_HEALTH_SITE_NOTIFIER_PID__\n'
      notifier_pid=""
      if [ -r {notifierPidFile} ]; then
        notifier_pid=$(sed -n '1p' {notifierPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ -n "$notifier_pid" ] && kill -0 "$notifier_pid" >/dev/null 2>&1; then
        printf '%s\n' "$notifier_pid"
      fi
      printf '__PIXEL_HEALTH_SITE_NOTIFIER_HEARTBEAT__\n'
      if [ -f {notifierHeartbeatFile} ]; then
        cat {notifierHeartbeatFile} 2>/dev/null || true
      fi
      notifier_health_output=""
      set +e
      notifier_health_output=$(sh {notifierHealthScript} 2>&1)
      notifier_health_rc=$?
      set -e
      printf '__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_HEALTHY__\n'
      if [ "$notifier_health_rc" -eq 0 ]; then
        printf '1\n'
      else
        printf '0\n'
      fi
      printf '__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_REASON__\n'
      notifier_health_reason=$(printf '%s\n' "$notifier_health_output" | sed -n '1p' | tr -d '\r')
      case "$notifier_health_reason" in
        healthy:\ *) notifier_health_reason=${{notifier_health_reason#healthy: }} ;;
        unhealthy:\ *) notifier_health_reason=${{notifier_health_reason#unhealthy: }} ;;
        healthy:*) notifier_health_reason=${{notifier_health_reason#healthy:}} ;;
        unhealthy:*) notifier_health_reason=${{notifier_health_reason#unhealthy:}} ;;
      esac
      if [ -z "$notifier_health_reason" ]; then
        if [ "$notifier_health_rc" -eq 0 ]; then
          notifier_health_reason="ok"
        else
          notifier_health_reason="unknown"
        fi
      fi
      printf '%s\n' "$notifier_health_reason"
      "###
        ));
    }
    output.push_str(
        r###"
      "###,
    );
    if config.enabled("subscription_bot")? {
        output.push_str(&format!(r###"
      printf '__PIXEL_HEALTH_SUBSCRIPTION_BOT_PID__\n'
      subscription_pid=""
      if [ -r {subscriptionPidFile} ]; then
        subscription_pid=$(sed -n '1p' {subscriptionPidFile} 2>/dev/null | tr -d '\r')
      fi
      if [ -z "$subscription_pid" ] || ! kill -0 "$subscription_pid" >/dev/null 2>&1; then
        subscription_pid=$(
          ps -A 2>/dev/null | awk '(($NF=="subscription-bot") || index($NF,"subscription-bot.")==1) {{print $2; exit}}' | tr -d '\r'
        )
      fi
      if [ -n "$subscription_pid" ] && kill -0 "$subscription_pid" >/dev/null 2>&1; then
        printf '%s\n' "$subscription_pid"
      fi
      printf '__PIXEL_HEALTH_SUBSCRIPTION_BOT_HEARTBEAT__\n'
      if [ -f {subscriptionHeartbeatFile} ]; then
        cat {subscriptionHeartbeatFile} 2>/dev/null || true
      fi
      "###));
    }
    output.push_str(&format!(r###"
      management_report=""
      set +e
      management_report=$(PIXEL_MANAGEMENT_HEALTH_REPORT=1 sh /data/local/pixel-stack/bin/pixel-management-health.sh --report 2>/dev/null)
      management_health_rc=$?
      set -e
      printf '%s\n' "$management_report" | awk -v expected_enabled={expectedEnabled} -v health_rc="$management_health_rc" '
        BEGIN {{
        marker["vpn_health"] = "__PIXEL_HEALTH_VPN_HEALTH__"
        marker["vpn_enabled"] = "__PIXEL_HEALTH_VPN_ENABLED_EFFECTIVE__"
        marker["tailscaled_live"] = "__PIXEL_HEALTH_VPN_TAILSCALED_LIVE__"
        marker["tailscaled_sock"] = "__PIXEL_HEALTH_VPN_TAILSCALED_SOCK__"
        marker["tailnet_ipv4"] = "__PIXEL_HEALTH_VPN_TAILNET_IPV4__"
        marker["guard_chain_ipv4"] = "__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV4__"
        marker["guard_chain_ipv6"] = "__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV6__"
        marker["management_enabled"] = "__PIXEL_HEALTH_MANAGEMENT_ENABLED__"
        marker["management_healthy"] = "__PIXEL_HEALTH_MANAGEMENT_HEALTHY__"
        marker["management_reason"] = "__PIXEL_HEALTH_MANAGEMENT_REASON__"
        marker["management_auth_consistent"] = "__PIXEL_HEALTH_MANAGEMENT_AUTH_CONSISTENT__"
        marker["management_auth_warning_reason"] = "__PIXEL_HEALTH_MANAGEMENT_AUTH_WARNING_REASON__"
        marker["ssh_listener"] = "__PIXEL_HEALTH_MANAGEMENT_SSH_LISTENER__"
        marker["ssh_auth_mode"] = "__PIXEL_HEALTH_MANAGEMENT_SSH_AUTH_MODE__"
        marker["ssh_password_auth_requested"] = "__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED__"
        marker["ssh_password_auth_ready"] = "__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_READY__"
        marker["ssh_key_auth_requested"] = "__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_REQUESTED__"
        marker["ssh_key_auth_ready"] = "__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_READY__"
        marker["pm_path"] = "__PIXEL_HEALTH_MANAGEMENT_PM_PATH__"
        marker["am_path"] = "__PIXEL_HEALTH_MANAGEMENT_AM_PATH__"
        marker["logcat_path"] = "__PIXEL_HEALTH_MANAGEMENT_LOGCAT_PATH__"
        marker["wireless_debug_enabled"] = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_ENABLED__"
        marker["wireless_debug_tls_port"] = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT__"
        marker["wireless_debug_live"] = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE__"
        marker["wireless_debug_live_ports"] = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS__"
        marker["wireless_debug_healthy"] = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_HEALTHY__"
        marker["wireless_debug_reason"] = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_REASON__"
        marker["wifi_enabled"] = "__PIXEL_HEALTH_MANAGEMENT_WIFI_ENABLED__"
        marker["wifi_connected"] = "__PIXEL_HEALTH_MANAGEMENT_WIFI_CONNECTED__"
        marker["wifi_ipv4"] = "__PIXEL_HEALTH_MANAGEMENT_WIFI_IPV4__"
        marker["mobile_iface"] = "__PIXEL_HEALTH_MANAGEMENT_MOBILE_IFACE__"
        marker["mobile_ipv4"] = "__PIXEL_HEALTH_MANAGEMENT_MOBILE_IPV4__"
        marker["active_transport"] = "__PIXEL_HEALTH_MANAGEMENT_ACTIVE_TRANSPORT__"
        marker["public_ipv4_candidate"] = "__PIXEL_HEALTH_MANAGEMENT_PUBLIC_IPV4_CANDIDATE__"
        marker["network_fingerprint"] = "__PIXEL_HEALTH_MANAGEMENT_NETWORK_FINGERPRINT__"
        }}
        {{
          separator = index($0, "=")
          if (separator == 0) next
          key = substr($0, 1, separator - 1)
          if (key in marker && !(key in value)) value[key] = substr($0, separator + 1)
        }}
        END {{
          if (value["vpn_health"] == "") value["vpn_health"] = expected_enabled ? "0" : "1"
          if (value["management_enabled"] == "") value["management_enabled"] = expected_enabled ? "1" : "0"
          if (value["management_healthy"] == "") value["management_healthy"] = (value["management_enabled"] == "1" && health_rc != 0) ? "0" : "1"
          if (value["management_reason"] == "") value["management_reason"] = (value["management_enabled"] == "1") ? "unknown" : "disabled"
          for (key in marker) {{
            print marker[key]
            print value[key]
          }}
        }}
      '
      "###));
    if config.enabled("remote")? && remoteEnabled != 0 {
        output.push_str(&format!(r###"
      remote_doh_tokenized_code="000"
      remote_doh_bare_code="000"
      remote_identity_inject_code="000"
      remote_public_base={remotePublicBaseUrl}
      remote_public_root_code="000"
      remote_public_probe_available="0"
      remote_public_doh_tokenized_code="000"
      remote_public_doh_bare_code="000"
      remote_public_identity_inject_code="000"
      remote_doh_curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
      if [ {dohEnabled} -eq 1 ] && [ -n "$remote_doh_curl_spec" ]; then
        remote_doh_mode={dohEndpointMode}
        remote_doh_token={dohPathToken}
        remote_doh_base="https://127.0.0.1:{httpsPort}"
        case "$remote_doh_mode" in
          tokenized|dual)
            if [ -n "$remote_doh_token" ]; then
              remote_doh_tokenized_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_doh_base/$remote_doh_token/dns-query" 4)
            fi
            ;;
        esac
        remote_doh_bare_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_doh_base/dns-query" 4)
      fi
      if [ -n "$remote_doh_curl_spec" ]; then
        remote_doh_mode={dohEndpointMode}
        remote_doh_base="https://127.0.0.1:{httpsPort}"
        case "$remote_doh_mode" in
          tokenized|dual)
            remote_identity_inject_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_doh_base/pixel-stack/identity/inject.js" 4)
            ;;
        esac
      fi
      if [ {remoteEnabled} -eq 1 ] && [ -n "$remote_doh_curl_spec" ] && [ -n "$remote_public_base" ]; then
        remote_public_probe_available="1"
        remote_public_root_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_public_base/" 8 "{hostname}" "{httpsPort}" "127.0.0.1")
        if [ {dohEnabled} -eq 1 ]; then
          remote_doh_mode={dohEndpointMode}
          remote_doh_token={dohPathToken}
          case "$remote_doh_mode" in
            tokenized|dual)
              if [ -n "$remote_doh_token" ]; then
                remote_public_doh_tokenized_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_public_base/$remote_doh_token/dns-query" 8 "{hostname}" "{httpsPort}" "127.0.0.1")
                remote_public_identity_inject_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_public_base/pixel-stack/identity/inject.js" 8 "{hostname}" "{httpsPort}" "127.0.0.1")
              fi
              ;;
          esac
          remote_public_doh_bare_code=$(probe_http_code "$remote_doh_curl_spec" "$remote_public_base/dns-query" 8 "{hostname}" "{httpsPort}" "127.0.0.1")
        fi
      fi
      printf '__PIXEL_HEALTH_REMOTE_DOH_TOKENIZED_CODE__\n'
      printf '%s\n' "$remote_doh_tokenized_code"
      printf '__PIXEL_HEALTH_REMOTE_DOH_BARE_CODE__\n'
      printf '%s\n' "$remote_doh_bare_code"
      printf '__PIXEL_HEALTH_REMOTE_IDENTITY_INJECT_CODE__\n'
      printf '%s\n' "$remote_identity_inject_code"
      printf '__PIXEL_HEALTH_REMOTE_PUBLIC_BASE_URL__\n'
      printf '%s\n' "$remote_public_base"
      printf '__PIXEL_HEALTH_REMOTE_PUBLIC_ROOT_CODE__\n'
      printf '%s\n' "$remote_public_root_code"
      printf '__PIXEL_HEALTH_REMOTE_PUBLIC_PROBE_AVAILABLE__\n'
      printf '%s\n' "$remote_public_probe_available"
      printf '__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_TOKENIZED_CODE__\n'
      printf '%s\n' "$remote_public_doh_tokenized_code"
      printf '__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_BARE_CODE__\n'
      printf '%s\n' "$remote_public_doh_bare_code"
      printf '__PIXEL_HEALTH_REMOTE_PUBLIC_IDENTITY_INJECT_CODE__\n'
      printf '%s\n' "$remote_public_identity_inject_code"
      "###));
    }
    output.push_str(
        r###"
      printf '__PIXEL_HEALTH_DONE__\n'
      "###,
    );
    Ok(trim_indent(&output))
}
fn trim_indent(value: &str) -> String {
    let normalized = value.replace("\r\n", "\n").replace('\r', "\n");
    let mut lines: Vec<&str> = normalized.split('\n').collect();
    if lines.first().is_some_and(|s| trim(s).is_empty()) {
        lines.remove(0);
    }
    if lines.last().is_some_and(|s| trim(s).is_empty()) {
        lines.pop();
    }
    let indent = lines
        .iter()
        .filter(|s| !trim(s).is_empty())
        .map(|s| s.chars().take_while(|c| whitespace(*c)).count())
        .min()
        .unwrap_or(0);
    lines
        .into_iter()
        .map(|s| {
            &s[s.char_indices()
                .nth(indent)
                .map(|(i, _)| i)
                .unwrap_or(s.len())..]
        })
        .collect::<Vec<_>>()
        .join("\n")
}
