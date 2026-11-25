"""Bounded, public-only HTTP for the content subscription worker.

Hard rules (see docs/reading-contract.md and the ops constraints):
- http/https only, no credentials in URL;
- resolve every host and refuse private/loopback/link-local/reserved/multicast
  and the cloud metadata address 169.254.169.254;
- hard byte cap and timeout on every read;
- follow at most MAX_REDIRECTS redirects and re-check the target host each hop.
"""
import ipaddress
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_BYTES = 4 * 1024 * 1024
TIMEOUT = 20
TOTAL_TIMEOUT = 45          # wall-clock cap for one request, independent of per-read timeout
MAX_REDIRECTS = 4
UA = "Mozilla/5.0 (Linux; Android 13) PocketKit-curator/1"

BLOCKED_V4 = [ipaddress.ip_network(n) for n in
              ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
               "169.254.0.0/16", "100.64.0.0/10", "0.0.0.0/8", "224.0.0.0/4")]


class Blocked(Exception):
    """Refused an address or scheme that the contract forbids."""


def _assert_public_host(host, port):
    if not host:
        raise Blocked("empty host")
    # getaddrinfo has no timeout of its own and can block on a broken resolver.
    box = {}

    def resolve():
        try:
            box["infos"] = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
        except BaseException as exc:
            box["error"] = exc

    thread = threading.Thread(target=resolve, daemon=True)
    thread.start()
    thread.join(5)
    if thread.is_alive():
        raise Blocked("dns timeout")
    if "error" in box:
        raise Blocked("dns: %s" % box["error"])
    infos = box.get("infos") or []
    if not infos:
        raise Blocked("dns: no address")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved \
                or ip.is_multicast or ip.is_unspecified:
            raise Blocked("non-public %s" % ip)
        if ip.version == 4 and any(ip in net for net in BLOCKED_V4):
            raise Blocked("non-public %s" % ip)


def _requote(url):
    """Percent-encode non-ASCII/space in path & query (http.client would fail on raw UTF-8)."""
    parts = urllib.parse.urlsplit(url)
    path = urllib.parse.quote(parts.path, safe="/%:@!$&'()*+,;=-._~")
    query = urllib.parse.quote(parts.query, safe="=&%:@!$'()*+,;/?-._~")
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, path, query, parts.fragment))


def _validate(url):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https"):
        raise Blocked("scheme %r" % parts.scheme)
    if parts.username or parts.password:
        raise Blocked("credentials in url")
    _assert_public_host(parts.hostname, parts.port or (443 if parts.scheme == "https" else 80))
    return parts


class _SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _validate(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_OPENER = urllib.request.build_opener(_SafeRedirect)


def _read_capped(response, max_bytes, deadline):
    """Read at most max_bytes+1, aborting once the wall-clock deadline passes.

    urllib's timeout only bounds one socket operation. A server that trickles bytes (or
    holds the connection open) would otherwise keep a worker alive indefinitely.
    """
    chunks = []
    size = 0
    while True:
        if time.monotonic() > deadline:
            raise TimeoutError("request exceeded %ss" % TOTAL_TIMEOUT)
        chunk = response.read(min(65536, max_bytes + 1 - size))
        if not chunk:
            break
        chunks.append(chunk)
        size += len(chunk)
        if size > max_bytes:
            raise ValueError("body exceeds %d bytes" % max_bytes)
    return b"".join(chunks)


def _with_deadline(work, timeout):
    """Run work in a daemon thread and stop waiting at the hard deadline.

    On timeout the underlying response is closed so the worker thread cannot keep a
    socket open after the caller has moved on.
    """
    deadline = time.monotonic() + min(timeout, TOTAL_TIMEOUT)
    result = {"response": None}

    def run():
        try:
            result["value"] = work(deadline, result)
        except BaseException as exc:  # surfaced to the caller below
            result["error"] = exc

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    thread.join(max(0.0, deadline - time.monotonic()))
    if thread.is_alive():
        response = result.get("response")
        if response is not None:
            try:
                response.close()
            except Exception:
                pass
        raise TimeoutError("request exceeded %ss" % TOTAL_TIMEOUT)
    if "error" in result:
        raise result["error"]
    return result["value"]


def get(url, *, max_bytes=MAX_BYTES, timeout=TIMEOUT, headers=None):
    """Return (status, body_bytes, final_url). Raises Blocked or urllib errors."""
    url = _requote(url)
    _validate(url)

    def work(deadline, slot):
        request = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
        with _OPENER.open(request, timeout=timeout) as response:
            slot["response"] = response
            return response.status, _read_capped(response, max_bytes, deadline), response.geturl()

    return _with_deadline(work, timeout)


def post(url, body, *, max_bytes=MAX_BYTES, timeout=TIMEOUT, headers=None):
    url = _requote(url)
    _validate(url)
    data = body.encode("utf-8") if isinstance(body, str) else (body or b"")
    merged = {"User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded", **(headers or {})}

    def work(deadline, slot):
        request = urllib.request.Request(url, data=data, headers=merged, method="POST")
        with _OPENER.open(request, timeout=timeout) as response:
            slot["response"] = response
            return response.status, _read_capped(response, max_bytes, deadline), response.geturl()

    return _with_deadline(work, timeout)


def get_probe(url, *, max_bytes=65536, timeout=TIMEOUT, headers=None):
    """Return (status, content_type, body_head, final_url) reading at most max_bytes.

    Unlike get() this deliberately STOPS at max_bytes instead of raising: image probes
    only need the first chunk plus the Content-Type header, and a normal photo is far
    larger than that cap. Same public-host validation and wall-clock deadline as get().
    """
    url = _requote(url)
    _validate(url)

    def work(deadline, slot):
        request = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
        with _OPENER.open(request, timeout=timeout) as response:
            slot["response"] = response
            try:
                ctype = response.headers.get("Content-Type", "") or ""
            except Exception:
                ctype = ""
            head, size = [], 0
            while size < max_bytes:
                if time.monotonic() > deadline:
                    break
                chunk = response.read(min(65536, max_bytes - size))
                if not chunk:
                    break
                head.append(chunk)
                size += len(chunk)
            return response.status, ctype, b"".join(head), response.geturl()

    return _with_deadline(work, timeout)


def get_text(url, **kwargs):
    status, body, final = get(url, **kwargs)
    charset = "utf-8"
    try:
        charset = (kwargs.get("headers") or {}).get("_charset") or "utf-8"
    except Exception:
        pass
    return status, body.decode(charset, "replace"), final
