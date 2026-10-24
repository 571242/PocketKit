"""在服务器本地把 APK 与元数据发到 pocketkit-hub（服务端执行，口令不离开服务器）。

由 publish.sh 通过 sudo 调用：
    sudo python3 publish-remote.py <暂存APK路径> <远端文件名>
    stdin: 每行一个 key=value（可空），合法 key 见 ALLOWED。
环境变量（仅用于自测）：
    HUB_DATA_DIR  数据目录，默认 /data/pocketkit-hub
    HUB_ADDR      目标地址，默认 127.0.0.1:9876
"""
import base64
import http.client
import json
import os
import sys
import urllib.parse

DATA = os.environ.get("HUB_DATA_DIR", "/data/pocketkit-hub")
ADDR = os.environ.get("HUB_ADDR", "127.0.0.1:9876")
ALLOWED = ("versionName", "versionCode", "title", "changelog", "downloadUrl", "configVersion", "proxyConfigUrl")


def die(msg):
    sys.exit(msg)


def main():
    if len(sys.argv) != 3:
        die("usage: publish-remote.py <staged-apk> <remote-filename>")
    stage, filename = sys.argv[1], sys.argv[2]

    overrides = {}
    for line in sys.stdin.read().splitlines():
        if not line.strip():
            continue
        key, sep, value = line.partition("=")
        if not sep:
            die("bad metadata entry (need key=value): " + line)
        if key not in ALLOWED:
            die("unsupported metadata key: " + key)
        overrides[key] = value

    with open(os.path.join(DATA, "admin.token"), encoding="utf-8") as f:
        cred = f.read().strip()
    auth = "Basic " + base64.b64encode(cred.encode()).decode()

    # 1) 流式上传，不把 300MB 读进内存
    size = os.path.getsize(stage)
    conn = http.client.HTTPConnection(ADDR, timeout=1800)
    with open(stage, "rb") as body:
        conn.request("POST", "/api/upload-release", body=body, headers={
            "Authorization": auth, "X-Filename": filename,
            "Content-Length": str(size), "Content-Type": "application/octet-stream"})
        resp = conn.getresponse()
        text = resp.read().decode("utf-8", "replace")
        status = resp.status
    conn.close()
    if status != 200:
        die("upload failed: %s %s" % (status, text))
    print("upload:", text)

    dest = os.path.join(DATA, "releases", filename)
    if os.path.getsize(dest) != size:
        die("size mismatch after upload: %d != %d" % (os.path.getsize(dest), size))
    print("size ok:", size)

    # 2) 只改给定字段，其余读自现有 config.json 原样回写，避免清空未提交字段
    if overrides:
        with open(os.path.join(DATA, "config.json"), encoding="utf-8") as f:
            cfg = json.load(f)
        cfg.update(overrides)
        if "versionCode" in overrides and str(overrides["versionCode"]).isdigit():
            cfg["versionCode"] = int(overrides["versionCode"])
        form = urllib.parse.urlencode({
            "versionName": cfg["versionName"], "versionCode": str(cfg["versionCode"]),
            "title": cfg["title"], "changelog": cfg["changelog"],
            "downloadUrl": cfg["downloadUrl"], "configVersion": cfg["configVersion"],
            "proxyConfigUrl": cfg.get("proxyConfigUrl", "")})
        conn = http.client.HTTPConnection(ADDR, timeout=120)
        conn.request("POST", "/api/admin/save-all", body=form.encode(), headers={
            "Authorization": auth, "Content-Type": "application/x-www-form-urlencoded"})
        resp = conn.getresponse()
        status = resp.status
        resp.read()
        conn.close()
        if status != 303:
            die("save-all failed: %s" % status)
        print("metadata updated fields:", ", ".join(overrides))
        print("version:", cfg.get("versionName"), "code:", cfg.get("versionCode"), "config:", cfg.get("configVersion"))
    else:
        print("metadata untouched")


if __name__ == "__main__":
    main()
