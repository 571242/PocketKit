"""Offline unit tests for the content subscription worker.  Run:  python3 selftest.py

Covers the pieces that can break silently: selector grammar, numbering, regex,
fallback operators, script refusal, request encoding, private-host blocking and
candidate classification. No network access is used.
"""
import json
import sys

import boundedhttp
import curator
import legado

FAIL = []


def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        FAIL.append(name)


def test_selector_basics():
    root = legado.parse(
        '<ul class="list"><li class="item"><a href="/a1">甲</a></li>'
        '<li class="item"><a href="/a2">乙</a></li></ul>')
    items = legado.select(root, ".list li")
    check("selector descendant", len(items) == 2)
    check("selector attr", legado.select(root, ".item a")[1].attrs["href"] == "/a2")
    check("selector :eq", len(legado.select(root, ".item:eq(0)")) == 1)


def test_number_suffix_and_child():
    # Legado 'a.0' means first <a>; 'dd > a' child combinator
    root = legado.parse('<div><a href="/x">一</a><a href="/y">二</a></div><dl><dd><a href="/c">章</a>说明</dd></dl>')
    first = legado.select(root, "a.0")
    check("number suffix a.0", len(first) == 1 and first[0].attrs["href"] == "/x")
    child = legado.select(root, "dd > a")
    check("child combinator", len(child) == 1 and child[0].attrs["href"] == "/c")


def test_evaluate_regex_and_fallback():
    root = legado.parse('<p class="meta">作者：张三 字数：1200</p>')
    check("regex replace strip", legado.evaluate("p.meta@text##字数：.*", root) == "作者：张三")
    check("fallback ||", legado.evaluate(".nope@text||p.meta@text", root).startswith("作者"))
    check("contains", legado.evaluate("p:contains(作者)@text", root).startswith("作者"))


def test_script_refusal():
    try:
        legado.evaluate("@js:java.lang.Runtime.getRuntime()", legado.parse("<p>x</p>"))
        check("script refused", False)
    except legado.ScriptRefused:
        check("script refused", True)


def test_classify():
    novel = {"bookSourceUrl": "https://a.com", "searchUrl": "/s?q={{key}}",
             "ruleSearch": {"bookList": ".b"}, "ruleToc": {"chapterList": "dd"},
             "ruleContent": {"content": "#c"}}
    check("classify novel", curator.classify(novel)[0] == "novel")
    web = {"sourceUrl": "https://a.com/rss", "bookSourceUrl": "https://a.com"}
    check("classify web rss", curator.classify(web)[0] == "web")
    # scripts no longer disqualify: the client runs them in a restricted sandbox
    scripted = dict(novel, ruleContent={"content": "@js:evil()"})
    check("classify scripted novel now allowed", curator.classify(scripted)[0] == "novel")
    check("scripted marked hasScript", curator.source_has_script(scripted) is True)
    check("clean not hasScript", curator.source_has_script(novel) is False)
    # manga by bookSourceType==2 and by name/group keyword
    manga_type2 = {"bookSourceUrl": "https://m.com", "bookSourceType": 2,
                   "searchUrl": "/s?q={{key}}", "ruleSearch": {"bookList": ".b"},
                   "ruleToc": {"chapterList": "dd"}, "ruleContent": {"content": "#c"}}
    check("classify manga type2", curator.classify(manga_type2)[0] == "manga")
    manga_kw = dict(novel, bookSourceGroup="漫画书源", bookSourceName="某漫画")
    check("classify manga keyword", curator.classify(manga_kw)[0] == "manga")
    # video CMS
    video = {"bookSourceUrl": "https://cms.example", "type": "cms",
             "rule": {"type": "cms", "api": "https://cms.example/api.php/provide/vod/"}}
    check("classify video cms", curator.classify(video)[0] == "video")
    check("cms_api found", curator.cms_api(video).endswith("/provide/vod/"))
    # novel via ruleArticles + singleUrl=false
    articles = {"bookSourceUrl": "https://a.com", "ruleArticles": {"list": ".x"},
                "singleUrl": False, "ruleSearch": {"bookList": ".b"},
                "ruleToc": {"chapterList": "dd"}, "ruleContent": {"content": "#c"}}
    check("classify novel ruleArticles", curator.classify(articles)[0] == "novel")


def test_manga_video_helpers():
    # _first_play_url picks the first http(s) address out of vod_play_url
    rows = [{"vod_name": "x", "vod_play_url": "第1集$https://v.qq.com/a#第2集$https://v.qq.com/b"},
            {"vod_name": "y", "vod_play_url": ""}]
    check("video play url", curator._first_play_url(rows) == "https://v.qq.com/a")
    check("video play url none", curator._first_play_url([{"vod_play_url": "无"}]) == "")
    # collect_image_urls handles <img> tags, JSON fields and bare urls
    html = ('<div><img data-src="/p/1.webp"><img src="https://cdn/x/2.jpg"></div>'
            '{"url":"https://cdn/x/3.png"} https://cdn/x/4.jpeg')
    urls = curator.collect_image_urls(html, "https://site.example/ch/1.html")
    check("image count", len(urls) == 4)
    check("image absolute", urls[0] == "https://site.example/p/1.webp")
    check("image dedup/order", urls[1] == "https://cdn/x/2.jpg")
    # detection depth as_dict reports honest depths + success timestamp for image/playable
    d = curator.Detection(); d.depth = "image"; d.image_url = "https://c/1.webp"; d.content_chars = 2048
    dd = d.as_dict()
    check("image depth ok", dd["depth"] == "image" and dd.get("imageUrl") == "https://c/1.webp"
          and dd["lastSuccessAt"])
    p = curator.Detection(); p.depth = "playable"; p.play_url = "https://v/1.m3u8"
    check("playable depth ok", p.as_dict()["depth"] == "playable" and p.as_dict().get("playUrl"))
    # _verified gate by kind
    check("verified image", curator._verified("manga", d) is True)
    check("verified playable", curator._verified("video", p) is True)
    check("verified novel not from image", curator._verified("novel", d) is False)


def test_stable_id_dedup():
    a = {"bookSourceUrl": "https://A.com", "bookSourceName": "书"}
    b = {"bookSourceUrl": "https://a.com", "bookSourceName": "书"}
    check("stable id dedup", curator.stable_id(a) == curator.stable_id(b))


def test_search_request_encoding():
    url, method, body, headers = curator.build_search_request(
        "/s?wd={{key}}", "西游记", "https://m.cuoceng.com")
    check("search url absolute", url.startswith("https://m.cuoceng.com/s?wd="))
    check("search url encoded", "%E8%A5%BF" in url)
    url2, method2, body2, _ = curator.build_search_request(
        'https://x.com/s,{"method":"POST","body":"k={{key}}"}', "西游记", "https://x.com")
    check("post method parsed", method2 == "POST" and "%E8%A5%BF" in body2)


def test_private_host_blocked():
    for bad in ("http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.5/", "http://localhost/"):
        try:
            boundedhttp._validate(bad)
            check("blocked %s" % bad, False)
        except boundedhttp.Blocked:
            check("blocked %s" % bad, True)


def test_policy_blacklist():
    check("policy po18", curator.policy_excluded({"bookSourceUrl": "https://m.po18vip.me"}) == "po18")
    check("policy clean", curator.policy_excluded({"bookSourceUrl": "https://m.cuoceng.com"}) is None)


def test_content_quality():
    prose = "他抬头看天，星星铺了满天。" * 20
    check("quality prose accepted", curator.content_quality(prose)[0] is True)
    notice = "本章没完，请点击下一页继续阅读！如果被转码了请退出转码。" + "正文" * 120
    check("quality notice rejected", curator.content_quality(notice)[0] is False)
    footer = "真正的正文内容很长很长。" * 30 + "\n推荐阅读\n手机版阅读"
    ok, _ = curator.content_quality(curator.trim_nav(footer))
    check("footer trimmed not rejected", ok is True)


def test_lastgood_keeps_package():
    import os, tempfile
    d = tempfile.mkdtemp()
    prev = os.path.join(d, "content", "book-sources.json")
    os.makedirs(os.path.dirname(prev), exist_ok=True)
    good = {"schemaVersion": 1, "name": "拾用精选", "sources": [{"id": "keep1", "name": "ok"}]}
    with open(prev, "w", encoding="utf-8") as f:
        json.dump(good, f)
    # a collection whose only entry is not a classified kind -> nothing verifies
    cand = os.path.join(d, "cand.json")
    with open(cand, "w", encoding="utf-8") as f:
        json.dump([{"bookSourceName": "坏源", "bookSourceUrl": "https://x.com"}], f)
    rc = curator.run(d, [("x", "t")], offline=cand, max_test=1)
    with open(prev, encoding="utf-8") as f:
        after = json.load(f)
    check("last-good kept on all-fail", rc == 1 and after == good)


def test_lastgood_survives_two_failures():
    """Kept-last-good must not overwrite the verified depth, or the second failure drops it."""
    import os, tempfile
    d = tempfile.mkdtemp()
    prev = os.path.join(d, "content", "book-sources.json")
    os.makedirs(os.path.dirname(prev), exist_ok=True)
    good = {"schemaVersion": 1, "name": "拾用精选", "sources": [
        {"id": "n1", "name": "好小说", "kind": "novel", "url": "https://n",
         "rule": {"bookSourceUrl": "https://n"},
         "detection": {"depth": "content", "tocVia": "declared", "contentVia": "declared"}}]}
    json.dump(good, open(prev, "w", encoding="utf-8"), ensure_ascii=False)
    cand = os.path.join(d, "cand.json")
    json.dump([{"bookSourceUrl": "https://n", "bookSourceName": "好小说",
                "searchUrl": "/s?q={{key}}", "ruleSearch": {"bookList": ".b"},
                "ruleToc": {"chapterList": "dd"}, "ruleContent": {"content": "x"}}],
              open(cand, "w", encoding="utf-8"), ensure_ascii=False)

    def stub(src, previous=None):
        det = curator.Detection()
        det.error = "timeout"
        return det

    saved = curator.detect_isolated
    curator.detect_isolated = stub
    try:
        for round_no in (1, 2):
            curator.run(d, [("s", "t")], offline=cand, max_test=5, min_healthy=1)
            pkg = json.load(open(prev, encoding="utf-8"))
            ids = {s["id"] for s in pkg["sources"]}
            check("kept-last-good round %d survives" % round_no, "n1" in ids)
    finally:
        curator.detect_isolated = saved


def test_publish_shape_by_kind():
    """Drive run() with a stub detector (no network) and assert the published package
    carries the right kind, hasScript provenance, and honest detection depth."""
    import os, tempfile
    d = tempfile.mkdtemp()
    cand = os.path.join(d, "cand.json")
    srcs = [
        {"bookSourceUrl": "https://m.example", "bookSourceName": "某漫画", "bookSourceType": 2,
         "searchUrl": "/s?q={{key}}&page={{page}}", "ruleSearch": {"bookList": ".b"},
         "ruleToc": {"chapterList": "dd"}, "ruleContent": {"content": "@js:img()"}},
        {"bookSourceUrl": "https://cms.example", "bookSourceName": "某影视", "type": "cms",
         "rule": {"type": "cms", "api": "https://cms.example/api.php/provide/vod/"}},
    ]
    json.dump(srcs, open(cand, "w", encoding="utf-8"), ensure_ascii=False)

    def stub(src, previous=None):
        kind = curator.classify(src)[0]
        det = curator.Detection()
        if kind == "manga":
            det.depth = "image"; det.image_url = "https://c/1.webp"; det.content_chars = 4096
            det.toc_chapters = 5; det.search_hits = 3
        elif kind == "video":
            det.depth = "playable"; det.play_url = "https://v/1.m3u8"; det.search_hits = 20
        return det

    saved = curator.detect_isolated
    curator.detect_isolated = stub
    try:
        rc = curator.run(d, [("s", "t")], offline=cand, max_test=10, min_healthy=1)
    finally:
        curator.detect_isolated = saved
    pkg = json.load(open(os.path.join(d, "content", "book-sources.json"), encoding="utf-8"))
    by_kind = {s["kind"]: s for s in pkg["sources"]}
    check("publish rc ok", rc == 0)
    check("publish manga depth image", by_kind.get("manga", {}).get("detection", {}).get("depth") == "image")
    check("publish manga hasScript", by_kind.get("manga", {}).get("provenance", {}).get("hasScript") is True)
    check("publish video cms rule", by_kind.get("video", {}).get("rule", {}).get("type") == "cms")
    check("publish video playable", by_kind.get("video", {}).get("detection", {}).get("depth") == "playable")


def test_carry_over_untested():
    """A run that tests only part of the candidate set must not shrink the published package."""
    import os, tempfile
    d = tempfile.mkdtemp()
    prev = os.path.join(d, "content", "book-sources.json")
    os.makedirs(os.path.dirname(prev), exist_ok=True)
    good = {"schemaVersion": 1, "name": "拾用精选", "sources": [
        {"id": "video-x", "name": "老视频", "kind": "video", "url": "https://v",
         "rule": {"type": "cms", "api": "https://v/api"},
         "detection": {"depth": "playable", "playUrl": "https://v/1.m3u8"}},
        {"id": "novel-y", "name": "老小说", "kind": "novel", "url": "https://n",
         "rule": {"bookSourceUrl": "https://n"}, "detection": {"depth": "content"}},
    ]}
    json.dump(good, open(prev, "w", encoding="utf-8"), ensure_ascii=False)
    cand = os.path.join(d, "cand.json")
    json.dump([{"bookSourceUrl": "https://m.example", "bookSourceName": "新漫画", "bookSourceType": 2,
                "searchUrl": "/s?q={{key}}", "ruleSearch": {"bookList": ".b"},
                "ruleToc": {"chapterList": "dd"}, "ruleContent": {"content": "x"}}],
              open(cand, "w", encoding="utf-8"), ensure_ascii=False)

    def stub(src, previous=None):
        det = curator.Detection()
        det.depth = "image"; det.image_url = "https://c/1.webp"
        return det

    saved = curator.detect_isolated
    curator.detect_isolated = stub
    try:
        rc = curator.run(d, [("s", "t")], offline=cand, max_test=1, min_healthy=1)
    finally:
        curator.detect_isolated = saved
    pkg = json.load(open(prev, encoding="utf-8"))
    ids = {s["id"] for s in pkg["sources"]}
    check("carry-over publishes", rc == 0)
    check("carry-over keeps untested video", "video-x" in ids)
    check("carry-over keeps untested novel", "novel-y" in ids)


def test_cms_seed_present():
    """The fixed CMS endpoints must be classifiable, or a nightly run drops all video."""
    kinds = {curator.classify(e)[0] for e in curator.CMS_SEED}
    check("cms seed classified video", kinds == {"video"})
    check("cms seed has four", len(curator.CMS_SEED) == 4)


def test_finalize_keeps_kinds():
    import finalize_sources
    pkg = {"sources": [
        {"id": "m", "name": "漫", "kind": "manga", "url": "https://m", "rule": {"bookSourceUrl": "https://m"},
         "provenance": {"hasScript": True},
         "detection": {"depth": "image", "tocVia": "declared", "imageUrl": "https://c/1.webp"}},
        {"id": "v", "name": "影", "kind": "video", "url": "https://v",
         "rule": {"type": "cms", "api": "https://v/api"}, "provenance": {"hasScript": False},
         "detection": {"depth": "playable", "playUrl": "https://v/1.m3u8"}},
    ]}
    out = finalize_sources.finalize(pkg)
    kinds = {s["kind"] for s in out["sources"]}
    check("finalize keeps manga/video", kinds == {"manga", "video"})
    vm = [s for s in out["sources"] if s["kind"] == "video"][0]
    check("finalize keeps cms rule", vm["rule"].get("type") == "cms" and vm["rule"].get("api"))
    check("finalize note mentions 漫画", "漫画" in out["note"])


if __name__ == "__main__":
    sys.setrecursionlimit(10000)
    for fn in [test_selector_basics, test_number_suffix_and_child, test_evaluate_regex_and_fallback,
               test_script_refusal, test_classify, test_manga_video_helpers, test_stable_id_dedup,
               test_search_request_encoding, test_private_host_blocked, test_policy_blacklist,
               test_content_quality, test_lastgood_keeps_package, test_lastgood_survives_two_failures,
               test_publish_shape_by_kind,
               test_carry_over_untested, test_cms_seed_present, test_finalize_keeps_kinds]:
        fn()
    print("\n%d failed" % len(FAIL))
    raise SystemExit(1 if FAIL else 0)
