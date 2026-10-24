#!/usr/bin/env python3
"""Checks emitted runtime configs against sing-box's own JSON schema.

The point is catching a misspelled or invented rule field, which config-level self-tests cannot see.
Optional and offline by default: pass the path to a schema.json fetched from
  https://raw.githubusercontent.com/SagerNet/sing-box/v<version>/docs/schema.json
as SINGBOX_SCHEMA, or via --schema. Without it this script does nothing and exits 0.
"""
import json
import os
import sys


def load_props(schema, name, action):
    """Allowed keys for a rule, merged with the action variant that the rule uses."""
    template = schema["$defs"][name]["oneOf"][0]
    props = {}
    for part in template.get("allOf", []):
        ref = part.get("$ref")
        if not ref:
            props.update(part.get("properties", {}))
            continue
        for branch in schema["$defs"][ref.split("/")[-1]]["oneOf"]:
            branch_props = branch.get("properties", {})
            if branch_props.get("action", {}).get("const") == action:
                props.update(branch_props)
    props.update(template.get("properties", {}))
    return props


def check_rule(schema, rule, kind, where):
    errors = []
    action = rule.get("action")
    name = "Rule" if kind == "route" else "DNSRule"
    if action is None:
        errors.append(f"{where}: missing action")
        return errors
    props = load_props(schema, name, action)
    if "action" not in props:
        errors.append(f"{where}: action '{action}' is not valid for {name}")
        return errors
    for key in rule:
        if key not in props:
            errors.append(f"{where}: unknown field '{key}' for {name} action '{action}'")
            continue
        if props[key].get("type") == "boolean" and not isinstance(rule[key], bool):
            errors.append(f"{where}: '{key}' must be a boolean")
        if props[key].get("type") == "array" and not isinstance(rule[key], list):
            errors.append(f"{where}: '{key}' must be an array")
    if action == "route" and kind == "route" and "outbound" not in rule:
        errors.append(f"{where}: route action without outbound")
    if action == "route" and kind == "dns" and "server" not in rule:
        errors.append(f"{where}: dns route action without server")
    return errors


def check_config(schema, path):
    with open(path, encoding="utf-8") as handle:
        config = json.load(handle)
    errors = []
    for index, rule in enumerate(config.get("route", {}).get("rules", [])):
        errors += check_rule(schema, rule, "route", f"{path} route.rules[{index}]")
    for index, rule in enumerate(config.get("dns", {}).get("rules", []) or []):
        errors += check_rule(schema, rule, "dns", f"{path} dns.rules[{index}]")
    for name in ("dns", "route", "inbounds", "outbounds", "experimental", "log"):
        if name not in config:
            errors.append(f"{path}: missing top-level '{name}'")
    return errors


def main():
    args = sys.argv[1:]
    schema_path = os.environ.get("SINGBOX_SCHEMA", "")
    if args[:1] == ["--schema"]:
        schema_path = args[1]
        args = args[2:]
    if not schema_path:
        print("SINGBOX_SCHEMA not set: skipping sing-box schema check")
        return 0
    with open(schema_path, encoding="utf-8") as handle:
        schema = json.load(handle)
    failures = []
    for path in args:
        failures += check_config(schema, path)
    for failure in failures:
        print("FAIL: " + failure, file=sys.stderr)
    if failures:
        return 1
    print(f"sing-box schema check passed ({len(args)} configs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
