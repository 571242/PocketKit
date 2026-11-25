"""Declarative Legado-subset extraction: a tolerant HTML subset parser,
CSS-ish selectors and the field expression grammar the bundled sources use.

Supported (deliberately small; anything else is reported, never executed):
  selectors:  tag  #id  .class  [attr=val]  [attr*=val]
              :contains(text)  :eq(n)  :has(sel)   descendant by spaces
  steps:      sel@sel@attr            (attr: text html textNodes href src content …)
  prefixes:   class.X  tag.X  id.X  text.LABEL   @css:…
  suffix:     ##regex   and combined with ||  (first non-empty) or && (concat)
  scripts:    @js: and <js> blocks are DETECTED and refused, never run
"""
import re

VOID = {"br", "img", "hr", "meta", "link", "input", "area", "base", "col", "embed",
        "param", "source", "track", "wbr"}
RAW = {"script", "style", "textarea", "title"}
TOKEN_RE = re.compile(r"""
    (?P<tag>[a-zA-Z][a-zA-Z0-9]*)
  | \#(?P<id>[A-Za-z0-9_-]+)
  | \.(?P<cls>[A-Za-z0-9_-]+)
  | \[(?P<attr>[a-zA-Z0-9_:-]+)\s*(?P<op>[*^$]?=)\s*"?(?P<val>[^"\]]*)"?\]
  | :contains\((?P<contains>[^)]*)\)
  | :eq\((?P<eq>\d+)\)
  | :has\((?P<has>[^)]*)\)
""", re.X)


class Node:
    __slots__ = ("tag", "attrs", "children", "parent", "_text")

    def __init__(self, tag, attrs=None, parent=None):
        self.tag = tag
        self.attrs = attrs or {}
        self.children = []
        self.parent = parent
        self._text = []

    def text(self):
        out = list(self._text)
        for child in self.children:
            if child.tag not in RAW:
                out.append(child.text())
        return "".join(out)

    def text_nodes(self):
        out = list(self._text)
        for child in self.children:
            if child.tag not in RAW:
                out.append(child.text_nodes())
                if child.tag == "br":
                    out.append("\n")
        return "".join(out)

    def descendants(self):
        for child in self.children:
            yield child
            yield from child.descendants()


def parse(html):
    root = Node("#root")
    stack = [root]
    i, n = 0, len(html)
    while i < n:
        lt = html.find("<", i)
        if lt < 0:
            stack[-1]._text.append(html[i:])
            break
        if lt > i:
            stack[-1]._text.append(html[i:lt])
        if html.startswith("<!--", lt):
            end = html.find("-->", lt + 4)
            i = n if end < 0 else end + 3
            continue
        if html.startswith("<!", lt) or html.startswith("<?", lt):
            end = html.find(">", lt)
            i = n if end < 0 else end + 1
            continue
        gt = html.find(">", lt)
        if gt < 0:
            stack[-1]._text.append(html[lt:])
            break
        head = html[lt + 1:gt]
        i = gt + 1
        if head.startswith("/"):
            tag = head[1:].strip().lower()
            for depth in range(len(stack) - 1, 0, -1):
                if stack[depth].tag == tag:
                    del stack[depth:]
                    break
            continue
        selfclose = head.endswith("/")
        head = head.rstrip("/").strip()
        m = re.match(r"([a-zA-Z][a-zA-Z0-9]*)", head)
        if not m:
            continue
        tag = m.group(1).lower()
        attrs = {}
        for am in re.finditer(r"""([a-zA-Z0-9_:-]+)(?:\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+)))?""", head[m.end():]):
            val = am.group(3) if am.group(3) is not None else am.group(4) if am.group(4) is not None else am.group(5)
            attrs[am.group(1).lower()] = val if val is not None else ""
        node = Node(tag, attrs, stack[-1])
        stack[-1].children.append(node)
        if tag in RAW and not selfclose:
            close = re.search(r"</%s\s*>" % tag, html[i:], re.I)
            inner_end = i + close.start() if close else n
            if tag in ("textarea", "title"):
                node._text.append(html[i:inner_end])
            i = (i + close.end()) if close else n
            continue
        if tag in VOID or selfclose:
            continue
        stack.append(node)
    return root


def _match_simple(node, token):
    """True if node satisfies every sub-constraint in a compound token (p.meta, #id.cls:contains)."""
    pos, seen = 0, False
    for m in TOKEN_RE.finditer(token):
        if m.start() != pos:
            return None  # gap => unsupported syntax in this token
        pos = m.end()
        seen = True
        g = m.groupdict()
        if g["tag"] and node.tag != g["tag"].lower():
            return None
        if g["id"] and node.attrs.get("id") != g["id"]:
            return None
        if g["cls"]:
            if g["cls"] not in (node.attrs.get("class") or "").split():
                return None
        if g["attr"]:
            got = node.attrs.get(g["attr"], "")
            want, op = g["val"], g["op"]
            if op == "=" and got != want:
                return None
            if op == "*=" and want not in got:
                return None
            if op == "^=" and not got.startswith(want):
                return None
            if op == "$=" and not got.endswith(want):
                return None
        if g["contains"] is not None and g["contains"] not in node.text():
            return None
        if g["has"] and not _select(node, g["has"]):
            return None
    if not seen or pos != len(token):
        return None
    return node


def _tokenize(selector):
    # split on spaces, but keep :contains(...) / :has(...) together
    tokens, buf, depth = [], "", 0
    for ch in selector.strip():
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch.isspace() and depth == 0:
            if buf:
                tokens.append(buf)
                buf = ""
        else:
            buf += ch
    if buf:
        tokens.append(buf)
    return tokens


def _select(scope, selector):
    nodes = [scope] if isinstance(scope, Node) else list(scope)
    direct = False
    for token in _tokenize(selector):
        if token == ">":
            direct = True
            continue
        if token.startswith("@"):
            return []
        idx = None
        m_idx = re.search(r"\.(-?\d+)$", token)
        if m_idx:
            idx = int(m_idx.group(1))
            token = token[:m_idx.start()]
        m_eq = re.search(r":eq\((\d+)\)", token)
        eq = None
        if m_eq:
            eq = int(m_eq.group(1))
            token = token[:m_eq.start()] + token[m_eq.end():]
        if direct:
            found = [ch for src in nodes for ch in src.children if _match_simple(ch, token)]
            direct = False
        else:
            found = [node for src in nodes for node in src.descendants() if _match_simple(node, token)]
        if eq is not None:
            found = found[eq:eq + 1]
        if idx is not None:
            found = found[idx:idx + 1] if idx >= 0 else found[idx:]
        nodes = found
        if not nodes:
            return []
    return nodes


def select(root, selector):
    selector = selector.strip()
    if selector.startswith("@"):
        return []  # @css: handled upstream by stripping prefix; bare @ is unsupported
    return _select(root, selector)


def _first(node_list):
    return node_list[0] if node_list else None


def _extract_attr(node, attr):
    if attr == "text":
        return node.text().strip()
    if attr == "textNodes":
        return node.text_nodes().strip()
    if attr == "ownText":
        return "".join(node._text).strip()
    if attr == "html" or attr == "htmlNodes":
        return _inner_html(node).strip()
    if attr == "content":
        return (node.attrs.get("content") or node.text()).strip()
    return (node.attrs.get(attr) or "").strip()


def _inner_html(node):
    # only used for content extraction; a shallow render is enough for text
    out = list(node._text)
    for child in node.children:
        out.append("<%s>" % child.tag + _inner_html(child) + "</%s>" % child.tag)
    return "".join(out)


def _compile_regex(spec):
    if not spec:
        return None
    # Legado: '##pattern' removes matches; '##pattern##replacement' replaces with replacement.
    body = spec[2:] if spec.startswith("##") else spec
    if "##" in body:
        pat, _, repl = body.partition("##")
        return ("replace", pat, repl)
    return ("replace", body, "")


def _apply_regex(value, compiled):
    if not compiled:
        return value
    kind, pat, repl = compiled
    try:
        rx = re.compile(pat, re.S)
    except re.error:
        return value
    if kind == "replace":
        return rx.sub(repl, value)
    m = rx.search(value)
    if not m:
        return ""
    return m.group(1) if m.groups() else m.group(0)


def _resolve_selector(sel):
    """Rewrite legacy prefixes (class.X / tag.X / id.X / text.LABEL / @css:)."""
    sel = sel.strip()
    if sel.startswith("@css:"):
        return sel[len("@css:"):].strip()
    if sel.startswith("class."):
        body = sel[len("class."):].strip()
        # Legado 'class.A B C' means one element carrying ALL of those classes; a plain
        # "." + body would turn the spaces into a descendant chain and match nothing.
        if " " in body:
            return "." + ".".join(body.split())
        return "." + body
    if sel.startswith("tag."):
        return sel[len("tag."):]
    if sel.startswith("id."):
        return "#" + sel[len("id."):]
    if sel.startswith("text."):
        return ":contains(%s)" % sel[len("text."):]
    return sel


def evaluate(rule, root, base_url="", variables=None):
    """Evaluate one Legado field rule (str) against a parsed document."""
    variables = variables or {}
    if rule is None:
        return ""
    if not isinstance(rule, str):
        return ""
    rule = rule.strip()
    if not rule:
        return ""
    if "@js:" in rule or "<js>" in rule:
        raise ScriptRefused("javascript rule refused")
    # OR fallback across '||' at top level
    for candidate in _split_top(rule, "||"):
        value = _eval_single(candidate, root, base_url, variables)
        if value:
            return value
    return ""


def _eval_single(expr, root, base_url, variables):
    expr = expr.strip()
    if not expr:
        return ""
    # && concatenation of independent rules
    if "&&" in expr and "@" in expr and expr.count("&&"):
        parts = _split_top(expr, "&&")
        if len(parts) > 1:
            return "".join(_eval_single(p, root, base_url, variables) for p in parts)
    regex = None
    if "##" in expr:
        head, _, rest = expr.partition("##")
        regex = _compile_regex("##" + rest)
        expr = head
    steps = _split_at(expr, "@")
    scope = [root]
    last_attr = None
    for idx, step in enumerate(steps):
        step = step.strip()
        if not step:
            continue
        # a trailing bare attribute name is an attribute read, not a selector
        if idx == len(steps) - 1 and re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_:-]*", step) \
                and step.lower() in ATTRS:
            last_attr = step
            break
        sel = _resolve_selector(step)
        scope = [n for node in scope for n in select(node, sel)]
        if not scope:
            return ""
    if last_attr:
        node = _first(scope)
        if node is None:
            return ""
        value = _extract_attr(node, last_attr)
    else:
        node = _first(scope)
        if node is None:
            return ""
        value = node.text().strip()
    return _apply_regex(value, regex).strip()


ATTRS = {"text", "textnodes", "html", "htmlnodes", "content", "href", "src", "data-original",
         "data-src", "title", "value", "alt", "datetime", "owntext"}


def _split_top(expr, sep):
    out, buf, depth = [], "", 0
    i = 0
    while i < len(expr):
        ch = expr[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if depth == 0 and expr.startswith(sep, i):
            out.append(buf)
            buf = ""
            i += len(sep)
            continue
        buf += ch
        i += 1
    out.append(buf)
    return out


def _split_at(expr, sep):
    """Split on '@' that is not inside parentheses or a ##regex body."""
    out, buf, depth = [], "", 0
    for ch in expr:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == sep and depth == 0:
            out.append(buf)
            buf = ""
            continue
        buf += ch
    out.append(buf)
    return out


class ScriptRefused(Exception):
    pass
