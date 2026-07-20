# Command Reference

Generated from source files by `scripts/docs/generate_command_reference.sh`.

## Android Deployment Scripts (scripts/android)

### `scripts/android/build_orchestrator_apk.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--help`
- `--print-provenance`
- `--profile`

### `scripts/android/deploy_orchestrator_apk.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--acme-token-file`
- `--action`
- `--admin-password-file`
- `--component`
- `--component-release-dir`
- `--config-file`
- `--ddns-token-file`
- `--device`
- `--dry-run`
- `--es`
- `--ez`
- `--help`
- `--install-apk`
- `--ipinfo-lite-token-file`
- `--profile`
- `--remote-healthcheck`
- `--remote-healthcheck-debug`
- `--runtime-bundle-dir`
- `--satiksme-bot-env-file`
- `--scope`
- `--site-notifier-env-file`
- `--skip-build`
- `--sqlite`
- `--ssh-host`
- `--ssh-password-hash-file`
- `--ssh-port`
- `--ssh-public-key`
- `--subscription-bot-env-file`
- `--train-bot-env-file`
- `--transport`
- `--vpn-auth-key-file`

### `scripts/android/package_component_release.sh`

**Usage snippets**

```text
Usage: $(basename "$0") --component NAME --artifact FILE [--artifact-id ID] [--file-name NAME] [--release-id VALUE] [--out-dir DIR] [--full]
```

**Long options found in source**

- `--action`
- `--artifact`
- `--artifact-id`
- `--component`
- `--component-release-dir`
- `--fast`
- `--file-name`
- `--full`
- `--help`
- `--out-dir`
- `--reflink`
- `--release-id`
- `--strict`
- `--timings-file`

### `scripts/android/package_dns_component_release.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--action`
- `--component`
- `--component-release-dir`
- `--fast`
- `--full`
- `--help`
- `--out-dir`
- `--reflink`
- `--release-id`
- `--reuse-rootfs-sha256`
- `--reuse-rootfs-size`
- `--rootfs-tarball`
- `--strict`
- `--timings-file`

### `scripts/android/package_runtime_bundle.sh`

Fast packaging should only resolve artifacts that the caller selected.  The

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--action`
- `--dropbear-artifact-dir`
- `--fast`
- `--full`
- `--help`
- `--include-workloads`
- `--manifest-version`
- `--out-dir`
- `--platform-only`
- `--print-inputs`
- `--reflink`
- `--rootfs-tarball`
- `--runtime-bundle-dir`
- `--satiksme-bot-bundle`
- `--site-notifier-bundle`
- `--strict`
- `--subscription-bot-bundle`
- `--tailscale-bundle`
- `--timings-file`
- `--train-bot-bundle`

### `scripts/android/pixel_redeploy.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--acme-token-file`
- `--action`
- `--admin-password-file`
- `--changed-paths-file`
- `--component`
- `--component-release-dir`
- `--config-file`
- `--ddns-token-file`
- `--destructive-e2e`
- `--device`
- `--full`
- `--help`
- `--install-apk`
- `--ipinfo-lite-token-file`
- `--manifest-version`
- `--max-time`
- `--mirror-root`
- `--mode`
- `--out-dir`
- `--package-only`
- `--platform-only`
- `--profile`
- `--release-id`
- `--remote-healthcheck`
- `--remote-healthcheck-debug`
- `--remote-root`
- `--rootfs-tarball`
- `--runtime-bundle-dir`
- `--satiksme-bot-bundle`
- `--satiksme-bot-env-file`
- `--scope`
- `--site-notifier-bundle`
- `--site-notifier-env-file`
- `--skip-build`
- `--ssh-host`
- `--ssh-password-hash-file`
- `--ssh-port`
- `--ssh-public-key`
- `--subscription-bot-bundle`
- `--subscription-bot-env-file`
- `--train-bot-bundle`
- `--train-bot-env-file`
- `--transport`
- `--validate-only`
- `--vpn-auth-key-file`

### `scripts/android/release_runtime_artifacts.sh`

**Usage snippets**

_No `Usage:` lines detected in source._

**Long options found in source**

- `--action`
- `--runtime-bundle-dir`

### `scripts/android/runtime_asset_freshness.sh`

**Usage snippets**

```text
Usage: runtime_asset_freshness.sh [options]
```

**Long options found in source**

- `--device`
- `--help`
- `--print-specs`
- `--scope`
- `--ssh-host`
- `--ssh-port`
- `--timings-file`
- `--transport`

### `scripts/android/sign_runtime_manifest.sh`

**Usage snippets**

```text
Usage: $(basename "$0") --manifest FILE --private-key-pem FILE [--out FILE]
```

**Long options found in source**

- `--help`
- `--manifest`
- `--out`
- `--private-key-pem`
- `--timings-file`

## Operations Scripts (scripts/ops)

### `scripts/ops/adguardhome_migration_snapshot.sh`

**Usage snippets**

```text
Usage: adguardhome_migration_snapshot.sh [options]
```

**Long options found in source**

- `--adb-serial`
- `--arg`
- `--doh-url`
- `--help`
- `--output-dir`
- `--slurpfile`

### `scripts/ops/build_dropbear_android_prebuilt.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--api-level`
- `--disable-lastlog`
- `--disable-obsolete-api`
- `--disable-shared`
- `--disable-syslog`
- `--disable-utmp`
- `--disable-utmpx`
- `--disable-werror`
- `--disable-zlib`
- `--enable-static`
- `--help`
- `--host`
- `--keep-work-dir`
- `--ndk-root`
- `--out-dir`
- `--prefix`
- `--source-sha256`
- `--version`
- `--work-dir`

### `scripts/ops/build_tailscale_android_bundle.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--branch`
- `--depth`
- `--help`
- `--keep-work-dir`
- `--out-dir`
- `--version`
- `--work-dir`

### `scripts/ops/check_no_termux_dependency.sh`

Keep this check focused on active repository sources. Archived evidence and

**Usage snippets**

_No `Usage:` lines detected in source._

**Long options found in source**

- `--color`
- `--glob`

### `scripts/ops/doh-identity-control.sh`

**Usage snippets**

```text
Usage: doh-identity-control.sh [--adb-serial SERIAL] -- <identityctl args>
```

**Long options found in source**

- `--adb-serial`
- `--help`
- `--id`
- `--json`
- `--window`

### `scripts/ops/enforce_remote_admin_contract.sh`

**Usage snippets**

```text
Usage: enforce_remote_admin_contract.sh [options]
```

**Long options found in source**

- `--adb-serial`
- `--cidr`
- `--help`
- `--no-restart`
- `--password-file`
- `--username`

### `scripts/ops/hard-cutover-orchestrator-owners.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--es`
- `--help`
- `--remove-unmanaged-ssh-script`

### `scripts/ops/pixel-production-interference-report.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--arg`
- `--argjson`
- `--baseline-json`
- `--dot-port`
- `--help`
- `--host`
- `--https-port`
- `--output-dir`
- `--timeout`

### `scripts/ops/purge-legacy-ssh-runtime.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--help`
- `--no-restart-local`

### `scripts/ops/restart-stability-report.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--days`
- `--dns-log-file`
- `--help`
- `--json-out`
- `--logcat-file`
- `--markdown-out`
- `--now`
- `--output-dir`
- `--redeploy-log-glob`
- `--satiksme-log-file`
- `--site-notifier-log-file`
- `--ssh-log-file`
- `--timezone`
- `--train-loop-log-file`
- `--window-minutes`

### `scripts/ops/service-availability-report.sh`

**Usage snippets**

```text
Usage: service-availability-report.sh [options]
```

**Long options found in source**

- `--adb-connect`
- `--adb-serial`
- `--arg`
- `--benchmark-requests`
- `--config-file`
- `--dns-domain`
- `--doh-endpoint-mode`
- `--doh-token`
- `--doh-url`
- `--dot-port`
- `--expect-lan-client-ip`
- `--expect-router-lan-ip`
- `--expect-router-public-ip`
- `--fqdn`
- `--help`
- `--host`
- `--https-port`
- `--include-internal-querylog`
- `--internal-probe-domains`
- `--internal-querylog-clients`
- `--json-out`
- `--lan-gateway-ip`
- `--max-lan-gateway-share-pct`
- `--max-router-lan-doh-count`
- `--max-time`
- `--querylog-json-file`
- `--querylog-limit`
- `--require-lan-visible`
- `--require-remote`
- `--rooted-pixel-checks`
- `--skip-network-checks`
- `--skip-root-checks`
- `--ssh-port`
- `--timeout`

### `scripts/ops/ssh-performance-report.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--arg`
- `--argjson`
- `--baseline-json`
- `--help`
- `--host`
- `--local-host`
- `--output-dir`
- `--password-env`
- `--ping-count`
- `--port`
- `--samples`
- `--timeout`
- `--user`

### `scripts/ops/vpn-ssh-memory-report.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--arg`
- `--argjson`
- `--enforce-thresholds`
- `--help`
- `--output-dir`
- `--tailscaled-max-kb`
- `--total-max-kb`

### `scripts/ops/vpn_break_glass_ssh.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--comment`
- `--dport`
- `--duration-sec`
- `--help`
- `--ssh-port`

## Documentation Scripts (scripts/docs)

### `scripts/docs/check_stale_references.sh`

**Usage snippets**

_No `Usage:` lines detected in source._

**Long options found in source**

- `--color`

### `scripts/docs/generate_command_reference.sh`

**Usage snippets**

_No `Usage:` lines detected in source._

**Long options found in source**

- `--no-filename`
- `--no-line-number`
- `--only-matching`

### `scripts/docs/generate_config_reference.sh`

**Usage snippets**

_No `Usage:` lines detected in source._

**Long options found in source**

- _(none detected)_

