"""Normalise the curated package and its embedded fallback after a curator run.

The curator may record a bookSourceUrl with Legado's ## suffix. That is source metadata,
not part of the URL, and must not become a client base URL. This script also makes the
published provenance honest: heuristic toc/body probes are evidence, but they are not
evidence that the declarative source rules work in PocketKit.
"""
import argparse
import json
import os
import tempfile
from datetime import datetime, timezone


def clean_url(value):
    return (value or "").strip().split("##", 1)[0].strip()


def normalise(rule, url):
    cleaned = dict(rule or {})
    cleaned["bookSourceUrl"] = clean_url(cleaned.get("bookSourceUrl") or url)
    return cleaned


def finalize(package):
    out = []
    for source in package.get("sources", []):
        detection = dict(source.get("detection") or {})
        if detection.get("tocVia") == "heuristic" or detection.get("contentVia") == "heuristic":
            detection["ruleCompatibility"] = "unproven: heuristic extraction used; client declarative rules may fail"
        else:
            detection["ruleCompatibility"] = "declarative path verified in worker"
        url = clean_url(source.get("url") or (source.get("rule") or {}).get("bookSourceUrl"))
        out.append({
            "id": source["id"], "name": source.get("name", ""), "kind": source.get("kind", "novel"),
            "url": url, "rule": normalise(source.get("rule"), url),
            "provenance": source.get("provenance", {}), "detection": detection,
        })
    result = dict(package)
    result["schemaVersion"] = 1
    result["name"] = package.get("name", "拾用精选")
    result["sources"] = out
    result["note"] = (
        "仅下发声明式书源/漫画/视频规则与检测元数据；不托管正文、不执行上游脚本。"
        "检测深度按类型：novel 为 search→toc→content，manga 为 search→detail→toc→image(真实图片字节)，"
        "video 为 CMS list→playable；heuristic 标记表示站点改版后由兜底抽取，"
        "不代表客户端声明规则可用。单源失败保留上次已验证条目；全失败不覆盖旧包。"
    )
    result["updatedAt"] = package.get("updatedAt") or datetime.now(timezone.utc).isoformat(timespec="seconds")
    return result


def atomic_json(path, value):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path)), prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        # mkstemp creates 0600; the hub runs as a different user and must be able to read
        # this file, otherwise /content/book-sources.json turns into a 502 after every run.
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--builtin-out")
    args = parser.parse_args()
    with open(args.package, encoding="utf-8") as handle:
        package = json.load(handle)
    final = finalize(package)
    atomic_json(args.package, final)
    if args.builtin_out:
        builtin = dict(final)
        builtin["builtin"] = True
        builtin["generatedAt"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        atomic_json(args.builtin_out, builtin)
    print(json.dumps({"package": args.package, "sources": len(final["sources"]),
                      "builtin": args.builtin_out or ""}, ensure_ascii=False))


if __name__ == "__main__":
    main()
