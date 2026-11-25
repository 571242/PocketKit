# -*- coding: utf-8 -*-
"""Assemble the bundled curated source snapshot.

Two inputs feed this file:
  * tools/content-subscription/seed/sources.json - the upstream yckceo rules that passed the live
    search -> detail -> toc -> content/image chain in this checkout (fanqie/banshanren were
    corrected afterwards; see docs/reading-source-provenance.md). The seed lives in the repo, not
    in .scratch, so the bundle can be rebuilt without the probe workspace.
  * the Apple CMS video APIs, which are plain JSON endpoints rather than Legado rules.

Only sources that were observed working end to end are bundled. The rule object is copied
verbatim so the app exercises the real upstream rule, not a hand-written approximation.
"""
import hashlib
import io
import json
import os
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CANDIDATE = os.path.join(ROOT, "tools", "content-subscription", "seed", "sources.json")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "reading", "builtin-sources.json")

# name -> probe keyword used when the source was verified
PROBE_KEYWORDS = {
    "番茄小说": "斗破苍穹",
    "搬山人小说网": "斗破苍穹",
    "无忧书城": "斗破苍穹",
    "笔趣阁": "斗破苍穹",
    "精华书阁": "斗破苍穹",
    "全本小说网": "斗破苍穹",
    "得奇小说网": "斗破苍穹",
    "筆趣閣": "斗破苍穹",
    "拷贝漫画": "斗破苍穹",
    "G社漫画": "斗破苍穹",
    "漫画团": "斗破苍穹",
}

# Measured dead ends from this host, dropped from the bundle so no built-in card is a dud:
#   筆趣閣   both hostnames blackhole (TCP timeout, IPv6 unreachable)
#   拷贝漫画 api.mangacopy.com resets every TLS handshake (all four Cloudflare IPs)
UNREACHABLE = {"筆趣閣", "拷贝漫画"}

# Public Apple CMS endpoints. Kept separate from Legado rules: the engine talks to them
# directly through rule {"type":"cms","api":...}.
# zuidazy.me has no route here and api.wujinapi.me is behind a Cloudflare JS challenge.
VIDEOS = [
    ("ffzy", "非凡资源", "https://cj.ffzyapi.com/api.php/provide/vod/"),
    ("jisu", "极速资源", "https://jszyapi.com/api.php/provide/vod/"),
    ("guangsu", "光速资源", "https://api.guangsuapi.com/api.php/provide/vod/"),
    ("hongniu", "红牛资源", "https://www.hongniuzy2.com/api.php/provide/vod/"),
]


def stable_id(url, name):
    basis = (url or "").strip().lower() + "|" + (name or "").strip()
    return "yck-" + hashlib.sha256(basis.encode("utf-8")).hexdigest()[:16]


# Measured with tools/reading-live-check/ReadingLiveCheck on 2026-10-31 against the client
# engine (not the Python worker): search hits / toc chapters / text chars, or image count.
MEASURED = {
    "番茄小说": {"searchHits": 6, "tocChapters": 404, "contentChars": 7487},
    "搬山人小说网": {"searchHits": 24, "tocChapters": 79, "contentChars": 17385},
    "无忧书城": {"searchHits": 2, "tocChapters": 1705, "contentChars": 9150},
    "笔趣阁": {"searchHits": 1, "tocChapters": 500, "contentChars": 9326},
    "精华书阁": {"searchHits": 20, "tocChapters": 25, "contentChars": 3625},
    "全本小说网": {"searchHits": 12, "tocChapters": 209, "contentChars": 3512},
    "得奇小说网": {"searchHits": 10, "tocChapters": 741, "contentChars": 2515},
    "G社漫画": {"searchHits": 30, "tocChapters": 681, "contentChars": 0},
    "漫画团": {"searchHits": 3, "tocChapters": 681, "contentChars": 0},
    "非凡资源": {"searchHits": 16, "tocChapters": 432, "contentChars": 0},
    "极速资源": {"searchHits": 20, "tocChapters": 430, "contentChars": 0},
    "光速资源": {"searchHits": 20, "tocChapters": 436, "contentChars": 0},
    "红牛资源": {"searchHits": 20, "tocChapters": 436, "contentChars": 0},
}


def detection_for(kind, name):
    """Measured chain depth from the client-engine live check.

    These blocks are also what the nightly curator reads to decide that a source which
    fails one night keeps its last-good entry instead of being dropped from the bundle.
    """
    measured = dict(MEASURED.get(name, {"searchHits": 0, "tocChapters": 0, "contentChars": 0}))
    measured.update({"tocVia": "declared", "contentVia": "declared",
                     "verifiedAt": "2026-10-31",
                     "verifiedBy": "PocketKit ReadingEngine (tools/reading-live-check)"})
    if kind == "manga":
        measured["depth"] = "image"
        measured["note"] = "chapter image list resolved"
    elif kind == "video":
        measured["depth"] = "playable"
        measured["note"] = "CMS detail returned an .m3u8"
    else:
        measured["depth"] = "content"
        measured["note"] = "first 3 chapters non-empty"
    return measured


def main():
    bundle = json.load(io.open(CANDIDATE, encoding="utf-8"))
    sources = []
    for entry in bundle["sources"]:
        name = entry["name"]
        if name in UNREACHABLE:
            continue
        rule = dict(entry["rule"])
        for key in ("loginUrl", "loginUi", "loginCheckJs"):
            rule.pop(key, None)
        url = (rule.get("bookSourceUrl") or entry.get("url") or "").strip()
        sources.append({
            "id": stable_id(url, rule.get("bookSourceName", name)),
            "name": name,
            "kind": entry["kind"],
            "url": url,
            "rule": rule,
            "provenance": {
                "provider": "yckceo",
                "collection": "",
                "upstreamName": rule.get("bookSourceName", ""),
                "upstreamUrl": url,
                "probe": "search->detail->toc->content/image chain",
                "probeKeyword": PROBE_KEYWORDS.get(name, ""),
            },
            "detection": detection_for(entry["kind"], name),
        })
    for code, name, api in VIDEOS:
        sources.append({
            "id": "video-" + code,
            "name": name,
            "kind": "video",
            "url": api,
            "rule": {"type": "cms", "api": api, "name": name},
            "provenance": {"provider": "yckceo-video", "collection": "", "upstreamName": name,
                           "upstreamUrl": api, "probe": "list->detail->m3u8", "probeKeyword": "斗罗大陆"},
            "detection": detection_for("video", name),
        })
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    package = {
        "schemaVersion": 1,
        "name": "拾用精选",
        "builtin": True,
        "generatedAt": now,
        "updatedAt": now,
        "sources": sources,
        "note": "内置离线快照，与线上 /content/book-sources.json 同结构。仅含实测可用的公开源；不托管正文。",
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(package, f, ensure_ascii=False, indent=2)
    print(json.dumps({"out": OUT, "count": len(sources),
                      "kinds": {k: sum(1 for s in sources if s["kind"] == k) for k in ("novel", "manga", "video")}},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
