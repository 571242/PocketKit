import os
import json
import base64
import hmac
import re
import tempfile
from http.server import HTTPServer, SimpleHTTPRequestHandler
import urllib.parse

DATA_DIR = "/data/pocketkit-hub"
CONFIG_FILE = os.path.join(DATA_DIR, "config.json")
PROXY_RULE_FILE = os.path.join(DATA_DIR, "clash_rules.yaml")
RULES_DIR = os.path.join(DATA_DIR, "rules")
RULE_SOURCES_DIR = os.path.join(DATA_DIR, "rule-sources")
RULE_MANIFEST_FILE = os.path.join(DATA_DIR, "rules-manifest.json")
RULE_STATE_FILE = os.path.join(DATA_DIR, "rules-state.json")
RULE_MANIFEST_FIELDS = ("rulesSchemaVersion", "rulesVersion", "rulesSha256", "rulesBytes", "rulesUrl",
                        "rulesUpdatedAt", "rulesCounts", "rulesSourceCommit", "rulesSources",
                        "rulesLicenseUrl", "rulesMinAppCode")
RELEASES_DIR = os.path.join(DATA_DIR, "releases")
BASE_URL = "https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech"
# 管理口令文件，0600，由部署脚本生成；每次请求读取，轮换口令无需重启
AUTH_FILE = os.path.join(DATA_DIR, "admin.token")
MAX_UPLOAD_BYTES = 512 * 1024 * 1024
RELEASE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$")

if not os.path.exists(RELEASES_DIR):
    os.makedirs(RELEASES_DIR, exist_ok=True)

def get_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "versionName": "0.9.1",
        "versionCode": 19,
        "minSupportedCode": 1,
        "title": "PocketKit 0.9.1",
        "changelog": "1. 修复画板触摸手绘时界面上下滑动的事件冲突\n2. 修复多订阅链接自动重定向、GZIP解压与节点解析\n3. 节点池汇总优化与首节点智能对齐",
        "downloadUrl": f"{BASE_URL}/releases/PocketKit-0.9.1.apk",
        "configVersion": "2026.10.24.3",
        "proxyConfigUrl": f"{BASE_URL}/api/proxy-config",
        "forceUpdate": False
    }

def rule_json(path):
    try:
        with open(path, encoding="utf-8") as stream:
            result = json.load(stream)
        return result if isinstance(result, dict) else {}
    except (OSError, ValueError):
        return {}


def get_update_config():
    cfg = get_config()
    manifest = rule_json(RULE_MANIFEST_FILE)
    version = manifest.get("rulesVersion", "")
    if isinstance(version, str) and re.fullmatch(r"[0-9a-f]{64}", version):
        artifact = os.path.join(RULES_DIR, version + ".json")
        if os.path.isfile(artifact) and os.path.getsize(artifact) == manifest.get("rulesBytes"):
            cfg.update({key: manifest[key] for key in RULE_MANIFEST_FIELDS if key in manifest})
    cfg["upstreamReleases"] = rule_json(RULE_STATE_FILE).get("upstreamReleases", {})
    return cfg


def atomic_write(path, data, mode):
    """写临时文件并 fsync，返回临时路径；调用方负责 os.replace 生效。"""
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".tmp-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, mode)
        return tmp
    except BaseException:
        os.unlink(tmp)
        raise

def save_config(cfg):
    mode = os.stat(CONFIG_FILE).st_mode & 0o777 if os.path.exists(CONFIG_FILE) else 0o644
    tmp = atomic_write(CONFIG_FILE, json.dumps(cfg, ensure_ascii=False, indent=2).encode("utf-8"), mode)
    os.replace(tmp, CONFIG_FILE)

def get_proxy_rules():
    if os.path.exists(PROXY_RULE_FILE):
        try:
            with open(PROXY_RULE_FILE, "r", encoding="utf-8") as f:
                return f.read()
        except Exception:
            pass
    return """port: 7890
socks-port: 7891
allow-lan: false
mode: rule
log-level: info
external-controller: 127.0.0.1:9090

dns:
  enable: true
  ipv6: false
  listen: 127.0.0.1:1053
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  nameserver:
    - 223.5.5.5
    - 119.29.29.29
  fallback:
    - 8.8.8.8
    - 1.1.1.1

tun:
  enable: true
  stack: gvisor
  dns-hijack:
    - "tcp://any:53"
    - "udp://any:53"
  auto-route: true
  auto-detect-interface: true

rules:
  - DOMAIN-SUFFIX,local,DIRECT
  - GEOIP,CN,DIRECT
  - MATCH,PROXY
"""

def load_credentials():
    """返回 (user, password)；文件缺失或格式不对时返回 None（fail closed）。"""
    try:
        with open(AUTH_FILE, "r", encoding="utf-8") as f:
            line = f.read().strip()
    except Exception:
        return None
    user, sep, password = line.partition(":")
    if not sep or not user or not password:
        return None
    return user, password

class HubHandler(SimpleHTTPRequestHandler):
    server_version = "PocketKitHub"

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Filename, Authorization")
        # 认证靠 Authorization 头，任何中间缓存都不应留存响应
        if re.fullmatch(r"/(?:rules/[0-9a-f]{64}\.json|rule-sources/[0-9a-f]{64}\.txt)", urllib.parse.urlsplit(self.path).path):
            self.send_header("Cache-Control", "public, max-age=86400, immutable")
        else:
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(header[6:].strip(), validate=True).decode("utf-8")
        except Exception:
            return False
        user, _, password = raw.partition(":")
        creds = load_credentials()
        if not creds:
            return False
        expected_user, expected_pass = creds
        user_ok = hmac.compare_digest(user.encode("utf-8"), expected_user.encode("utf-8"))
        pass_ok = hmac.compare_digest(password.encode("utf-8"), expected_pass.encode("utf-8"))
        return user_ok and pass_ok

    def _require_auth(self):
        """鉴权只看 Authorization 头：不看来源 IP，平台入口可能从本地转发。"""
        if self._authorized():
            return True
        body = b'{"error":"unauthorized"}'
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="PocketKit Hub Admin", charset="UTF-8"')
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        return False

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/api/check-update":
            cfg = get_update_config()
            body = json.dumps(cfg, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/api/rule-status":
            body = json.dumps(rule_json(RULE_STATE_FILE), ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        rule_match = re.fullmatch(r"/rules/([0-9a-f]{64}\.json)", path)
        source_match = re.fullmatch(r"/rule-sources/([0-9a-f]{64}\.txt|LICENSE\.txt)", path)
        if rule_match or source_match:
            match = rule_match or source_match
            root = RULES_DIR if rule_match else RULE_SOURCES_DIR
            filename = match.group(1)
            target = os.path.join(root, filename)
            if not os.path.isfile(target) or os.path.islink(target):
                self.send_error(404, "Rule artifact not found")
                return
            with open(target, "rb") as stream:
                body = stream.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8" if rule_match else "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("ETag", '"' + filename.split('.')[0] + '"')
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/api/proxy-config":
            rules = get_proxy_rules().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/yaml; charset=utf-8")
            self.send_header("Content-Length", str(len(rules)))
            self.end_headers()
            self.wfile.write(rules)
            return

        if path.startswith("/releases/"):
            filename = os.path.basename(path)
            filepath = os.path.join(RELEASES_DIR, filename)
            if os.path.exists(filepath) and os.path.isfile(filepath):
                size = os.path.getsize(filepath)
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(size))
                self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
                self.end_headers()
                with open(filepath, "rb") as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
                return
            else:
                self.send_error(404, "Release file not found")
                return

        # 管理页面会展示并提交配置，属受保护资源
        if path == "/" or path == "/index.html":
            if not self._require_auth():
                return
            cfg = get_config()
            rules = get_proxy_rules()
            html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>PocketKit 云端配置中心 (机器 01)</title>
<style>
body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0F172A; color: #F1F5F9; margin: 0; padding: 24px; }}
.card {{ background: #1E293B; border-radius: 14px; padding: 20px; margin-bottom: 20px; border: 1px solid #334155; }}
h1 {{ font-size: 20px; color: #38BDF8; margin-top: 0; }}
h2 {{ font-size: 16px; color: #94A3B8; margin-bottom: 12px; }}
label {{ display: block; font-size: 13px; color: #94A3B8; margin-top: 10px; }}
input, textarea {{ width: 100%; box-sizing: border-box; background: #0F172A; border: 1px solid #475569; border-radius: 8px; color: #F8FAFC; padding: 10px; font-family: inherit; font-size: 14px; margin-top: 4px; }}
textarea {{ min-height: 220px; font-family: monospace; font-size: 13px; }}
button {{ background: #0284C7; color: white; border: none; border-radius: 8px; padding: 10px 18px; font-size: 14px; font-weight: bold; cursor: pointer; margin-top: 14px; }}
button:hover {{ background: #0369A1; }}
.badge {{ display: inline-block; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: bold; background: #065F46; color: #34D399; margin-left: 8px; }}
</style>
</head>
<body>
<div class="card">
  <h1>PocketKit 云端服务总控 <span class="badge">机器 01 运行中</span></h1>
  <p style="font-size: 13px; color: #94A3B8;">公网下发地址: <code>{BASE_URL}/api/check-update</code></p>
</div>

<form method="POST" action="/api/admin/save-all">
<div class="card">
  <h2>软件版本推送</h2>
  <label>最新版本名 (versionName):</label>
  <input name="versionName" value="{cfg.get('versionName', '')}">
  <label>最新版本号 (versionCode):</label>
  <input name="versionCode" type="number" value="{cfg.get('versionCode', 1)}">
  <label>更新标题:</label>
  <input name="title" value="{cfg.get('title', '')}">
  <label>更新日志 (Changelog):</label>
  <textarea name="changelog" style="min-height:90px;">{cfg.get('changelog', '')}</textarea>
  <label>APK 下载直链 (downloadUrl):</label>
  <input name="downloadUrl" value="{cfg.get('downloadUrl', '')}">
</div>

<div class="card">
  <h2>代理规则与配置热重载</h2>
  <label>规则版本号 (configVersion):</label>
  <input name="configVersion" value="{cfg.get('configVersion', '')}">
  <label>Clash YAML 分流规则与节点内容:</label>
  <textarea name="proxyYaml">{rules}</textarea>
  <button type="submit">保存并立即同步至云端</button>
</div>
</form>
</body>
</html>
""".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
            return

        self.send_error(404, "Not Found")

    def do_POST(self):
        # 所有写接口（上传、保存、以及将来任何 POST）统一先鉴权
        if not self._require_auth():
            return
        origin = self.headers.get("Origin")
        if origin and origin not in (BASE_URL, "http://127.0.0.1:9876", "http://localhost:9876"):
            self.send_error(403, "Cross-origin writes forbidden")
            return

        if self.path == "/api/upload-release":
            filename = (self.headers.get("X-Filename") or "").strip()
            if not RELEASE_NAME_RE.match(filename) or ".." in filename:
                self.send_error(400, "Invalid release filename")
                return
            try:
                content_length = int(self.headers.get("Content-Length", 0))
            except ValueError:
                self.send_error(400, "Bad Content-Length")
                return
            if content_length <= 0 or content_length > MAX_UPLOAD_BYTES:
                self.send_error(413, "Payload too large or empty")
                return

            filepath = os.path.join(RELEASES_DIR, filename)
            fd, tmp = tempfile.mkstemp(dir=RELEASES_DIR, prefix=".upload-", suffix=".part")
            bytes_read = 0
            try:
                with os.fdopen(fd, "wb") as f:
                    while bytes_read < content_length:
                        chunk = self.rfile.read(min(65536, content_length - bytes_read))
                        if not chunk:
                            break
                        f.write(chunk)
                        bytes_read += len(chunk)
                    f.flush()
                    os.fsync(f.fileno())
                if bytes_read != content_length:
                    raise IOError("short body")
                os.chmod(tmp, 0o644)
                os.replace(tmp, filepath)
            except Exception as ex:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                # 上传中断只丢弃临时文件，磁盘上已有 APK 不受影响
                self.send_error(500, "Upload failed: " + type(ex).__name__)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status":"ok","received":' + str(bytes_read).encode() + b'}')
            return

        if self.path == "/api/admin/save-all":
            if not self.headers.get("Content-Type", "").startswith("application/x-www-form-urlencoded"):
                self.send_error(415, "Expected form encoding")
                return
            try:
                content_length = int(self.headers.get("Content-Length", 0))
            except ValueError:
                self.send_error(400, "Bad Content-Length")
                return
            if content_length <= 0 or content_length > 1024 * 1024:
                self.send_error(413, "Payload too large or empty")
                return
            post_data = self.rfile.read(content_length).decode("utf-8", "replace")
            params = urllib.parse.parse_qs(post_data)

            cfg = get_config()
            # 只覆盖表单里实际出现的字段，缺失字段保留原值
            for key in ("versionName", "title", "changelog", "downloadUrl", "configVersion", "proxyConfigUrl"):
                if key in params:
                    cfg[key] = params[key][0]
            if "versionCode" in params:
                try:
                    cfg["versionCode"] = int(params["versionCode"][0])
                except Exception:
                    pass
            new_yaml = params.get("proxyYaml", [""])[0]

            # 先把两份内容都落到临时文件，全部成功后才替换，失败保留旧 metadata
            staged = []
            try:
                staged.append((atomic_write(CONFIG_FILE, json.dumps(cfg, ensure_ascii=False, indent=2).encode("utf-8"), 0o644), CONFIG_FILE))
                if new_yaml:
                    staged.append((atomic_write(PROXY_RULE_FILE, new_yaml.encode("utf-8"), 0o644), PROXY_RULE_FILE))
            except Exception as ex:
                for tmp, _ in staged:
                    try:
                        os.unlink(tmp)
                    except OSError:
                        pass
                self.send_error(500, "Save failed: " + type(ex).__name__)
                return
            for tmp, target in staged:
                os.replace(tmp, target)

            self.send_response(303)
            self.send_header("Location", "/")
            self.end_headers()
            return

        self.send_error(404, "Not Found")

if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 9876), HubHandler)
    print("PocketKit Cloud Hub listening on port 9876...")
    server.serve_forever()
