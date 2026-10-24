#!/usr/bin/env python3
"""Merge the Tokyo REALITY node with the InstaBox-01 node into one fixed subscription.

01 only exposes an ephemeral Cloudflare quick tunnel plus a stable platform
HTTPS entry (port-published by InstaBox). We pin the platform entry and take
only the live Xray parameters (uuid / ws path) from 01's own subscription, so
the client subscription URL stays fixed while 01 keeps rotating its tunnel.

Failure policy: never publish a partial file. The previous aggregate stays in
place until a fresh one passes validation, then it is swapped in atomically.
"""
import json
import os
import sys
import urllib.request

UPSTREAM_SUB = "https://12443-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/sub"
FIXED_HOST = "12443-agqvov42zisdyuvnzmi23lrrw4.instabox.tech"
TOKYO_FILE = "/opt/subscription-merge/tokyo-seed.yaml"
OUT_DIR = "/opt/subscription-merge"
OUT_FILE = os.path.join(OUT_DIR, "subscription.json")
STATE_FILE = os.path.join(OUT_DIR, "state.json")
NODE_01 = "Instabox-US-01"


def fail(msg):
    print(f"[merge] FAIL {msg}", flush=True)
    return 1


def fetch(url):
    """Fetch upstream subscription text; one retry, short timeout."""
    last = None
    for _ in range(2):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "tokyo-sub-merge/1"})
            with urllib.request.urlopen(req, timeout=20) as resp:
                if resp.status != 200:
                    last = f"HTTP {resp.status}"
                    continue
                return resp.read().decode("utf-8", "replace")
        except Exception as exc:  # network, TLS, DNS
            last = repr(exc)
    raise RuntimeError(last)


def parse_doc(text):
    """Accept JSON (Tokyo style) or the simple Clash YAML our 01 generator emits."""
    try:
        doc = json.loads(text)
        if isinstance(doc, dict):
            return doc
    except ValueError:
        pass
    # Minimal YAML subset: only the flat proxy blocks tunnel-manager.py renders.
    doc = {"proxies": []}
    cur = None
    parent = None
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        stripped = line.strip()
        indent = len(line) - len(line.lstrip())
        if stripped.startswith("- "):
            cur = {}
            doc["proxies"].append(cur)
            parent = None
            stripped, indent = stripped[2:], indent + 2
        if cur is None or ":" not in stripped:
            continue
        key, _, value = stripped.partition(":")
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if not value:
            parent = key
            cur.setdefault(key, {})
            continue
        if indent >= 6 and parent:
            # shortcut: two-level keys only, enough for ws-opts/headers
            cur[parent][key] = value
            continue
        parent = None
        cur[key] = value
    return doc


def coerce(proxy):
    """YAML gives strings; Clash wants port as int and bools as bool."""
    if "port" in proxy:
        try:
            proxy["port"] = int(proxy["port"])
        except (TypeError, ValueError):
            pass
    for key in ("tls", "udp", "skip-cert-verify"):
        if isinstance(proxy.get(key), str):
            proxy[key] = proxy[key].lower() in ("true", "yes", "on")
    return proxy


def tokyo_proxy():
    with open(TOKYO_FILE, encoding="utf-8") as f:
        doc = parse_doc(f.read())
    proxies = doc.get("proxies") or []
    if not proxies:
        raise RuntimeError("tokyo yaml has no proxies")
    return doc, proxies[0]


def node_01():
    doc = parse_doc(fetch(UPSTREAM_SUB))
    proxies = doc.get("proxies") or []
    if not proxies:
        raise RuntimeError("01 subscription has no proxies")
    # Prefer the node that already points at a trycloudflare host (the WS origin);
    # the CF-fast fallback duplicates the same xray backend behind a fixed CF IP.
    src = coerce(next((p for p in proxies if "trycloudflare" in str(p.get("servername", ""))), proxies[0]))
    ws = src.get("ws-opts") or {}
    # Build from an explicit whitelist: upstream also carries leftover
    # trycloudflare hosts in other keys that must not survive the merge.
    return {
        "name": NODE_01,
        "type": "vless",
        "server": FIXED_HOST,
        "port": 443,
        "uuid": src.get("uuid"),
        "network": "ws",
        "tls": True,
        "udp": True,
        "skip-cert-verify": False,
        "servername": FIXED_HOST,
        "ws-opts": {"path": ws.get("path") or "/ray", "headers": {"Host": FIXED_HOST}},
    }


def build():
    tokyo_doc, tokyo = tokyo_proxy()
    up = node_01()
    if tokyo.get("name") == up["name"]:
        raise RuntimeError("node name collision")
    merged = dict(tokyo_doc)
    merged["proxies"] = [tokyo, up]
    groups = merged.get("proxy-groups") or []
    if not groups:
        groups = [{"name": "PROXY", "type": "select", "proxies": []}]
    groups[0]["proxies"] = [tokyo["name"], up["name"]]
    merged["proxy-groups"] = groups
    if not merged.get("rules"):
        merged["rules"] = ["GEOIP,CN,DIRECT", "MATCH,PROXY"]
    if not merged["proxies"][1].get("uuid"):
        raise RuntimeError("01 node has no uuid")
    names = [p.get("name") for p in merged["proxies"]]
    if len(names) != 2 or None in names:
        raise RuntimeError(f"merged node set invalid: {names}")
    return merged, names


def publish(merged):
    os.makedirs(OUT_DIR, exist_ok=True)
    body = json.dumps(merged, ensure_ascii=False, indent=2) + "\n"
    tmp = OUT_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(body)
        f.flush()
        os.fsync(f.fileno())
    os.chmod(tmp, 0o644)
    os.replace(tmp, OUT_FILE)  # atomic: never a half-written subscription


def write_state(ok, names, detail):
    os.makedirs(OUT_DIR, exist_ok=True)
    payload = {"ok": ok, "ts": __import__("time").time(), "nodes": names, "detail": detail}
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    os.replace(tmp, STATE_FILE)


def main():
    try:
        merged, names = build()
    except Exception as exc:
        # last-known-good: OUT_FILE is left exactly as it was
        kept = os.path.exists(OUT_FILE)
        write_state(False, [], f"{exc}; kept_last_good={kept}")
        return fail(f"{exc} (kept last-known-good={kept})")
    publish(merged)
    write_state(True, names, "merged and published")
    print(f"[merge] OK nodes={names}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
