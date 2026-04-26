#!/usr/bin/env python3
import json
import pathlib
import sys

try:
    import yaml
except Exception as exc:
    print(f"ERROR: missing PyYAML: {exc}", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]
registry_path = ROOT / "orchestrator/modules/registry/modules.yaml"
asset_path = ROOT / "orchestrator/android-orchestrator/app/src/main/assets/runtime/component-registry.json"

registry = yaml.safe_load(registry_path.read_text(encoding="utf-8")) or {}
modules = registry.get("modules", [])
components = []


def as_command(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


for module in modules:
    components.append(
        {
            "id": module["component_id"],
            "startCommand": as_command(module["start_command"]),
            "stopCommand": as_command(module["stop_command"]),
            "healthCommand": as_command(module["health_command"]),
        }
    )

doc = {"schema": 1, "components": components}
asset_path.parent.mkdir(parents=True, exist_ok=True)
asset_path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
print(f"synced {asset_path}")
