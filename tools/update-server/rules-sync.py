#!/usr/bin/env python3
"""Publish data-only Clash rules as a bounded sing-box 1.14 bundle.

Only fixed upstreams are read; protocol/core releases are observed, never executed.
Loyalsoldier/clash-rules data is GPL-3.0: original snapshot + license are served too.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from datetime import datetime, timezone
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = "https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech"
REF_URL = "https://api.github.com/repos/Loyalsoldier/clash-rules/git/ref/heads/release"
LICENSE_URL = "https://raw.githubusercontent.com/Loyalsoldier/clash-rules/master/LICENSE"
RELEASES = {
    "flclash": "chen08209/FlClash",
    "mihomo": "MetaCubeX/mihomo",
    "singbox": "SagerNet/sing-box",
}
MAX_BYTES = 12 * 1024 * 1024
MAX_ENTRIES = 300_000
DOMAIN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
ENTRY = re.compile(r"\s+- ['\"]([^'\"\r\n]+)['\"]\s*")


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def atomic(path, body):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, stage = tempfile.mkstemp(prefix=".tmp-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(body)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(stage, 0o644)
        os.replace(stage, path)
    finally:
        if os.path.exists(stage):
            os.unlink(stage)


def read_json(path, default):
    try:
        return json.loads(Path(path).read_bytes())
    except (OSError, ValueError):
        return default


def trusted_url(url):
    parsed = urllib.parse.urlsplit(url)
    return (parsed.scheme == "https" and parsed.hostname in ("api.github.com", "raw.githubusercontent.com")
            and parsed.port in (None, 443) and not parsed.username and not parsed.password)


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not trusted_url(newurl):
            raise ValueError("untrusted upstream redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url, cache):
    if not trusted_url(url):
        raise ValueError("untrusted source")
    # Immutable commit URLs share a cache slot per source filename, not one slot per daily commit.
    cache_url = re.sub(r"(/Loyalsoldier/clash-rules/)[0-9a-f]{40}/", r"\1snapshot/", url)
    key = hashlib.sha256(cache_url.encode()).hexdigest()
    body_path, meta_path = cache / (key + ".body"), cache / (key + ".json")
    old = read_json(meta_path, {})
    headers = {"User-Agent": "PocketKit-rule-sync/1", "Accept": "application/vnd.github+json" if "api.github.com" in url else "text/plain"}
    cached = body_path.read_bytes() if body_path.is_file() else None
    if cached is not None and hashlib.sha256(cached).hexdigest() == old.get("sha256") and old.get("etag"):
        headers["If-None-Match"] = old["etag"]
    opener = urllib.request.build_opener(SafeRedirect())
    try:
        with opener.open(urllib.request.Request(url, headers=headers), timeout=40) as response:
            if response.status != 200:
                raise ValueError("unexpected upstream status")
            data = response.read(MAX_BYTES + 1)
            if not data or len(data) > MAX_BYTES:
                raise ValueError("upstream size limit")
            etag = response.headers.get("ETag", "")
    except urllib.error.HTTPError as error:
        if error.code != 304 or "If-None-Match" not in headers:
            raise
        return cached
    atomic(body_path, data)
    atomic(meta_path, encoded({"etag": etag, "sha256": hashlib.sha256(data).hexdigest()}))
    return data


def payload(data):
    text = data.decode("utf-8-sig")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "payload:":
        raise ValueError("not a Clash payload")
    values = []
    for line in lines[1:]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = ENTRY.fullmatch(line)
        if not match:
            raise ValueError("unsupported payload syntax")
        values.append(match[1])
        if len(values) > MAX_ENTRIES:
            raise ValueError("entry limit")
    if not values:
        raise ValueError("empty payload")
    return values


def domains(data):
    exact, suffix = set(), set()
    for value in payload(data):
        is_suffix = value.startswith("+.")
        domain = value[2:] if is_suffix else value
        if len(domain) > 253 or not DOMAIN.fullmatch(domain):
            raise ValueError("invalid domain")
        (suffix if is_suffix else exact).add(domain)
    return {"domain": sorted(exact), "domain_suffix": sorted(suffix)}


def cidrs(data):
    values = set()
    for value in payload(data):
        network = ipaddress.ip_network(value, strict=True)
        if network.prefixlen < (8 if network.version == 4 else 16):
            raise ValueError("overbroad network")
        values.add(str(network))
    return sorted(values)


def make_bundle(raw):
    direct, proxy = domains(raw["direct"]), domains(raw["proxy"])
    direct["ip_cidr"] = cidrs(raw["cncidr"])
    counts = {"directDomains": len(direct["domain"]) + len(direct["domain_suffix"]),
              "proxyDomains": len(proxy["domain"]) + len(proxy["domain_suffix"]), "cnCidrs": len(direct["ip_cidr"])}
    if counts["directDomains"] < 500 or counts["proxyDomains"] < 500 or counts["cnCidrs"] < 100:
        raise ValueError("implausibly small upstream")
    if sum(counts.values()) > MAX_ENTRIES:
        raise ValueError("combined entry limit")
    body = encoded({"schemaVersion": 1, "rules": {"direct": direct, "proxy": proxy}})
    if len(body) > MAX_BYTES:
        raise ValueError("bundle size limit")
    return body, counts


def watch_releases(cache, previous, get=fetch):
    observed, errors = dict(previous), {}
    for name, repo in RELEASES.items():
        try:
            release = json.loads(get("https://api.github.com/repos/" + repo + "/releases/latest", cache))
            tag = release["tag_name"]
            if not isinstance(tag, str) or not 0 < len(tag) < 100 or release.get("draft") or release.get("prerelease"):
                raise ValueError("invalid release")
            item = {"tag": tag, "url": "https://github.com/" + repo + "/releases/tag/" + urllib.parse.quote(tag, safe=""),
                    "publishedAt": release.get("published_at"), "requiresAppUpgrade": True, "autoInstall": False}
            old = previous.get(name, {})
            item["detectedAt"] = old.get("detectedAt", now()) if old.get("tag") == tag else now()
            # Notes are evidence for a future APK upgrade, never executable configuration.
            item["notes"] = str(release.get("body") or "")[:4000]
            observed[name] = item
        except Exception as error:
            errors[name] = type(error).__name__
    return observed, errors


def sync(root, get=fetch, validator=None):
    root = Path(root)
    cache = root / "rule-cache"
    cache.mkdir(parents=True, exist_ok=True)
    state_path, manifest_path = root / "rules-state.json", root / "rules-manifest.json"
    previous = read_json(state_path, {})
    releases, errors = watch_releases(cache, previous.get("upstreamReleases", {}), get)
    state = {"lastAttemptAt": now(), "lastSuccessAt": previous.get("lastSuccessAt"),
             "upstreamReleases": releases, "upstreamErrors": errors, "lastError": None}
    try:
        commit = json.loads(get(REF_URL, cache))["object"]["sha"]
        if not isinstance(commit, str) or not re.fullmatch("[0-9a-f]{40}", commit):
            raise ValueError("invalid source commit")
        raw, sources = {}, []
        for name in ("direct", "proxy", "cncidr"):
            url = "https://raw.githubusercontent.com/Loyalsoldier/clash-rules/" + commit + "/" + name + ".txt"
            raw[name] = get(url, cache)
            digest = hashlib.sha256(raw[name]).hexdigest()
            sources.append({"name": name, "url": url, "sha256": digest, "license": "GPL-3.0",
                            "sourceUrl": BASE_URL + "/rule-sources/" + digest + ".txt"})
        body, counts = make_bundle(raw)
        if validator:
            validator(body)
        digest = hashlib.sha256(body).hexdigest()
        license_data = get(LICENSE_URL, cache)
        if b"GNU GENERAL PUBLIC LICENSE" not in license_data or b"Version 3" not in license_data:
            raise ValueError("missing upstream license")
        for item, name in zip(sources, ("direct", "proxy", "cncidr")):
            atomic(root / "rule-sources" / (item["sha256"] + ".txt"), raw[name])
        atomic(root / "rule-sources" / "LICENSE.txt", license_data)
        atomic(root / "rules" / (digest + ".json"), body)
        old = read_json(manifest_path, {})
        manifest = {"rulesSchemaVersion": 1, "rulesVersion": digest, "rulesSha256": digest,
                    "rulesBytes": len(body), "rulesUrl": BASE_URL + "/rules/" + digest + ".json",
                    "rulesUpdatedAt": old.get("rulesUpdatedAt", now()) if old.get("rulesVersion") == digest else now(),
                    "rulesCounts": counts, "rulesSourceCommit": commit, "rulesSources": sources,
                    "rulesLicenseUrl": BASE_URL + "/rule-sources/LICENSE.txt", "rulesMinAppCode": 22}
        atomic(manifest_path, encoded(manifest))  # pointer changes only after every file is valid and durable
        state.update({"lastSuccessAt": now(), "active": manifest, "changed": old.get("rulesVersion") != digest})
    except Exception as error:
        state.update({"lastError": type(error).__name__ + ": " + str(error)[:180],
                      "active": read_json(manifest_path, {}), "changed": False})
    atomic(state_path, encoded(state))
    print(json.dumps({"ok": state["lastError"] is None, "changed": state["changed"],
                      "rulesVersion": state["active"].get("rulesVersion"), "counts": state["active"].get("rulesCounts"),
                      "upstreamTags": {k: v["tag"] for k, v in releases.items()},
                      "upstreamErrors": errors, "error": state["lastError"]}))
    return 0 if state["lastError"] is None else 1


def cli_validator(command, root):
    def validate(body):
        bundle = json.loads(body)["rules"]
        direct, proxy = bundle["direct"], bundle["proxy"]
        rules = []
        for field, group, outbound in (("domain", proxy, "proxy"), ("domain", direct, "direct"),
                                        ("domain_suffix", proxy, "proxy"), ("domain_suffix", direct, "direct"),
                                        ("ip_cidr", direct, "direct")):
            if group.get(field):
                rules.append({field: group[field], "action": "route", "outbound": outbound})
        config = {"outbounds": [{"type": "direct", "tag": "direct"}, {"type": "direct", "tag": "proxy"}],
                  "route": {"rules": rules, "final": "proxy"}}
        path = root / "rule-cache" / "native-check.json"
        atomic(path, encoded(config))
        result = subprocess.run([command, "check", "-c", str(path)], capture_output=True, timeout=45)
        if result.returncode:
            raise ValueError("sing-box native validation failed")
    return validate


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", default="/data/pocketkit-hub")
    parser.add_argument("--validator", help="pinned sing-box 1.14.3 executable; supplied by deployment")
    args = parser.parse_args()
    root = Path(args.data_dir)
    raise SystemExit(sync(root, validator=cli_validator(args.validator, root) if args.validator else None))
