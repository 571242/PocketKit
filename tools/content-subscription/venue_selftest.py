"""Local smoke test of the /content/ route added to server.py.

Runs the real HubHandler against a temp dir on 127.0.0.1, with CONTENT_DIR patched
to that temp dir. Verifies: anonymous 200 for whitelisted files, 404 for anything
else, and that path traversal / sub-paths are refused. No production data touched.
"""
import importlib.util
import os
import tempfile
import threading
import urllib.request
import json

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "..", "update-server", "server.py")

tmp = tempfile.mkdtemp(prefix="pk-content-")
os.makedirs(os.path.join(tmp, "content"), exist_ok=True)
with open(os.path.join(tmp, "content", "book-sources.json"), "w", encoding="utf-8") as f:
    json.dump({"schemaVersion": 1, "name": "拾用精选", "sources": []}, f)
with open(os.path.join(tmp, "content", "secret.txt"), "w") as f:
    f.write("should-not-serve")

spec = importlib.util.spec_from_file_location("server", SERVER)
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
# redirect the hub to the temp content dir and a temp data dir (avoid real /data)
server.CONTENT_DIR = os.path.join(tmp, "content")
server.DATA_DIR = tmp
server.CONFIG_FILE = os.path.join(tmp, "config.json")
server.RELEASES_DIR = os.path.join(tmp, "releases")
os.makedirs(server.RELEASES_DIR, exist_ok=True)

from http.server import HTTPServer
httpd = HTTPServer(("127.0.0.1", 0), server.HubHandler)
port = httpd.server_address[1]
threading.Thread(target=httpd.serve_forever, daemon=True).start()

FAIL = []


def expect(path, code):
    url = "http://127.0.0.1:%d%s" % (port, path)
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            got = r.status
    except urllib.error.HTTPError as e:
        got = e.code
    ok = got == code
    print(("PASS " if ok else "FAIL ") + "%s -> %d (want %d)" % (path, got, code))
    if not ok:
        FAIL.append(path)


expect("/content/book-sources.json", 200)
expect("/content/secret.txt", 404)            # not whitelisted
expect("/content/../server.py", 404)          # traversal
expect("/content/book-sources-status.json", 404)  # whitelisted name but absent -> 404
httpd.shutdown()
print("\n%d failed" % len(FAIL))
raise SystemExit(1 if FAIL else 0)
