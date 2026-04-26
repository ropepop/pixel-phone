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

### `scripts/android/package_runtime_bundle.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--dropbear-artifact-dir`
- `--help`
- `--manifest-version`
- `--out-dir`
- `--print-inputs`
- `--rootfs-tarball`
- `--site-notifier-bundle`
- `--tailscale-bundle`
- `--train-bot-bundle`

### `scripts/android/pixel_redeploy.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--destructive-e2e`
- `--device`
- `--help`
- `--mode`
- `--scope`
- `--skip-build`

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
- `--config-file`
- `--ddns-token-file`
- `--device`
- `--es`
- `--help`
- `--runtime-bundle-dir`
- `--site-notifier-env-file`
- `--skip-build`
- `--ssh-password-hash-file`
- `--ssh-public-key`
- `--train-bot-env-file`
- `--vpn-auth-key-file`

### `scripts/android/release_runtime_artifacts.sh`

**Usage snippets**

```text
No options (deprecated script that exits with migration guidance)
```

**Long options found in source**

- _(none detected)_

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

## Operations Scripts (scripts/ops)

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

Keep this check focused on deprecated deployment entrypoints and stale path

**Usage snippets**

_No `Usage:` lines detected in source._

**Long options found in source**

- `--color`
- `--glob`

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
- `--help`
- `--host`
- `--output-dir`
- `--timeout`

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

### `scripts/ops/purge-legacy-ssh-runtime.sh`

**Usage snippets**

```text
Usage: $(basename "$0") [options]
```

**Long options found in source**

- `--adb-serial`
- `--help`
- `--no-restart-local`

### `scripts/ops/service-availability-report.sh`

**Usage snippets**

```text
Usage: service-availability-report.sh [options]
```

**Long options found in source**

- `--adb-connect`
- `--adb-serial`
- `--benchmark-requests`
- `--config-file`
- `--dns-domain`
- `--doh-endpoint-mode`
- `--doh-token`
- `--doh-url`
- `--expect-lan-client-ip`
- `--expect-router-lan-ip`
- `--expect-router-public-ip`
- `--fqdn`
- `--help`
- `--host`
- `--include-internal-querylog`
- `--internal-probe-domains`
- `--internal-querylog-clients`
- `--json-out`
- `--lan-gateway-ip`
- `--max-lan-gateway-share-pct`
- `--max-router-lan-doh-count`
- `--querylog-json-file`
- `--querylog-limit`
- `--require-lan-visible`
- `--require-remote`
- `--skip-root-checks`
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
- `--dport`
- `--duration-sec`
- `--help`
- `--ssh-port`

## Documentation Scripts (scripts/docs)

### `scripts/docs/check_stale_references.sh`

Keep this focused on stale pre-orchestrator path conventions.

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
