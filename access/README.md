# Pixel access

This directory is intentionally tracked in the **private**
`ropepop/pixel-phone-canonical` repository, at the owner's request. It contains
working private credentials. Keep it out of public mirrors and shared artifacts.
The SSH identity is dedicated to this Pixel. The ADB identity is the Mac identity
the Pixel had already approved; existing phone approvals were preserved.

## After cloning

From the repository root, restore private-file permissions (Git does not preserve
them):

```sh
chmod 700 access
chmod 600 access/pixel_ssh access/adbkey
```

Connect this computer to the existing Tailscale network. The Pixel is
`node.tail9345a.ts.net`, currently `100.76.50.43` (Pixel 9a).

## SSH

SSH is key-only and available through Tailscale on port 2222:

```sh
ssh -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=access/known_hosts \
  -i access/pixel_ssh -p 2222 root@100.76.50.43
```

The host key in `known_hosts` was verified directly through authenticated Pixel
ADB. Do not bypass a host-key mismatch. Repository management commands pick up
this folder's SSH identity and host key automatically:

```sh
./tools/pixel/check_ssh_ready.sh --ssh-host 100.76.50.43
./tools/pixel/redeploy.sh mirror-audit --transport ssh --ssh-host 100.76.50.43
```

## ADB

ADB on port 5555 accepts connections through Tailscale and the Pixel's local
Wi-Fi interface. Android still requires an approved host key. On a new computer,
set `ADB_VENDOR_KEYS` before starting its ADB server:

```sh
export ADB_VENDOR_KEYS="$PWD/access/adbkey"
adb start-server
adb connect 100.76.50.43:5555
adb -s 100.76.50.43:5555 shell
```

If an ADB server was already running before setting that variable, restart that
computer's ADB server first so it loads the key. For local Wi-Fi, replace the
address with the Pixel's current Wi-Fi address; it was `192.168.31.25` when
verified. Find its current address through SSH with `ip -4 addr show wlan0`.

`adb_allowed_connection_time=0` keeps remembered approvals from expiring due to
inactivity. The boot hook preserves this setting. Authentication remains enabled
(`ro.adb.secure=1`); manually revoking approvals or resetting the phone still
removes access. No separate dynamic wireless-debug service is required.

## Files and ownership

- `pixel_ssh` / `pixel_ssh.pub`: dedicated SSH private/public key pair.
- `adbkey` / `adbkey.pub`: existing approved ADB private/public key pair.
- `known_hosts`: verified Pixel SSH host identity.
- `../orchestrator/templates/magisk-service.d/99-wireless-adb.sh`: source of the
  installed one-shot ADB boot hook. Android init owns the running ADB service;
  the orchestrator owns the existing SSH and Tailscale services.

A full phone reboot has not been used as an acceptance test. Boot-hook replay,
authenticated access and source/device equality are checked separately.
