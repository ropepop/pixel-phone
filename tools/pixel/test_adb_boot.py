#!/usr/bin/env python3
"""Exercise boot ordering and repeat/failure behavior without a phone."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "orchestrator/templates/magisk-service.d/99-wireless-adb.sh"
MOCK = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ["ADB_BOOT_STATE"])
s = json.loads(p.read_text())
name, args = Path(sys.argv[0]).name, sys.argv[1:]
s["calls"].append([name, *args])
rc = 0
if name == "getprop":
 print(s["props"].get(args[0], ""))
elif name == "setprop":
 s["props"][args[0]] = args[1]
elif name == "settings":
 if args[0] == "get": print(s.get(args[2], "0"))
 else: s[args[2]] = args[3]
elif name in ("iptables", "ip6tables"):
 args = args[2:]
 if name == s.get("fail"):
  rc = 1
 else:
  chains = s.setdefault(name, {})
  op, chain = args[:2]
  rule = args[2:]
  if op == "-N":
   rc = int(chain in chains)
   chains.setdefault(chain, [])
  elif op == "-S": rc = int(chain not in chains)
  elif op == "-C": rc = int(rule not in chains.get(chain, []))
  elif op == "-A": chains.setdefault(chain, []).append(rule)
  elif op == "-I": chains.setdefault(chain, []).insert(0, rule[1:])
p.write_text(json.dumps(s))
sys.exit(rc)
'''


class BootTest(unittest.TestCase):
    def run_boot(self, *, secure="1", fail=None, repeat=False, expiry="0"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state.json"
            state.write_text(json.dumps({"calls": [], "fail": fail, "adb_allowed_connection_time": expiry, "props": {
                "ro.adb.secure": secure, "sys.boot_completed": "1",
                "persist.adb.tls_server.enable": "0", "service.adb.tcp.port": "5555",
                "init.svc.adbd": "running"}}))
            for name in ("getprop", "setprop", "settings", "iptables", "ip6tables"):
                path = root / name
                path.write_text(MOCK)
                path.chmod(0o755)
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", ADB_BOOT_STATE=str(state))
            result = subprocess.run(["sh", str(SCRIPT)], env=env, capture_output=True)
            if repeat:
                self.assertEqual(result.returncode, 0, result.stderr)
                result = subprocess.run(["sh", str(SCRIPT)], env=env, capture_output=True)
            return result.returncode, json.loads(state.read_text())

    def test_repeat_has_one_guard_and_no_daemon_restart(self):
        code, state = self.run_boot(repeat=True)
        self.assertEqual(code, 0)
        for family in ("iptables", "ip6tables"):
            self.assertEqual(state[family]["PIXEL_ADB_GUARD"], [["-i", "wlan0", "-j", "ACCEPT"], ["-i", "tailscale0", "-j", "ACCEPT"], ["-j", "DROP"]])
            self.assertEqual(len(state[family]["INPUT"]), 1)
        self.assertFalse(any(c[0] == "setprop" for c in state["calls"]))

    def test_expiry_disabled_once_without_disabling_authentication(self):
        code, state = self.run_boot(expiry="604800000", repeat=True)
        self.assertEqual(code, 0)
        self.assertEqual(state["adb_allowed_connection_time"], "0")
        writes = [c for c in state["calls"] if c[:2] == ["settings", "put"]]
        self.assertEqual(writes, [["settings", "put", "global", "adb_allowed_connection_time", "0"]])
        self.assertEqual(state["props"]["ro.adb.secure"], "1")

    def test_insecure_adb_and_firewall_failure_abort(self):
        for options in ({"secure": "0"}, {"fail": "ip6tables"}):
            code, state = self.run_boot(**options)
            self.assertNotEqual(code, 0)
            self.assertFalse(any(c[0] in ("setprop", "settings") for c in state["calls"]))


if __name__ == "__main__":
    unittest.main()
