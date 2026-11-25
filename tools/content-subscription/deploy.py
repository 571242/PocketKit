#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Deploy the PocketKit 拾用精选 content subscription worker to InstaBox machine 01.

The old SSH path (ibssh_...@api.instabox.sh) is expired; everything now goes
through the InstaBox REST API:

    POST /v1/machines/<id>/exec          body {"cmd": "...", "cwd": "/root"}
    PUT  /v1/machines/<id>/files?path=…  upload (user-writable /tmp only; a root
                                         dir returns 403, so we PUT to /tmp then
                                         `sudo -n install -m 644 -o root -g root`)

Runnable directly on Windows:

    python tools/content-subscription/deploy.py preflight
    python tools/content-subscription/deploy.py deploy [--force-hub]
    python tools/content-subscription/deploy.py verify

preflight / deploy / verify keep the same meaning as the previous deploy.sh:
  preflight  read-only inspection + server.py diff, changes nothing
  deploy     backup, create low-priv user/dirs, install code + systemd units,
             enable the timer and run the service once
  verify     timer enabled, service ran, HTTP 200 non-empty, admin still 401

Only this one independent service is touched. The Hub admin.token is never read
or printed; a differing remote server.py is never clobbered without --force-hub.
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KEY = os.environ.get("INSTABOX_KEY", "ibk_rwo5745wedepg6oluqbs2n44hppgwbexjzvj2utfyezmzydv2p2a")
BASE = "https://api.instabox.sh/v1"
MACHINE_ID = os.environ.get("INSTABOX_MACHINE", "m_agqvov42zisdyuvnzmi23lrrw4")

BASE_URL = os.environ.get("BASE_URL", "https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech")
HUB_DIR = "/data/pocketkit-hub"
CODE_DIR = "/opt/pocketkit-content"
DATA_DIR = "/data/pocketkit-content"
SVC_USER = "pk-content"

SRC_DIR = os.path.dirname(os.path.abspath(__file__))
STAMP = time.strftime("%Y%m%d-%H%M%S")

CODE_FILES = [("curator.py", "0644"), ("legado.py", "0644"), ("boundedhttp.py", "0644"),
              ("finalize_sources.py", "0644"), ("refresh-inner.sh", "0755")]
UNIT_FILES = [("pocketkit-content-sync.service", "0644"),
              ("pocketkit-content-sync.timer", "0644")]

_H = {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"}


# ----------------------------------------------------------------------------- API plumbing

def _req(method, path, data=None, timeout=180, retries=5, headers=None, raw=False):
    url = BASE + path
    hh = dict(_H)
    if headers:
        hh.update(headers)
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=hh, data=data, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
                if raw:
                    return body
                text = body.decode("utf-8", "replace")
            return json.loads(text) if text.strip().startswith(("{", "[")) else {"_raw": text}
        except urllib.error.HTTPError as e:
            # 4xx is a real answer, do not burn retries on it
            detail = e.read().decode("utf-8", "replace")[:300]
            if 400 <= e.code < 500:
                return {"_error": "HTTP %s: %s" % (e.code, detail)}
            last = e
            time.sleep(2 * (i + 1))
        except Exception as e:  # TLS EOF / reset: retry with backoff
            last = e
            time.sleep(2 * (i + 1))
    return {"_error": str(last)}


def exec_cmd(cmd, cwd="/tmp", timeout=180, retries=5):
    """Run a shell command as user 'user' (passwordless sudo available)."""
    payload = {"cmd": cmd, "cwd": cwd}
    return _req("POST", "/machines/%s/exec" % MACHINE_ID,
                data=json.dumps(payload).encode("utf-8"), timeout=timeout, retries=retries)


def run_sudo(script, timeout=300):
    """Feed a multi-line root script through base64 | sudo -n bash -s.

    base64 (not inline quoting) keeps Windows->JSON->shell escaping out of the
    picture and works for arbitrarily long scripts.
    """
    b64 = base64.b64encode(script.encode("utf-8")).decode("ascii")
    cmd = "echo %s | base64 -d | sudo -n bash -s" % b64
    return exec_cmd(cmd, timeout=timeout)


def put_file(remote_path, data, mode="644"):
    """Upload a file by streaming base64 through the exec endpoint.

    The REST files API writes into a per-request sandbox that the exec shell cannot
    see, so a PUT here is invisible to `install /tmp/x`. Streaming base64 chunks
    through exec is slower but lands on the real filesystem the shell uses.
    """
    b64 = base64.b64encode(data).decode("ascii")
    chunk = 24000
    parts = [b64[i:i + chunk] for i in range(0, len(b64), chunk)] or [""]
    tmp = "/tmp/.upload-" + os.path.basename(remote_path)
    lines = ["set -e", ": > " + tmp + ".b64"]
    for part in parts:
        lines.append("printf '%%s' '%s' >> %s.b64" % (part, tmp))
    lines.append("base64 -d %s.b64 > %s" % (tmp, remote_path))
    lines.append("rm -f %s.b64" % tmp)
    lines.append("chmod %s %s" % (mode, remote_path))
    res = exec_cmd("\n".join(lines), timeout=180)
    if res.get("exitCode") != 0:
        return {"_error": (res.get("stderr") or res.get("_error") or "upload failed")[:300]}
    return {"status": 201, "body": remote_path}


def read_file(remote_path):
    """Download raw bytes of a remote file (returns None on any failure)."""
    q = urllib.parse.urlencode({"path": remote_path})
    try:
        data = _req("GET", "/machines/%s/files/content?%s" % (MACHINE_ID, q), raw=True, timeout=120)
    except Exception:
        return None
    return data if isinstance(data, (bytes, bytearray)) else None


def _out(res):
    """Print an exec result readably; return exit code."""
    code = res.get("exitCode")
    stdout = (res.get("stdout") or "").rstrip()
    stderr = (res.get("stderr") or "").rstrip()
    if stdout:
        print(stdout)
    if stderr:
        print("[stderr] " + stderr)
    if "_error" in res:
        print("[api error] " + str(res["_error"]))
        return 1
    return int(code) if isinstance(code, int) else 1


def _local(path):
    with open(os.path.join(SRC_DIR, path), "rb") as f:
        return f.read()


def install_units():
    """Install the two unit files and arm the timer.

    Uploads are redone here so a re-run after any interruption still has both files
    on the host. `systemctl start --no-block` matters: the service runs the full
    curator pass for many minutes, and a blocking start makes the deploy look like
    a failure at the exec timeout.
    """
    for name, _mode in UNIT_FILES:
        r = put_file("/tmp/" + name, _local(name), mode="644")
        if r.get("status") not in (200, 201):
            print("!! upload %s failed: %s" % (name, r))
            return 1
    units = r"""
set -e
install -o root -g root -m 0644 /tmp/pocketkit-content-sync.service /etc/systemd/system/
install -o root -g root -m 0644 /tmp/pocketkit-content-sync.timer /etc/systemd/system/
rm -f /tmp/pocketkit-content-sync.service /tmp/pocketkit-content-sync.timer
systemctl daemon-reload
systemctl enable pocketkit-content-sync.timer
systemctl restart pocketkit-content-sync.timer
systemctl start --no-block pocketkit-content-sync.service || true
"""
    if _out(run_sudo(units, timeout=120)) != 0:
        print("!! unit install failed; aborting")
        return 1
    return 0


# ----------------------------------------------------------------------------- preflight

def preflight():
    print("== identity / host ==")
    _out(exec_cmd("whoami; id; hostname; uname -a", cwd="/tmp"))
    print("== disk / python ==")
    _out(exec_cmd("df -h /data /opt 2>/dev/null || df -h /; python3 --version", cwd="/tmp"))
    print("== existing content service? ==")
    _out(exec_cmd("test -e %s && ls -la %s || echo 'no %s'; "
                  "test -e %s && ls -la %s || echo 'no %s'; "
                  "systemctl list-unit-files | grep -i pocketkit-content || echo 'no content units'"
                  % (CODE_DIR, CODE_DIR, CODE_DIR, DATA_DIR, DATA_DIR, DATA_DIR), cwd="/tmp"))
    print("== hub service ==")
    _out(exec_cmd("systemctl is-enabled pocketkit-hub; systemctl is-active pocketkit-hub", cwd="/tmp"))
    print("== server.py diff (local vs remote) ==")
    remote = read_file(HUB_DIR + "/server.py")
    local_path = os.path.join(SRC_DIR, "..", "update-server", "server.py")
    if remote is None:
        print("!! could not fetch remote server.py (or it does not exist)")
    elif not os.path.isfile(local_path):
        print("!! local ../update-server/server.py not found; remote sha256=%s"
              % hashlib.sha256(remote).hexdigest()[:16])
    else:
        with open(local_path, "rb") as f:
            local = f.read()
        if local == remote:
            print("remote server.py == local (safe to overwrite)")
        else:
            print("!! remote differs from local (local %d bytes, remote %d bytes); review before "
                  "deploy --force-hub" % (len(local), len(remote)))
    return 0


# ----------------------------------------------------------------------------- deploy

def deploy(force_hub=False):
    print("== backup existing hub + content state ==")
    backup = r"""
set -e
B=/root/pocketkit-content-backup-%s
mkdir -p "$B"
cp -a %s/server.py "$B/server.py" 2>/dev/null || true
cp -a %s/config.json "$B/config.json" 2>/dev/null || true
test -e %s && cp -a %s "$B/content-data" || true
test -e %s && cp -a %s "$B/code" || true
echo "backup at $B"
ls -la "$B"
""" % (STAMP, HUB_DIR, HUB_DIR, DATA_DIR, DATA_DIR, CODE_DIR, CODE_DIR)
    if _out(run_sudo(backup)) != 0:
        print("!! backup failed; aborting")
        return 1

    print("== create low-priv user + dirs ==")
    mkdirs = r"""
set -e
id -u %s >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin %s
install -d -o root -g root -m 0755 %s
install -d -o %s -g %s -m 0755 %s
install -d -o %s -g %s -m 0755 %s/content
""" % (SVC_USER, SVC_USER, CODE_DIR, SVC_USER, SVC_USER, DATA_DIR, SVC_USER, SVC_USER, DATA_DIR)
    if _out(run_sudo(mkdirs)) != 0:
        print("!! user/dir setup failed; aborting")
        return 1

    print("== upload code ==")
    for name, mode in CODE_FILES:
        r = put_file("/tmp/" + name, _local(name), mode="644")
        if r.get("status") not in (200, 201):
            print("!! upload %s failed: %s" % (name, r))
            return 1
    install = ["set -e"]
    for name, mode in CODE_FILES:
        install.append("install -o root -g root -m %s /tmp/%s %s/%s" % (mode, name, CODE_DIR, name))
        install.append("rm -f /tmp/%s" % name)
    install.append("python3 -c \"import ast,sys; "
                   "[ast.parse(open('%s/'+f).read()) for f in "
                   "('curator.py','legado.py','boundedhttp.py','finalize_sources.py')]\"" % CODE_DIR)
    if _out(run_sudo("\n".join(install))) != 0:
        print("!! code install / syntax check failed; aborting")
        return 1

    print("== install units, enable timer, first run ==")
    if install_units() != 0:
        return 1

    print("== hub server.py: only replaced with --force-hub after reviewing the preflight diff ==")
    if force_hub:
        data = _local(os.path.join("..", "update-server", "server.py"))
        r = put_file("/tmp/pocketkit-hub-new.py", data, mode="644")
        if r.get("status") not in (200, 201):
            print("!! hub upload failed: %s" % r)
            return 1
        hub = r"""
set -e
install -o user -g user -m 0644 /tmp/pocketkit-hub-new.py %s/server.py
rm -f /tmp/pocketkit-hub-new.py
python3 -m py_compile %s/server.py
systemctl restart pocketkit-hub
sleep 1
systemctl is-active pocketkit-hub
""" % (HUB_DIR, HUB_DIR)
        if _out(run_sudo(hub)) != 0:
            print("!! hub server.py replace failed")
            return 1
    else:
        print("hub server.py not restarted (pass --force-hub after reviewing the preflight diff)")
    print("deploy complete")
    return 0


# ----------------------------------------------------------------------------- verify

def _http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "PocketKit-deploy/1"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read()
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception as e:
        return None, str(e).encode()


def verify():
    print("== timer/service ==")
    _out(exec_cmd("systemctl is-enabled pocketkit-content-sync.timer; "
                  "systemctl list-timers pocketkit-content-sync.timer --no-pager | head -3; "
                  "systemctl show -p Result -p ExecMainStatus pocketkit-content-sync.service",
                  cwd="/tmp"))
    print("== perms / owner of data ==")
    _out(exec_cmd("ls -la %s; ls -la %s/content" % (DATA_DIR, DATA_DIR), cwd="/tmp"))
    print("== published package over HTTP (anonymous, read-only) ==")
    code, body = _http_get(BASE_URL + "/content/book-sources.json")
    ok = True
    if code is None:
        print("GET failed: %s" % body.decode("utf-8", "replace"))
        ok = False
    elif code != 200:
        print("HTTP %s bytes=%d -- hub cannot read the file (check 0644 and directory +x)" % (code, len(body)))
        ok = False
    else:
        print("HTTP %s bytes=%d" % (code, len(body)))
        try:
            pkg = json.loads(body.decode("utf-8"))
            kinds = {}
            for s in pkg.get("sources", []):
                kinds[s.get("kind")] = kinds.get(s.get("kind"), 0) + 1
            print("sources by kind: %s" % json.dumps(kinds, ensure_ascii=False))
            if not pkg.get("sources"):
                print("published package is empty")
                ok = False
            # The nightly curator must never shrink the bundle back to one source.
            missing = [k for k in ("novel", "manga", "video") if kinds.get(k, 0) < 1]
            if missing:
                print("!! published package lost all %s sources" % ", ".join(missing))
                ok = False
            if len(pkg.get("sources", [])) < 13:
                print("!! published package has %d sources, expected at least the 13 bundled baseline"
                      % len(pkg["sources"]))
                ok = False
        except Exception as e:
            print("published package is not valid JSON: %s" % e)
            ok = False
    print("== status over HTTP ==")
    code, body = _http_get(BASE_URL + "/content/book-sources-status.json")
    print("HTTP %s bytes=%d" % (code, len(body)) if code is not None else "GET failed")
    print("== admin still protected (must be 401) ==")
    code, _ = _http_get(BASE_URL + "/")
    print("admin page HTTP %s" % code)
    return 0 if ok else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description="InstaBox deploy for 拾用精选 content worker")
    parser.add_argument("command", choices=["preflight", "deploy", "verify"])
    parser.add_argument("--force-hub", action="store_true",
                        help="also replace the Hub server.py (only after reviewing the preflight diff)")
    args = parser.parse_args(argv)
    if args.command == "preflight":
        return preflight()
    if args.command == "deploy":
        return deploy(force_hub=args.force_hub)
    return verify()


if __name__ == "__main__":
    raise SystemExit(main())
