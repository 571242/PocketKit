"""Curate the PocketKit "拾用精选" reading subscription (novel + manga + video).

Pipeline (all bounded, declarative parsing only — upstream JS is never executed):
  1. read tracked candidate collections (yckceo ids), fetch each JSON;
  2. normalise + dedupe, then classify into novel / manga / video (web entries are
     kept out of the book pack). Sources that carry @js/<js>/java scripts are no
     longer refused outright — the 0.14.0 client runs them in a restricted Rhino
     sandbox — but they are marked hasScript so the pack stays honest;
  3. real chain test per kind:
       novel : search -> toc -> body text (>=200 chars)
       manga : search -> detail -> toc has chapters -> a chapter page yields an
               image URL that really returns image/* bytes (first 64KB)
       video : CMS ?ac=detail&wd=<kw> returns JSON, list non-empty, and at least
               one http(s) playback address inside vod_play_url
     recording a detection depth, never claiming more than was observed;
  4. merge into the previous published package: a source that fails one run keeps
     its last verified entry; if every source fails, keep last-good and publish
     nothing empty;
  5. atomically write /data/pocketkit-hub/content/book-sources.json plus a
     maintenance status file the app and operators can read.

Run on server:  python3 curator.py --data-dir /data/pocketkit-hub
Dry run local:  python3 curator.py --data-dir .scratch/cs/out --offline-collections FILE
"""
import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import time
from datetime import datetime, timezone

import boundedhttp
import legado
import urllib.error as urllib_error

HERE = os.path.dirname(os.path.abspath(__file__))
MIN_CONTENT_CHARS = 200
PROBE_QUERIES = ["西游记", "三国演义", "斗罗大陆", "西游记后传小说", "三国演义章回体小说", "西游记后传小说全文阅读"]

# Manga/video search probes. Keep them short: each source gets a wall-clock budget.
MANGA_PROBE_QUERIES = ["斗破苍穹", "海贼王", "火影忍者", "进击的巨人", "鬼灭之刃"]
VIDEO_PROBE_QUERIES = ["斗罗大陆", "庆余年", "流浪地球", "西游记"]

# Script markers are informational now: a hit means hasScript=true in provenance,
# not rejection. The worker itself still never executes any of this.
SCRIPT_MARKERS = ("@js:", "<js>", "</js>", "java.", "org.jsoup", "org.rhino", "sourceRegex")

MANGA_NAME_HINTS = ("漫画", "漫蛙", "包子", "哔咔")
IMAGE_RE = re.compile(r"\.(?:jpe?g|png|webp|gif|bmp|avif)(?:[?#.]|$)", re.I)

# Candidate collections to track. Add ids here or via --collection; the worker
# re-reads every id on every run so newly added sources are picked up, instead
# of pinning one historical dump forever.
DEFAULT_COLLECTIONS = [
    ("1298", "阿豪书源 小说合集(起点/得奇/篱笆等 15 条)"),
    ("1285", "yckceo 书源合集 1285"),
    ("1325", "yckceo 书源合集 1325"),
]
COLLECTION_URL = "https://www.yckceo.com/yuedu/shuyuans/json/id/%s.json"


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def atomic_write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".tmp-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def read_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def source_has_script(src):
    text = json.dumps(src, ensure_ascii=False)
    return any(marker in text for marker in SCRIPT_MARKERS)


# ----------------------------------------------------------------------------- classification

def cms_api(src):
    """Return the CMS api base if this source is a video CMS (type=cms with api)."""
    if not isinstance(src, dict):
        return ""
    rule = src.get("rule")
    if isinstance(rule, dict):
        if str(rule.get("type", "")).strip().lower() == "cms" and rule.get("api"):
            return str(rule.get("api")).strip()
    # also accept a raw CMS entry that carries type/api at the top level
    if str(src.get("type", "")).strip().lower() == "cms" and src.get("api"):
        return str(src.get("api")).strip()
    return ""


def looks_manga(src):
    if not isinstance(src, dict):
        return False
    if src.get("bookSourceType") == 2:
        return True
    blob = str(src.get("bookSourceGroup") or "") + " " + str(src.get("bookSourceName") or "")
    return any(hint in blob for hint in MANGA_NAME_HINTS)


def classify(src):
    """Return (kind, reason). kind in novel|manga|video|web|unsupported.

    Scripts (@js:/<js>/java.*) no longer disqualify a source: the 0.14.0 client
    runs them in a restricted Rhino sandbox. We only *mark* them via hasScript so
    the pack is honest; the Python worker itself still never executes anything.
    """
    if not isinstance(src, dict):
        return "unsupported", "not an object"

    if cms_api(src):
        return "video", ""

    has_toc = (src.get("ruleToc") or {}).get("chapterList")
    has_content = (src.get("ruleContent") or {}).get("content")
    has_search = src.get("searchUrl") or (src.get("ruleSearch") or {}).get("bookList")

    # manga: explicit bookSourceType==2, or a manga keyword in group/name
    if looks_manga(src) and (has_toc or (src.get("ruleSearch") or {}).get("bookList")):
        return "manga", ""

    # novel: sourceUrl-style rule sources declare ruleArticles with singleUrl=false
    if isinstance(src.get("ruleArticles"), dict) and src.get("singleUrl") is False:
        return "novel", ""

    if has_toc and has_content and has_search:
        return "novel", ""

    # old sourceUrl/singleUrl style entries are web entry pages, not book sources
    if src.get("sourceUrl") or src.get("singleUrl") or (src.get("ruleExplore") and not has_toc):
        return "web", "web/rss entry, not a rule source"
    return "unsupported", "missing search/toc/content rule"


def stable_id(src):
    basis = (src.get("bookSourceUrl") or "").strip().lower() + "|" + (src.get("bookSourceName") or "").strip()
    return "yck-" + hashlib.sha256(basis.encode("utf-8")).hexdigest()[:16]


def normalise_rule(src):
    """Keep the rule object with a bookSourceUrl, plus the video/rule-articles shapes."""
    keys = ("bookSourceUrl", "bookSourceName", "bookSourceType", "bookSourceGroup",
            "searchUrl", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent",
            "ruleExplore", "exploreUrl", "enabledCookieJar",
            "ruleArticles", "rule", "sourceUrl", "singleUrl")
    rule = {k: src[k] for k in keys if k in src}
    rule.setdefault("bookSourceUrl", src.get("bookSourceUrl", ""))
    return rule


# ----------------------------------------------------------------------------- search URL

def build_search_request(search_url, keyword, base):
    """Parse Legado searchUrl ('url' or 'url,{json options}') into (url, method, body, headers)."""
    raw = (search_url or "").strip()
    options = {}
    if "," in raw:
        head, _, tail = raw.partition(",")
        try:
            options = json.loads(tail)
        except ValueError:
            options = {}
        raw = head.strip()
    from urllib.parse import quote
    url = raw.replace("{{key}}", quote(keyword, safe=""))
    if url.startswith("//"):
        url = "https:" + url
    elif url.startswith("/"):
        from urllib.parse import urlsplit
        parts = urlsplit(base)
        url = "%s://%s%s" % (parts.scheme, parts.netloc, url)
    method = (options.get("method") or "GET").upper()
    body = options.get("body")
    if isinstance(body, str):
        body = body.replace("{{key}}", quote(keyword, safe=""))
    headers = options.get("headers") or {}
    return url, method, body, headers


def absolute(url, base):
    from urllib.parse import urljoin
    if not url:
        return ""
    return urljoin(base, url.strip())


def decode(body):
    """Decode upstream bytes: honour a declared charset, else sniff gb18030/utf-8."""
    if isinstance(body, str):
        return body
    head = body[:4000].decode("ascii", "ignore").lower()
    m = re.search(r'charset=["\']?\s*([a-z0-9_-]+)', head)
    declared = m.group(1) if m else None
    for enc in ([declared] if declared else []) + ["utf-8", "gb18030"]:
        try:
            return body.decode(enc)
        except (LookupError, UnicodeDecodeError):
            continue
    return body.decode("utf-8", "replace")


def strip_tags(text):
    text = re.sub(r"<[^>]+>", "", text or "")
    text = re.sub(r"&nbsp;", " ", text)
    text = re.sub(r"&amp;", "&", text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


# ----------------------------------------------------------------------------- detection

class Detection:
    def __init__(self):
        self.depth = "none"
        self.search_hits = 0
        self.toc_chapters = 0
        self.content_chars = 0
        self.error = None
        self.sample_title = ""
        self.toc_via = ""
        self.content_via = ""
        self.image_url = ""
        self.play_url = ""

    def as_dict(self, previous=None):
        previous = previous or {}
        ok = self.depth in ("content", "toc", "search", "image", "playable")
        out = {
            "depth": self.depth,
            "searchHits": self.search_hits,
            "tocChapters": self.toc_chapters,
            "contentChars": self.content_chars,
            "sampleTitle": self.sample_title,
            "tocVia": self.toc_via,
            "contentVia": self.content_via,
            "lastError": self.error,
            "lastSuccessAt": now() if ok else previous.get("lastSuccessAt"),
            "lastAttemptAt": now(),
            "consecutiveFailures": 0 if ok else int(previous.get("consecutiveFailures", 0)) + 1,
        }
        if self.image_url:
            out["imageUrl"] = self.image_url
        if self.play_url:
            out["playUrl"] = self.play_url
        return out


CHAPTER_HINT = re.compile(r"第\s*[0-9０-９一二三四五六七八九十百千万零两]+\s*[章节回卷话]")

# Strong signals only: anti-scrape notices, paywall/app stubs, transcoding walls.
# Weak footer/pagination words (推荐阅读, 请点击下一页, 加入书签) are NOT here —
# they legitimately appear in real prose pages and are trimmed from fallbacks.
CONTENT_JUNK = ("本章没完", "退出转码", "转马", "请勿依赖搜索", "看后续", "下载QQ阅读",
                "上QQ阅读APP", "登录订阅", "本章字数", "更新时间：", "请使用[", "阅读APP",
                "请记住本站", "本站域名", "无弹窗")

NAV_LINE = re.compile(r"(推荐阅读|加入书签|手机版|上一[章页]|下一[章页]|目录|返回列表|"
                      r"最新[章更]|章节报错|投推荐票|求月票|下载APP|阅读模式|字体)")


def content_quality(text):
    """Reject bodies that are really notices, nav or paywall stubs, not prose."""
    if len(text) < MIN_CONTENT_CHARS:
        return False, "too short"
    hits = [m for m in CONTENT_JUNK if m in text]
    if hits:
        return False, "junk/notice/paywall: %s" % ",".join(hits[:3])
    cjk = len(re.findall(r"[\u4e00-\u9fff]", text))
    if cjk < MIN_CONTENT_CHARS * 0.5:
        return False, "low prose density"
    return True, ""


def trim_nav(text):
    lines = []
    for ln in text.split("\n"):
        s = ln.strip()
        if not s:
            continue
        if len(s) < 24 and NAV_LINE.search(s):
            continue
        lines.append(s)
    return "\n".join(lines)


def heuristic_chapters(root):
    """Bounded fallback when the declared chapterList matches nothing: pull anchors
    whose text looks like a chapter. Declared rules still win when they work."""
    anchors = [n for n in root.descendants() if n.tag == "a" and n.attrs.get("href")]
    chap = [a for a in anchors if CHAPTER_HINT.search(a.text())]
    return [(a, a.attrs["href"]) for a in chap]


def heuristic_content(root):
    """Bounded fallback for chapter body: the longest text block in a content-ish
    container, else the longest block outside page chrome. Never reads scripts."""
    def text_len(node):
        return len(strip_tags(node.text_nodes()))

    def container_hint(node):
        blob = ((node.attrs.get("id") or "") + " " + (node.attrs.get("class") or "")).lower()
        return any(h in blob for h in ("content", "txt", "nr", "read", "article", "chapter", "body"))

    best, best_len = None, 0
    for node in root.descendants():
        if node.tag in ("script", "style", "head", "html", "body", "nav", "footer", "header"):
            continue
        n = text_len(node)
        if container_hint(node) and n > best_len:
            best, best_len = node, n
    if best is None or best_len < MIN_CONTENT_CHARS:
        for node in root.descendants():
            if node.tag in ("script", "style", "head", "html", "body", "nav", "footer", "header", "a", "ul", "ol", "dl"):
                continue
            n = text_len(node)
            if n > best_len:
                best, best_len = node, n
    return strip_tags(trim_nav(best.text_nodes())) if best is not None else ""


# ---- image helpers (manga) ---------------------------------------------------

def _json_load(text):
    try:
        return json.loads(text)
    except ValueError:
        return None


def _json_first_list_of_dicts(data):
    """First JSON array whose items are dicts — the plausible search-result list."""
    found = []

    def rec(obj):
        if isinstance(obj, list):
            dicts = [x for x in obj if isinstance(x, dict)]
            if dicts:
                found.append(dicts)
            for x in obj:
                rec(x)
        elif isinstance(obj, dict):
            for v in obj.values():
                rec(v)

    rec(data)
    return found[0] if found else []


def _json_collect_images(obj, base, out, budget=400):
    if budget <= 0:
        return
    if isinstance(obj, str):
        if IMAGE_RE.search(obj):
            u = absolute(obj, base)
            if u:
                out.append(u)
    elif isinstance(obj, dict):
        for v in obj.values():
            _json_collect_images(v, base, out, budget - 1)
    elif isinstance(obj, list):
        for v in obj:
            _json_collect_images(v, base, out, budget - 1)


def collect_image_urls(text, base):
    """Declaratively pull candidate image URLs from an HTML or JSON chapter body."""
    urls = []
    # <img src|data-src|data-original|data-lazy-src|data-url> ...
    for m in re.finditer(r"<img\b[^>]*>", text or "", re.I):
        tag = m.group(0)
        for attr in ("data-src", "data-original", "data-lazy-src", "data-url", "src"):
            mm = re.search(attr + r'\s*=\s*["\']([^"\']+)["\']', tag, re.I)
            if mm:
                u = absolute(mm.group(1), base)
                if u and IMAGE_RE.search(u):
                    urls.append(u)
                break
    # JSON-ish "url"/"src"/"image"/"link" fields
    for m in re.finditer(r'"(?:url|src|image|images|link|page_url)"\s*:\s*"([^"]+)"', text or ""):
        u = absolute(m.group(1), base)
        if u and IMAGE_RE.search(u):
            urls.append(u)
    # raw absolute image URLs anywhere
    for m in re.finditer(r"https?://[^\s\"'\\)<>]+", text or ""):
        u = m.group(0)
        if IMAGE_RE.search(u):
            urls.append(u)
    # de-dup preserving order
    seen, out = set(), []
    for u in urls:
        if u and u not in seen:
            seen.add(u)
            out.append(u)
    return out


def _fetch_text(url, headers=None):
    status, raw, final = boundedhttp.get(url, headers=headers or {})
    return final, decode(raw)


def _probe_image(urls, referer):
    """Return (url, ctype, head_bytes) of the first URL that really returns image/*."""
    tried = 0
    for u in urls:
        if tried >= 4:
            break
        tried += 1
        try:
            status, ctype, head, _final = boundedhttp.get_probe(
                u, max_bytes=65536, headers={"Referer": referer} if referer else None)
        except Exception:
            continue
        if status == 200 and ctype.lower().startswith("image/") and head:
            return u, ctype, len(head)
    return None


def _manga_chapter_links(root, detail_final, declared_sel):
    """Return (links, via). via='declared' only when the source's own declarative
    chapterList produced the links; otherwise 'heuristic'."""
    links, via = [], ""
    if declared_sel and "@js:" not in declared_sel and "<js>" not in declared_sel:
        try:
            nodes = legado.select(root, legado._resolve_selector(declared_sel))
            for ch in nodes:
                anchors = [ch] if ch.tag == "a" else legado.select(ch, "a")
                for a in anchors:
                    if a.attrs.get("href"):
                        links.append(a.attrs["href"])
                        break
        except legado.ScriptRefused:
            links = []
        if links:
            via = "declared"
    if not links:
        anchors = [a for a in root.descendants() if a.tag == "a" and a.attrs.get("href")]
        chap = [a for a in anchors if CHAPTER_HINT.search(a.text())]
        pool = chap or anchors
        links = [a.attrs["href"] for a in pool]
        via = "heuristic" if links else ""
    seen, out = set(), []
    for h in links:
        u = absolute(h, detail_final)
        if u and u not in seen and not u.lower().startswith("javascript"):
            seen.add(u)
            out.append(u)
    return out, via


def _item_book_url(item, rule_search, base):
    """Resolve a search-result row into a detail/book URL (Node or dict)."""
    book_rule = (rule_search or {}).get("bookUrl", "")
    if isinstance(item, dict):
        for key in ("url", "href", "bookUrl", "link", "info_url"):
            v = item.get(key)
            if isinstance(v, str) and v.strip():
                return absolute(v, base)
        return ""
    if not isinstance(item, legado.Node):
        return ""
    if book_rule and "@js:" not in book_rule and "<js>" not in book_rule:
        try:
            val = legado.evaluate(book_rule, item)
        except legado.ScriptRefused:
            val = ""
        if val:
            return absolute(val, base)
    if item.tag == "a" and item.attrs.get("href"):
        return absolute(item.attrs["href"], base)
    anchors = legado.select(item, "a")
    for a in anchors:
        if a.attrs.get("href"):
            return absolute(a.attrs["href"], base)
    return ""


# ---- dispatch ----------------------------------------------------------------

def detect(src, previous=None):
    """Dispatch a bounded real-network probe by source kind."""
    kind, _reason = classify(src)
    if kind == "video":
        return detect_video(src, previous)
    if kind == "manga":
        return detect_manga(src, previous)
    return detect_novel(src, previous)


def detect_video(src, previous=None):
    """CMS JSON probe: ?ac=detail&wd=<kw> returns list, with an http(s) play url."""
    det = Detection()
    api = cms_api(src) or (src.get("bookSourceUrl") or "")
    if not api:
        det.error = "no cms api"
        return det
    if not api.startswith("http"):
        api = "https://" + api
    started = time.monotonic()
    for keyword in VIDEO_PROBE_QUERIES:
        if time.monotonic() - started > 60:
            break
        sep = "&" if "?" in api else "?"
        from urllib.parse import quote
        url = api + sep + "ac=detail&wd=" + quote(keyword, safe="")
        try:
            status, raw, final = boundedhttp.get(url, headers={
                "Accept": "application/json", "Referer": api})
        except Exception as exc:
            det.error = "video search: %s" % type(exc).__name__
            continue
        data = _json_load(decode(raw))
        if not isinstance(data, dict):
            det.error = "video api non-json"
            continue
        lst = data.get("list")
        if not isinstance(lst, list) or not lst:
            det.error = det.error or "video api empty list"
            continue
        det.search_hits = len(lst)
        det.depth = "search"
        det.sample_title = (lst[0].get("vod_name") or "") if isinstance(lst[0], dict) else ""
        play = _first_play_url(lst)
        if not play:
            vod_id = lst[0].get("vod_id") if isinstance(lst[0], dict) else None
            if vod_id:
                ids_url = api + sep + "ac=detail&ids=%s" % vod_id
                try:
                    _s, raw2, _f = boundedhttp.get(ids_url, headers={"Accept": "application/json"})
                    d2 = _json_load(decode(raw2))
                    if isinstance(d2, dict):
                        play = _first_play_url(d2.get("list") or [])
                except Exception:
                    play = ""
        if play:
            det.play_url = play
            det.depth = "playable"
            det.error = None
            return det
        det.error = "no http play url in vod_play_url"
    return det


def _first_play_url(rows):
    for it in rows or []:
        if not isinstance(it, dict):
            continue
        pu = str(it.get("vod_play_url") or "")
        m = re.search(r"https?://[^\s$#]+", pu)
        if m:
            return m.group(0)
    return ""


def detect_manga(src, previous=None):
    """Probe: search -> detail -> toc has chapters -> chapter page yields real image bytes."""
    det = Detection()
    base = src.get("bookSourceUrl") or ""
    if base and not base.startswith("http"):
        base = "https://" + base
    if not base:
        det.error = "no base url"
        return det
    started = time.monotonic()

    def over_budget():
        return time.monotonic() - started > 75

    search_url = src.get("searchUrl") or ""
    if "@js:" in search_url or "<js>" in search_url:
        det.error = "manga searchUrl is script-based; cannot probe declaratively"
        return det
    if not search_url:
        det.error = "no searchUrl"
        return det

    rule_search = src.get("ruleSearch") or {}
    book_list_sel = (rule_search.get("bookList") or "").strip()
    final = base
    items = []
    for keyword in MANGA_PROBE_QUERIES:
        if over_budget():
            break
        url, method, body, headers = build_search_request(search_url, keyword, base)
        # fill the common paging placeholder; refuse genuinely scripted templates
        url = (url or "").replace("{{page}}", "1").replace("{{page-1}}", "0")
        if not url or "{{" in url:
            det.error = det.error or "searchUrl unresolved/scripted template"
            continue
        hdrs = {"Referer": base, **(headers or {})}
        try:
            if method == "POST":
                status, raw, final = boundedhttp.post(url, body or "", headers=hdrs)
            else:
                status, raw, final = boundedhttp.get(url, headers=hdrs)
        except Exception as exc:
            det.error = "manga search: %s" % type(exc).__name__
            continue
        text = decode(raw)
        items = []
        json_body = _json_load(text)
        if book_list_sel and not book_list_sel.startswith("$") \
                and not book_list_sel.lower().startswith("@json") \
                and "@js:" not in book_list_sel and "<js>" not in book_list_sel:
            try:
                root = legado.parse(text)
                items = legado.select(root, legado._resolve_selector(book_list_sel.split("&&")[0].strip()))
            except legado.ScriptRefused:
                items = []
        if not items and json_body is not None:
            items = _json_first_list_of_dicts(json_body)
        if not items:
            root = legado.parse(text)
            items = [a for a in root.descendants() if a.tag == "a" and a.attrs.get("href")][:60]
        if items:
            det.search_hits = len(items)
            det.depth = "search"
            break
    if det.depth != "search":
        det.error = det.error or "manga search returned nothing"
        return det

    # ---- detail -> toc
    first = items[0]
    detail_url = _item_book_url(first, rule_search, final)
    if not detail_url:
        detail_url = final
    try:
        detail_final, detail_text = _fetch_text(detail_url, headers={"Referer": base})
    except Exception as exc:
        det.error = "manga detail: %s" % type(exc).__name__
        return det
    det.sample_title = det.sample_title or (first.text().strip() if isinstance(first, legado.Node) else "")

    detail_root = legado.parse(detail_text)
    chap_sel = (src.get("ruleToc") or {}).get("chapterList") or ""
    chap_links, via = _manga_chapter_links(detail_root, detail_final, chap_sel)
    det.toc_chapters = len(chap_links)
    det.toc_via = via
    if not chap_links:
        det.error = "toc had no chapter links"
        return det
    det.depth = "toc"

    # ---- chapter page -> image bytes
    rule_content = (src.get("ruleContent") or {}).get("content") or ""
    for chapter_url in chap_links[:3]:
        if over_budget():
            break
        try:
            chap_final, chap_text = _fetch_text(chapter_url, headers={"Referer": base})
        except Exception as exc:
            det.error = "chapter fetch: %s" % type(exc).__name__
            continue
        candidates = []
        # declared declarative image rule (no scripts)
        if rule_content and "@js:" not in rule_content and "<js>" not in rule_content:
            try:
                root = legado.parse(chap_text)
                val = legado.evaluate(rule_content, root, chap_final)
                if val:
                    candidates.extend(collect_image_urls(val, chap_final))
            except legado.ScriptRefused:
                pass
        candidates.extend(collect_image_urls(chap_text, chap_final))
        json_body = _json_load(chap_text)
        if json_body is not None:
            _json_collect_images(json_body, chap_final, candidates)
        seen, ordered = set(), []
        for u in candidates:
            if u and u not in seen:
                seen.add(u)
                ordered.append(u)
        if not ordered:
            det.error = "no image url on chapter page"
            continue
        hit = _probe_image(ordered, base)
        if hit:
            det.image_url = hit[0]
            det.content_chars = hit[2]
            det.content_via = "image-bytes"
            det.depth = "image"
            det.error = None
            return det
        det.error = "image url did not return image bytes"
    return det


def detect_novel(src, previous=None):
    """Legacy novel chain: search -> toc -> body text (>=200 chars)."""
    det = Detection()
    started = time.monotonic()
    def over_budget():
        return time.monotonic() - started > 75     # per-source wall-clock cap
    base = src.get("bookSourceUrl") or ""
    from urllib.parse import urlsplit
    if base and not base.startswith("http"):
        base = "https://" + base
    try:
        # ---- search
        rule_search = src.get("ruleSearch") or {}
        book_list_sel = rule_search.get("bookList") or ""
        if not book_list_sel or "@js:" in book_list_sel or "<js>" in book_list_sel:
            det.error = "no declarative search list rule"
            return det
        html = None
        for keyword in PROBE_QUERIES:
            if over_budget():
                break
            url, method, body, headers = build_search_request(src.get("searchUrl"), keyword, base)
            if not url:
                det.error = "no searchUrl"
                return det
            hdrs = {"Referer": base, "Accept-Language": "zh-CN,zh;q=0.9", **(headers or {})}
            try:
                if method == "POST":
                    status, raw, final = boundedhttp.post(url, body or "", headers=hdrs)
                else:
                    status, raw, final = boundedhttp.get(url, headers=hdrs)
            except urllib_error.HTTPError as exc:
                det.error = "search fetch: HTTP %s" % exc.code
                if exc.code in (400, 401, 403, 404, 410, 451, 502, 503):
                    break  # hard block; other keywords will not help
                continue
            except Exception as exc:  # network/blocked/dns/ssl
                det.error = "search fetch: %s" % type(exc).__name__
                continue
            html = decode(raw)
            root = legado.parse(html)
            items = legado.select(root, legado._resolve_selector(book_list_sel.split("&&")[0].strip()))
            if items:
                det.depth = "search"
                det.search_hits = len(items)
                break
        if det.depth == "none":
            det.error = det.error or "search returned no items"
            return det

        # ---- first item -> name + book url
        root = legado.parse(html)
        first = items[0]
        name = legado.evaluate(rule_search.get("name", "text"), first) or ""
        book_url = legado.evaluate(rule_search.get("bookUrl", "a@href"), first) or ""
        det.sample_title = name.strip()
        book_url = absolute(book_url, final)
        if not book_url:
            det.error = "no book url from search"
            return det

        # ---- toc
        rule_toc = src.get("ruleToc") or {}
        rule_info = src.get("ruleBookInfo") or {}
        detail_url = book_url
        status, detail_raw, detail_final = boundedhttp.get(book_url)
        detail_root = legado.parse(decode(detail_raw))
        toc_sel = rule_info.get("tocUrl")
        if toc_sel and "@js:" not in toc_sel:
            toc_url = legado.evaluate(toc_sel, detail_root)
            if toc_url:
                detail_url = absolute(toc_url, detail_final)
                status, detail_raw, detail_final = boundedhttp.get(detail_url)
                detail_root = legado.parse(decode(detail_raw))
        chap_sel = rule_toc.get("chapterList") or ""
        if not chap_sel or "@js:" in chap_sel:
            det.error = "no declarative chapterList"
            return det
        chapters = legado.select(detail_root, legado._resolve_selector(chap_sel))
        # a chapter entry may itself be an <a> or contain one
        chap_links = []
        for ch in chapters:
            if ch.tag == "a" and ch.attrs.get("href"):
                chap_links.append((ch, ch.attrs["href"]))
            else:
                anchors = legado.select(ch, "a")
                for a in anchors:
                    if a.attrs.get("href"):
                        chap_links.append((a, a.attrs["href"]))
                        break
        det.toc_via = "declared"
        if not chap_links:
            # declared rule matched nothing (site structure drift): bounded fallback
            chap_links = heuristic_chapters(detail_root)
            det.toc_via = "heuristic" if chap_links else ""
        det.toc_chapters = len(chap_links)
        if not chap_links:
            det.error = "toc had no chapter links"
            return det
        det.depth = "toc"

        # ---- content of the first chapter
        rule_content = src.get("ruleContent") or {}
        content_sel = rule_content.get("content") or ""
        if not content_sel or "@js:" in content_sel:
            det.error = "no declarative content rule"
            return det
        # try a few chapters to survive a paywalled first chapter
        for anchor, href in chap_links[:3]:
            chapter_url = absolute(href, detail_final)
            try:
                status, chap_raw, chap_final = boundedhttp.get(chapter_url)
            except Exception as exc:
                det.error = "chapter fetch: %s" % type(exc).__name__
                continue
            chap_root = legado.parse(decode(chap_raw))
            # strip @css: prefix then evaluate the content selector as selector@html
            sel = content_sel
            if sel.startswith("@css:"):
                sel = sel[len("@css:"):]
            head, _, attr = sel.rpartition("@")
            value = ""
            if head:
                nodes = legado.select(chap_root, legado._resolve_selector(head))
                if nodes:
                    value = legado._extract_attr(nodes[0], attr or "html")
            else:
                nodes = legado.select(chap_root, legado._resolve_selector(sel))
                if nodes:
                    value = nodes[0].text_nodes()
            text = trim_nav(strip_tags(value))
            det.content_via = "declared"
            good, _why = content_quality(text)
            if not good or any(m in value for m in ("<script", "function(", "@js:")):
                # declared content failed (drift/paywall/notice): bounded fallback
                text = heuristic_content(chap_root)
                det.content_via = "heuristic"
                good, _why = content_quality(text)
            if good:
                det.content_chars = len(text)
                det.depth = "content"
                det.error = None
                return det
            det.error = _why
        det.error = det.error or "chapter body too short or script-like"
        return det
    except boundedhttp.Blocked as exc:
        det.error = "blocked: %s" % exc
        return det
    except legado.ScriptRefused:
        det.error = "script rule refused"
        return det
    except Exception as exc:
        det.error = "%s: %s" % (type(exc).__name__, str(exc)[:80])
        return det


# ----------------------------------------------------------------------------- isolation

def detect_isolated(src, previous=None, timeout=None):
    """Run detect() in a child process so a slow/held HTTP connection cannot stall the batch.

    Threads cannot interrupt a blocking response.read() in CPython; a process can be killed.
    """
    import multiprocessing
    timeout = float(timeout or os.environ.get("PK_SOURCE_TIMEOUT_SEC", "90"))
    parent, child = multiprocessing.Pipe(duplex=False)
    proc = multiprocessing.Process(target=_detect_child, args=(src, previous, child), daemon=True)
    proc.start()
    child.close()
    det = Detection()
    if parent.poll(timeout):
        try:
            payload = parent.recv()
        except EOFError:
            payload = None
        if isinstance(payload, dict):
            det.depth = payload.get("depth", "none")
            det.search_hits = int(payload.get("searchHits", 0) or 0)
            det.toc_chapters = int(payload.get("tocChapters", 0) or 0)
            det.content_chars = int(payload.get("contentChars", 0) or 0)
            det.error = payload.get("error")
            det.sample_title = payload.get("sampleTitle", "")
            det.toc_via = payload.get("tocVia", "")
            det.content_via = payload.get("contentVia", "")
            det.image_url = payload.get("imageUrl", "")
            det.play_url = payload.get("playUrl", "")
    else:
        det.error = "source timed out after %ss" % timeout
    if proc.is_alive():
        proc.kill()
    proc.join(2)
    return det


def _detect_child(src, previous, pipe):
    try:
        det = detect(src, previous)
        pipe.send({
            "depth": det.depth, "searchHits": det.search_hits, "tocChapters": det.toc_chapters,
            "contentChars": det.content_chars, "error": det.error, "sampleTitle": det.sample_title,
            "tocVia": det.toc_via, "contentVia": det.content_via,
            "imageUrl": det.image_url, "playUrl": det.play_url,
        })
    except BaseException as exc:
        pipe.send({"depth": "none", "error": "%s: %s" % (type(exc).__name__, str(exc)[:120])})
    finally:
        pipe.close()


# ----------------------------------------------------------------------------- main

def load_candidates(collection_ids, offline=None):
    seen = {}
    offline_raw = open(offline, encoding="utf-8").read() if offline else None
    for cid, desc in collection_ids:
        try:
            raw = offline_raw if offline else boundedhttp.get(
                COLLECTION_URL % cid, max_bytes=16 * 1024 * 1024)[1]
            data = json.loads(raw)
            arr = data if isinstance(data, list) else data.get("sources") or [data]
        except Exception as exc:
            print("collection %s failed: %s" % (cid, type(exc).__name__), file=sys.stderr)
            continue
        for src in arr:
            if not isinstance(src, dict):
                continue
            sid = stable_id(src)
            if sid not in seen:
                seen[sid] = src
    # Fixed CMS endpoints are not part of the yckceo collections, but they belong in every
    # published package: without this a nightly run drops all video sources from the bundle.
    # Offline mode means "this file only" (used by the hermetic selftest), so it is skipped there.
    if offline is None:
        for entry in CMS_SEED:
            seen.setdefault(entry["id"], entry)
    return seen


# Public Apple CMS endpoints shipped with the bundle. They are plain JSON APIs, not Legado
# rules, so they are carried here rather than fetched from the collection.
CMS_SEED = [
    {"id": "video-ffzy", "bookSourceName": "非凡资源", "type": "cms",
     "api": "https://cj.ffzyapi.com/api.php/provide/vod/", "bookSourceGroup": "视频"},
    {"id": "video-jisu", "bookSourceName": "极速资源", "type": "cms",
     "api": "https://jszyapi.com/api.php/provide/vod/", "bookSourceGroup": "视频"},
    {"id": "video-guangsu", "bookSourceName": "光速资源", "type": "cms",
     "api": "https://api.guangsuapi.com/api.php/provide/vod/", "bookSourceGroup": "视频"},
    {"id": "video-hongniu", "bookSourceName": "红牛资源", "type": "cms",
     "api": "https://www.hongniuzy2.com/api.php/provide/vod/", "bookSourceGroup": "视频"},
]


# Refuse adult / known-pirate-paywall sources outright: we do not propagate
# pirated or pornographic content, and we never bypass paywalls or logins.
POLICY_BLACKLIST = ("po18", "18禁", "版主", "diyibanzhu", "海棠", "htwenxe", "肉文", "popofree")


def policy_excluded(src):
    url = (src.get("bookSourceUrl") or "").lower()
    name = (src.get("bookSourceName") or "").lower()
    blob = url + " " + name
    return next((m for m in POLICY_BLACKLIST if m in blob), None)


def _verified(kind, det):
    """Whether this run observed a complete, honest chain for the source's kind."""
    if kind == "novel":
        return det.depth == "content" and det.toc_via == "declared" and det.content_via == "declared"
    if kind == "manga":
        return det.depth == "image"
    if kind == "video":
        return det.depth == "playable"
    return False


def _prev_solid(prev, kind):
    """Only a previously kind-consistent verified entry may survive a transient failure."""
    if not prev:
        return False
    det = prev.get("detection") or {}
    if prev.get("kind") and prev.get("kind") != kind:
        return False
    if kind == "novel":
        return (det.get("depth") == "content"
                and det.get("tocVia") == "declared"
                and det.get("contentVia") == "declared")
    if kind == "manga":
        return det.get("depth") == "image"
    if kind == "video":
        return det.get("depth") == "playable"
    return False


def _publish_entry(sid, src, kind, det, prev_det):
    if kind == "video":
        api = cms_api(src) or (src.get("bookSourceUrl") or "")
        rule = {"type": "cms", "api": api, "name": src.get("bookSourceName", "")}
        url = api
    else:
        rule = normalise_rule(src)
        url = (src.get("bookSourceUrl", "") or "").split("##", 1)[0].strip()
    return {
        "id": sid,
        "name": src.get("bookSourceName", ""),
        "kind": kind,
        "url": url,
        "rule": rule,
        "provenance": {
            "collection": "yckceo",
            "group": src.get("bookSourceGroup", ""),
            "hasScript": source_has_script(src),
        },
        "detection": det.as_dict(prev_det),
    }


def run(data_dir, collection_ids, offline=None, max_test=25, min_healthy=1):
    prev_path = os.path.join(data_dir, "content", "book-sources.json")
    status_path = os.path.join(data_dir, "content", "book-sources-status.json")
    previous = read_json(prev_path, {})
    prev_by_id = {s.get("id"): s for s in previous.get("sources", []) if isinstance(s, dict)}

    candidates = load_candidates(collection_ids, offline)

    accepted, rejected = [], []
    kind_counts = {"novel": 0, "manga": 0, "video": 0}
    for sid, src in candidates.items():
        bad = policy_excluded(src)
        kind, reason = classify(src) if not bad else ("excluded", "policy: %s (adult/paywall pirate)" % bad)
        if kind in kind_counts:
            accepted.append((sid, src, kind))
            kind_counts[kind] += 1
        else:
            rejected.append({"id": sid, "name": src.get("bookSourceName", ""), "kind": kind,
                             "reason": reason, "url": src.get("bookSourceUrl", "")})

    # Bound the real-network test: re-test known-good first, then untested up to budget.
    def rank(item):
        return 0 if item[0] in prev_by_id else 1
    accepted.sort(key=rank)
    to_test = accepted[:max_test]

    sources_out = []
    verified = kept = carried = 0
    report = []
    tested_ids = set()
    run_deadline = time.monotonic() + float(os.environ.get("PK_RUN_BUDGET_SEC", "840"))
    for sid, src, kind in to_test:
        tested_ids.add(sid)
        if time.monotonic() >= run_deadline:
            report.append({"name": src.get("bookSourceName", ""), "kind": kind, "depth": "skipped",
                           "error": "run budget exhausted before this source"})
            continue
        prev = prev_by_id.get(sid, {})
        prev_det = prev.get("detection") or {}
        det = detect_isolated(src, prev_det)
        record = {"name": src.get("bookSourceName", ""), "kind": kind, "depth": det.depth,
                  "searchHits": det.search_hits, "tocChapters": det.toc_chapters,
                  "contentChars": det.content_chars, "error": det.error}
        if det.image_url:
            record["imageUrl"] = det.image_url
        if det.play_url:
            record["playUrl"] = det.play_url

        if _verified(kind, det):
            verified += 1
            sources_out.append(_publish_entry(sid, src, kind, det, prev_det))
            report.append(record)
        elif _prev_solid(prev, kind):
            # A transient failure keeps the last declarative/verified entry. The stored
            # detection must keep its verified depth: overwriting it with the failed probe
            # would make _prev_solid reject the entry on the very next failure.
            kept += 1
            prev = dict(prev)
            merged = dict(prev_det)
            merged["lastAttemptAt"] = now()
            merged["lastError"] = det.error
            merged["consecutiveFailures"] = int(prev_det.get("consecutiveFailures", 0)) + 1
            prev["detection"] = merged
            sources_out.append(prev)
            record["depth"] = "kept-last-good"
            report.append(record)
        else:
            record["error"] = det.error or ("%s chain not verified; depth=%s" % (kind, det.depth))
            report.append(record)

    # Everything already in the published package that this run did not re-test (budget or
    # candidate-set drift) is carried over untouched. Without this step a run that tests a
    # subset silently shrinks the bundle — the bug that dropped all four video sources.
    out_ids = {s.get("id") for s in sources_out}
    for sid, prev in prev_by_id.items():
        if sid in out_ids or sid in tested_ids:
            continue
        if prev.get("kind") not in ("novel", "manga", "video"):
            continue
        sources_out.append(prev)
        carried += 1
        report.append({"name": prev.get("name", ""), "kind": prev.get("kind"),
                       "depth": "carried", "error": ""})

    ok = verified >= min_healthy
    status = {
        "lastAttemptAt": now(),
        "lastSuccessAt": now() if ok else previous.get("maintenance", {}).get("lastSuccessAt"),
        "candidatesSeen": len(candidates),
        "acceptedNovel": kind_counts["novel"],
        "acceptedManga": kind_counts["manga"],
        "acceptedVideo": kind_counts["video"],
        "tested": len(to_test),
        "verifiedNow": verified,
        "keptLastGood": kept,
        "carriedOver": carried,
        "publishedSources": len(sources_out),
        "detectionPolicy": ("publish only verified chains: novel search->toc->content(declared); "
                            "manga search->detail->toc->image bytes; video CMS list->playable. "
                            "single failure keeps last-good; all fail keeps old package"),
        "rejected": rejected,
        "results": report,
        "published": False,
        "reason": "",
    }

    if not ok:
        status["reason"] = "no source reached a full verified chain; keeping last-good package"
        atomic_write(status_path, json.dumps(status, ensure_ascii=False, indent=2).encode("utf-8"))
        print(json.dumps({"ok": False, "published": False, "reason": status["reason"],
                          "tested": len(to_test), "results": report}, ensure_ascii=False))
        return 1

    package = {
        "schemaVersion": 1,
        "name": "拾用精选",
        "updatedAt": now(),
        "sources": sources_out,
        "note": ("声明式 Legado 子集源（小说/漫画/视频三类）；每源检测深度为实测链路"
                 "（search 搜索有结果 / toc 目录有章节 / content 正文可取且有界 / "
                 "image 章节可解析出真实图片字节 / playable CMS 有可播放地址），非成功率承诺。"
                 "含 @js/<js>/java 脚本的源已标记 hasScript 并允许进入候选（客户端受限脚本可执行），"
                 "但本抓取服务从不执行任何上游脚本；成人/付费盗版候选仍被隔离。"
                 "仅下发规则与来源/检测元数据，不托管正文、不转发视频、不绕过付费登录验证码。"),
        "maintenance": {
            "lastAttemptAt": status["lastAttemptAt"],
            "lastSuccessAt": status["lastSuccessAt"],
            "candidatesSeen": status["candidatesSeen"],
            "verifiedNow": verified,
            "keptLastGood": kept,
            "carriedOver": carried,
            "detectionDepthLegend": {
                "search": "搜索有结果",
                "toc": "目录有章节",
                "content": "正文可取且有界",
                "image": "章节可解析出真实图片字节",
                "playable": "CMS 有可播放地址",
            },
        },
    }
    atomic_write(prev_path, json.dumps(package, ensure_ascii=False, indent=2).encode("utf-8"))
    status["published"] = True
    status["reason"] = "published %d verified sources (%d kept last-good, %d carried over)" % (
        verified, kept, carried)
    atomic_write(status_path, json.dumps(status, ensure_ascii=False, indent=2).encode("utf-8"))
    print(json.dumps({"ok": True, "published": True, "sources": len(sources_out),
                      "verified": verified, "kept": kept, "tested": len(to_test), "results": report},
                     ensure_ascii=False))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", default="/data/pocketkit-hub")
    parser.add_argument("--offline-collections", help="local JSON file instead of fetching collections")
    parser.add_argument("--collection", action="append", default=None,
                        help="collection id to track (repeatable); defaults to built-in list")
    parser.add_argument("--max-test", type=int, default=25, help="max sources to real-network test per run")
    args = parser.parse_args(argv)
    ids = [(c, "cli") for c in args.collection] if args.collection else DEFAULT_COLLECTIONS
    return run(args.data_dir, ids, offline=args.offline_collections, max_test=args.max_test)


if __name__ == "__main__":
    raise SystemExit(main())
