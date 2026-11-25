"""Build the APK-bundled fallback snapshot from a published package.

The bundled snapshot must share the online package's structure (app/src/main/
assets/reading/builtin-sources.json) so a first offline launch can preview it.
Only verified (search->toc->content) sources are carried over.

  python3 build_builtin.py --package out/content/book-sources.json \
      --out ../../app/src/main/assets/reading/builtin-sources.json
"""
import argparse
import json
import os
from datetime import datetime, timezone


def build(package):
    sources = []
    for s in package.get("sources", []):
        if (s.get("detection") or {}).get("depth") != "content":
            continue
        sources.append({
            "id": s["id"], "name": s.get("name", ""), "kind": s.get("kind", "novel"),
            "url": s.get("url", ""), "rule": s.get("rule", {}),
            "provenance": s.get("provenance", {}),
            "detection": s.get("detection", {}),
        })
    return {
        "schemaVersion": 1,
        "name": package.get("name", "拾用精选"),
        "builtin": True,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "updatedAt": package.get("updatedAt"),
        "sources": sources,
        "note": ("内置离线快照，与线上 /content/book-sources.json 同结构。仅含已完成 "
                 "search→toc→content 实测的声明式源；断网首启可预览，确认后按需获取线上更新。"
                 "不托管正文、不执行上游脚本。"),
    }, sources


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    with open(args.package, encoding="utf-8") as f:
        package = json.load(f)
    builtin, sources = build(package)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(builtin, f, ensure_ascii=False, indent=2)
    print(json.dumps({"out": args.out, "sources": len(sources)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
