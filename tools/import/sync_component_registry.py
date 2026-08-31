#!/usr/bin/env python3
import argparse
import json
import pathlib
import re
import sys

try:
    import yaml
except Exception as exc:
    print(f"ERROR: missing PyYAML: {exc}", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]
registry_path = ROOT / "orchestrator/modules/registry/modules.yaml"
asset_path = ROOT / "orchestrator/android-orchestrator/app/src/main/assets/runtime/component-registry.json"

def as_command(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def render_registry():
    registry = yaml.safe_load(registry_path.read_text(encoding="utf-8")) or {}
    modules = registry.get("modules", [])
    if not isinstance(modules, list) or not modules:
        raise ValueError("module registry must contain at least one module")
    components = []
    seen = set()
    for module in modules:
        component_id = str(module["component_id"])
        if not re.fullmatch(r"[a-z0-9_]+", component_id) or component_id in seen:
            raise ValueError(f"invalid or duplicate component id: {component_id!r}")
        seen.add(component_id)
        commands = {
            "startCommand": as_command(module["start_command"]),
            "stopCommand": as_command(module["stop_command"]),
            "healthCommand": as_command(module["health_command"]),
        }
        if any(not command.strip() for command in commands.values()):
            raise ValueError(f"component commands must not be empty: {component_id}")
        components.append({"id": component_id, **commands})
    return json.dumps({"schema": 1, "components": components}, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when the checked-in Android asset differs from the module registry",
    )
    args = parser.parse_args()
    rendered = render_registry()
    if args.check:
        if not asset_path.is_file():
            print(f"ERROR: missing generated component registry: {asset_path}", file=sys.stderr)
            return 1
        if asset_path.read_text(encoding="utf-8") != rendered:
            print(
                "ERROR: generated component registry is stale; run "
                "tools/import/sync_component_registry.py",
                file=sys.stderr,
            )
            return 1
        print(f"component registry is current: {asset_path}")
        return 0
    asset_path.parent.mkdir(parents=True, exist_ok=True)
    asset_path.write_text(rendered, encoding="utf-8")
    print(f"synced {asset_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, TypeError, ValueError, yaml.YAMLError) as exc:
        print(f"ERROR: invalid module registry: {exc}", file=sys.stderr)
        raise SystemExit(1)
