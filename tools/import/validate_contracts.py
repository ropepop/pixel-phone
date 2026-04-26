#!/usr/bin/env python3
import json
import pathlib
import sys

try:
    import yaml
except Exception as exc:  # pragma: no cover
    print(f"ERROR: missing PyYAML: {exc}", file=sys.stderr)
    sys.exit(2)

try:
    from jsonschema import Draft202012Validator
except Exception as exc:  # pragma: no cover
    print(f"ERROR: missing jsonschema: {exc}", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]

schema_path = ROOT / "orchestrator/modules/schemas/module-manifest.v1.schema.json"
registry_path = ROOT / "orchestrator/modules/registry/modules.yaml"
component_registry_path = (
    ROOT
    / "orchestrator/android-orchestrator/app/src/main/assets/runtime/component-registry.json"
)
orchestrator_manifest_path = ROOT / "orchestrator/module.yaml"
train_bot_manifest_path = ROOT / "workloads/train-bot/module.yaml"
site_notifier_manifest_path = ROOT / "workloads/site-notifications/module.yaml"

manifest_roots = [
    ROOT / "orchestrator",
    ROOT / "workloads",
    ROOT / "automation",
    ROOT / "infra",
]
manifest_paths = sorted(
    {
        path
        for root in manifest_roots
        if root.exists()
        for path in root.rglob("module.yaml")
    }
)


def normalize_command(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return ""
    return str(value)


def find_component(manifest_doc, component_id):
    components = manifest_doc.get("components", []) if isinstance(manifest_doc, dict) else []
    for item in components:
        if isinstance(item, dict) and item.get("id") == component_id:
            return item
    return None


errors = []

if not manifest_paths:
    errors.append("no module.yaml files discovered under orchestrator/workloads/automation/infra")

if not schema_path.exists():
    errors.append(f"missing schema: {schema_path}")
else:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)

    for mp in manifest_paths:
        if not mp.exists():
            errors.append(f"missing manifest: {mp}")
            continue
        doc = yaml.safe_load(mp.read_text(encoding="utf-8"))
        for err in validator.iter_errors(doc):
            loc = ".".join([str(p) for p in err.path]) or "<root>"
            errors.append(f"manifest validation failed: {mp} [{loc}] {err.message}")

registry_modules = []
registry_by_component = {}
if not registry_path.exists():
    errors.append(f"missing registry: {registry_path}")
else:
    registry = yaml.safe_load(registry_path.read_text(encoding="utf-8")) or {}
    modules = registry.get("modules", [])
    if not isinstance(modules, list) or not modules:
        errors.append("registry.modules must be a non-empty list")
    else:
        ids = []
        component_ids = []
        for item in modules:
            if not isinstance(item, dict):
                errors.append("registry entry must be an object")
                continue
            mid = item.get("id")
            cid = item.get("component_id")
            for field in ("start_command", "stop_command", "health_command"):
                if item.get(field) in (None, ""):
                    errors.append(f"registry entry '{mid}' missing {field}")
            if not mid:
                errors.append("registry entry missing id")
            else:
                ids.append(mid)
            if not cid:
                errors.append(f"registry entry '{mid}' missing component_id")
            else:
                component_ids.append(cid)
                registry_by_component[cid] = item
            registry_modules.append(item)
        if len(ids) != len(set(ids)):
            errors.append("registry contains duplicate module ids")
        if len(component_ids) != len(set(component_ids)):
            errors.append("registry contains duplicate component_id values")

required_components = {"dns", "ssh", "ddns", "remote", "train_bot", "site_notifier"}
if registry_modules:
    observed = {m.get("component_id") for m in registry_modules if isinstance(m, dict)}
    missing = sorted(required_components - observed)
    if missing:
        errors.append(f"registry missing required component_id entries: {', '.join(missing)}")

if component_registry_path.exists() and registry_modules:
    try:
        component_registry_doc = json.loads(component_registry_path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"failed to parse component registry JSON: {component_registry_path} ({exc})")
        component_registry_doc = {}

    components = component_registry_doc.get("components", [])
    if not isinstance(components, list):
        errors.append("component-registry.json 'components' must be a list")
    else:
        component_registry_by_id = {}
        for comp in components:
            if not isinstance(comp, dict):
                errors.append("component-registry.json entry must be an object")
                continue
            cid = comp.get("id")
            if not cid:
                errors.append("component-registry.json entry missing id")
                continue
            if cid in component_registry_by_id:
                errors.append(f"component-registry.json contains duplicate id '{cid}'")
                continue
            component_registry_by_id[cid] = comp

        for cid, reg in registry_by_component.items():
            comp = component_registry_by_id.get(cid)
            if comp is None:
                errors.append(f"component-registry.json missing component id '{cid}'")
                continue

            expected_start = normalize_command(reg.get("start_command"))
            expected_stop = normalize_command(reg.get("stop_command"))
            expected_health = normalize_command(reg.get("health_command"))

            if comp.get("startCommand") != expected_start:
                errors.append(
                    f"component-registry mismatch for '{cid}' startCommand: expected '{expected_start}' got '{comp.get('startCommand')}'"
                )
            if comp.get("stopCommand") != expected_stop:
                errors.append(
                    f"component-registry mismatch for '{cid}' stopCommand: expected '{expected_stop}' got '{comp.get('stopCommand')}'"
                )
            if comp.get("healthCommand") != expected_health:
                errors.append(
                    f"component-registry mismatch for '{cid}' healthCommand: expected '{expected_health}' got '{comp.get('healthCommand')}'"
                )

        extra = sorted(set(component_registry_by_id) - set(registry_by_component))
        if extra:
            errors.append(f"component-registry.json has unknown component ids: {', '.join(extra)}")
else:
    if not component_registry_path.exists():
        errors.append(f"missing component registry JSON: {component_registry_path}")

if orchestrator_manifest_path.exists() and registry_modules:
    orchestrator_manifest = yaml.safe_load(orchestrator_manifest_path.read_text(encoding="utf-8")) or {}
    shared_components = ["dns", "ssh", "vpn", "ddns", "remote", "train_bot", "site_notifier"]
    for cid in shared_components:
        reg = registry_by_component.get(cid)
        if reg is None:
            errors.append(f"registry missing shared component '{cid}'")
            continue
        manifest_comp = find_component(orchestrator_manifest, cid)
        if manifest_comp is None:
            errors.append(f"orchestrator/module.yaml missing component '{cid}'")
            continue

        expected_start = normalize_command(reg.get("start_command"))
        expected_stop = normalize_command(reg.get("stop_command"))
        expected_health = normalize_command(reg.get("health_command"))

        actual_start = normalize_command(manifest_comp.get("start"))
        actual_stop = normalize_command(manifest_comp.get("stop"))
        actual_health = normalize_command(manifest_comp.get("health"))

        if actual_start != expected_start:
            errors.append(
                f"orchestrator/module.yaml mismatch for '{cid}' start: expected '{expected_start}' got '{actual_start}'"
            )
        if actual_stop != expected_stop:
            errors.append(
                f"orchestrator/module.yaml mismatch for '{cid}' stop: expected '{expected_stop}' got '{actual_stop}'"
            )
        if actual_health != expected_health:
            errors.append(
                f"orchestrator/module.yaml mismatch for '{cid}' health: expected '{expected_health}' got '{actual_health}'"
            )

if train_bot_manifest_path.exists() and registry_modules:
    train_manifest = yaml.safe_load(train_bot_manifest_path.read_text(encoding="utf-8")) or {}
    train_comp = find_component(train_manifest, "train_bot")
    registry_train = registry_by_component.get("train_bot")
    if train_comp is None:
        errors.append("workloads/train-bot/module.yaml missing component 'train_bot'")
    elif registry_train is None:
        errors.append("registry missing component 'train_bot'")
    else:
        expected = normalize_command(registry_train.get("health_command"))
        actual = normalize_command(train_comp.get("health"))
        if actual != expected:
            errors.append(
                f"workloads/train-bot/module.yaml health mismatch: expected '{expected}' got '{actual}'"
            )

if site_notifier_manifest_path.exists() and registry_modules:
    site_manifest = yaml.safe_load(site_notifier_manifest_path.read_text(encoding="utf-8")) or {}
    site_comp = find_component(site_manifest, "site_notifier")
    registry_site = registry_by_component.get("site_notifier")
    if site_comp is None:
        errors.append("workloads/site-notifications/module.yaml missing component 'site_notifier'")
    elif registry_site is None:
        errors.append("registry missing component 'site_notifier'")
    else:
        expected = normalize_command(registry_site.get("health_command"))
        actual = normalize_command(site_comp.get("health"))
        if actual != expected:
            errors.append(
                f"workloads/site-notifications/module.yaml health mismatch: expected '{expected}' got '{actual}'"
            )

if errors:
    for err in errors:
        print(f"ERROR: {err}", file=sys.stderr)
    sys.exit(1)

print("contract validation passed")
