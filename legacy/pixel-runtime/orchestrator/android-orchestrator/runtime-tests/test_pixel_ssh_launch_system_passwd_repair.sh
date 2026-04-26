#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SSH_LAUNCH_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-launch.sh"

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

fake_bin_dir="${tmpdir}/fake-bin"
ssh_root="${tmpdir}/ssh"
system_passwd_file="${tmpdir}/system-passwd"
launch_script="${tmpdir}/pixel-ssh-launch.sh"
mount_log="${tmpdir}/mount.log"
dropbear_log="${tmpdir}/dropbear.log"
auth_keys_dir="${tmpdir}/pixel-ssh-auth"

mkdir -p \
  "${fake_bin_dir}" \
  "${ssh_root}/bin" \
  "${ssh_root}/conf" \
  "${ssh_root}/etc/dropbear" \
  "${ssh_root}/home/root/.ssh" \
  "${ssh_root}/run" \
  "${ssh_root}/logs"

cat > "${ssh_root}/conf/dropbear.env" <<EOF
SSH_PASSWORD_AUTH=1
SSH_ALLOW_KEY_AUTH=0
SSH_PORT=2222
SSH_BIND_ADDRESS=0.0.0.0
EOF

printf '%s\n' "root:\$6\$healthyhash:0:0:root:${ssh_root}/home/root:/system/bin/sh" > "${ssh_root}/etc/passwd"
: > "${system_passwd_file}"

cat > "${ssh_root}/bin/dropbearkey" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
out=""
while (($# > 0)); do
  if [[ "$1" == "-f" ]]; then
    shift
    out="${1:-}"
  fi
  shift || true
done
if [[ -z "${out}" ]]; then
  echo "missing -f output path" >&2
  exit 1
fi
printf 'host-key\n' > "${out}"
EOF
chmod +x "${ssh_root}/bin/dropbearkey"

cat > "${ssh_root}/bin/dropbear" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "\$*" > "${dropbear_log}"
exit 0
EOF
chmod +x "${ssh_root}/bin/dropbear"

cat > "${fake_bin_dir}/mount" <<EOF
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "\$*" >> "${mount_log}"
exit 1
EOF
chmod +x "${fake_bin_dir}/mount"

cat > "${fake_bin_dir}/umount" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${fake_bin_dir}/umount"

cp "${SSH_LAUNCH_TEMPLATE}" "${launch_script}"
perl -0pi -e "s#/system/etc/passwd#${system_passwd_file//\//\\/}#g" "${launch_script}"
perl -0pi -e "s#/debug_ramdisk/pixel-ssh-auth#${auth_keys_dir//\//\\/}#g" "${launch_script}"
chmod +x "${launch_script}"

PATH="${fake_bin_dir}:${PATH}" PIXEL_SSH_ROOT="${ssh_root}" bash "${launch_script}"

expected_line="$(sed -n '1p' "${ssh_root}/etc/passwd")"
system_line="$(sed -n '1p' "${system_passwd_file}")"
[[ "${system_line}" == "${expected_line}" ]] || {
  echo "FAIL: system passwd repair fallback did not copy runtime passwd" >&2
  exit 1
}

auth_mode="$(sed -n '1p' "${ssh_root}/run/auth-mode")"
[[ "${auth_mode}" == "password_only" ]] || {
  echo "FAIL: expected password_only auth mode, got ${auth_mode}" >&2
  exit 1
}

[[ -s "${mount_log}" ]] || {
  echo "FAIL: expected mount attempt before repair fallback" >&2
  exit 1
}

[[ -s "${dropbear_log}" ]] || {
  echo "FAIL: fake dropbear was not launched" >&2
  exit 1
}

echo "PASS: pixel-ssh-launch repairs system passwd drift when bind mount fails"
