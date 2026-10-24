#!/usr/bin/env python3
"""Cloudflare quick-tunnel supervisor for InstaBox-01.

Restarts cloudflared whenever the quick tunnel is revoked or the assigned
hostname stops resolving, and re-renders the Clash subscription each time a
new hostname appears. Quick-tunnel hostnames are ephemeral by design; this
keeps the service usable without pretending the URL is stable.
"""
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request

UUID_FILE = "/etc/xray_uuid.txt"
SUB_FILE = "/var/www/html/sub.yaml"
DOMAIN_FILE = "/var/www/html/domain.txt"
CLOUDFLARED = "cloudflared"
TUNNEL_URL = "http://127.0.0.1:80"
METRICS = "http://127.0.0.1:20241/metrics"

DOMAIN_RE = re.compile(r"https://([a-z0-9-]+\.trycloudflare\.com)")
FATAL_RE = re.compile(r"Unauthorized: Tunnel not found|Tunnel not found", re.I)

state = {"domain": None, "revoked": False}
lock = threading.Lock()


def log(msg):
    print(f"[sup] {msg}", flush=True)


def render(domain):
    """Rewrite the Clash subscription for the given quick-tunnel hostname."""
    uuid = open(UUID_FILE).read().strip()
    body = f"""port: 7890
socks-port: 7891
allow-lan: false
mode: rule
log-level: info

proxies:
  - name: "Instabox-US-Oregon"
    type: vless
    server: {domain}
    port: 443
    uuid: {uuid}
    network: ws
    tls: true
    udp: true
    skip-cert-verify: false
    servername: {domain}
    ws-opts:
      path: "/ray"
      headers:
        Host: {domain}
  - name: "Instabox-CF-Fast"
    type: vless
    server: 104.18.2.1
    port: 443
    uuid: {uuid}
    network: ws
    tls: true
    udp: true
    skip-cert-verify: false
    servername: {domain}
    ws-opts:
      path: "/ray"
      headers:
        Host: {domain}

proxy-groups:
  - name: "PROXY"
    type: select
    proxies:
      - "Instabox-US-Oregon"
      - "Instabox-CF-Fast"

rules:
  - MATCH,PROXY
"""
    with open(SUB_FILE, "w") as f:
        f.write(body)
    os.chmod(SUB_FILE, 0o644)
    with open(DOMAIN_FILE, "w") as f:
        f.write(domain)
    log(f"subscription written for {domain}")


def pump(proc):
    """Read cloudflared output: capture hostname, flag a revoked tunnel."""
    for raw in proc.stdout:
        line = raw.rstrip()
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
        m = DOMAIN_RE.search(line)
        if m:
            with lock:
                if state["domain"] != m.group(1):
                    state["domain"] = m.group(1)
                    render(state["domain"])
            continue
        if FATAL_RE.search(line):
            with lock:
                state["revoked"] = True


def hostname_resolves(domain):
    try:
        socket.getaddrinfo(domain, 443)
        return True
    except OSError:
        return False


def endpoint_serves(domain):
    """Public HTTPS probe: DNS can resolve while the edge still refuses us."""
    req = urllib.request.Request(f"https://{domain}/sub", method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return 200 <= resp.status < 400
    except Exception:
        return False


def health_check():
    """Return (healthy, reason). Cheap probes only; called every 60s."""
    with lock:
        domain, revoked = state["domain"], state["revoked"]
    if revoked:
        return False, "cloudflare reported tunnel not found"
    if not domain:
        return True, "waiting for hostname assignment"
    if not hostname_resolves(domain):
        return False, f"{domain} does not resolve (tunnel recycled)"
    if not endpoint_serves(domain):
        return False, f"{domain} resolves but serves no traffic"
    return True, f"{domain} serving"


def supervise():
    backoff = 5
    while True:
        with lock:
            state["domain"] = None
            state["revoked"] = False
        log(f"starting cloudflared for {TUNNEL_URL}")
        proc = subprocess.Popen(
            [CLOUDFLARED, "tunnel", "--url", TUNNEL_URL, "--no-autoupdate"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
        )
        reader = threading.Thread(target=pump, args=(proc,), daemon=True)
        reader.start()

        started = time.time()
        reason = "process exited"
        while proc.poll() is None:
            time.sleep(60)
            viable = bool(state["domain"]) and time.time() - started > 90
            if viable:
                ok, why = health_check()
                if not ok:
                    reason = why
                    break
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
        log(f"tunnel down ({reason}); restarting in {backoff}s")
        time.sleep(backoff)
        backoff = min(backoff * 2, 60)


def main():
    if not os.path.exists(UUID_FILE):
        log("UUID file not found; refusing to start")
        return 1
    supervise()
    return 0


if __name__ == "__main__":
    sys.exit(main())
