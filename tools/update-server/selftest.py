"""server.py 的自检：把路径常量指向临时目录后起真实 HTTP 服务，验证鉴权与写入语义。

不改动生产常量，只对副本做路径替换。
"""
import base64
import http.client
import importlib.util
import json
import os
import re
import shutil
import tempfile
import threading
import unittest
from http.server import HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_PY = os.path.join(HERE, "server.py")


def load_hub(data_dir, auth_file):
    src = open(SERVER_PY, encoding="utf-8").read()
    src = src.replace('DATA_DIR = "/data/pocketkit-hub"', 'DATA_DIR = %r' % data_dir)
    src = src.replace('AUTH_FILE = os.path.join(DATA_DIR, "admin.token")', 'AUTH_FILE = %r' % auth_file)
    path = os.path.join(data_dir, "_hub_under_test.py")
    with open(path, "w", encoding="utf-8") as f:
        f.write(src)
    spec = importlib.util.spec_from_file_location("hub_under_test_%d" % os.getpid(), path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class HubTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="hub-selftest-")
        cls.auth_path = os.path.join(cls.tmp, "admin.token")
        cls.user, cls.password = "admin", "s3cret-token-value"
        with open(cls.auth_path, "w", encoding="utf-8") as f:
            f.write("%s:%s\n" % (cls.user, cls.password))
        os.chmod(cls.auth_path, 0o600)
        cls.mod = load_hub(cls.tmp, cls.auth_path)
        cls.releases = os.path.join(cls.tmp, "releases")
        os.makedirs(cls.releases, exist_ok=True)
        with open(os.path.join(cls.releases, "PocketKit-0.9.2.apk"), "wb") as f:
            f.write(b"existing-apk")
        os.symlink("PocketKit-0.9.2.apk", os.path.join(cls.releases, "latest.apk"))
        with open(os.path.join(cls.tmp, "config.json"), "w", encoding="utf-8") as f:
            json.dump({"versionName": "0.9.2", "versionCode": 20, "minSupportedCode": 1,
                       "title": "t", "changelog": "c", "downloadUrl": "u",
                       "configVersion": "2026.10.24.5", "proxyConfigUrl": "p",
                       "forceUpdate": False}, f, ensure_ascii=False, indent=2)
        cls.meta_before = open(os.path.join(cls.tmp, "config.json"), "rb").read()
        cls.httpd = HTTPServer(("127.0.0.1", 0), cls.mod.HubHandler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def req(self, method, path, body=None, headers=None, auth=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = dict(headers or {})
        if auth is not None:
            h["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
        conn.request(method, path, body=body, headers=h)
        r = conn.getresponse()
        data = r.read()
        status, hs = r.status, dict(r.getheaders())
        conn.close()
        return status, hs, data

    def auth(self):
        return "%s:%s" % (self.user, self.password)

    def assert_meta_unchanged(self):
        self.assertEqual(open(os.path.join(self.tmp, "config.json"), "rb").read(), self.meta_before)

    # --- 公共只读 ---
    def test_public_reads_stay_open(self):
        for p in ("/api/check-update", "/api/proxy-config", "/releases/PocketKit-0.9.2.apk"):
            s, _, _ = self.req("GET", p)
            self.assertEqual(s, 200, p)
        s, _, body = self.req("GET", "/api/check-update")
        self.assertEqual(json.loads(body)["versionCode"], 20)

    def test_rule_publication_is_separate_and_hash_named(self):
        import hashlib
        body = b'{"schemaVersion":1,"rules":{}}\n'
        digest = hashlib.sha256(body).hexdigest()
        os.makedirs(self.mod.RULES_DIR, exist_ok=True)
        artifact = os.path.join(self.mod.RULES_DIR, digest + ".json")
        with open(artifact, "wb") as stream:
            stream.write(body)
        manifest = {"rulesVersion": digest, "rulesSha256": digest, "rulesBytes": len(body),
                    "rulesUrl": self.mod.BASE_URL + "/rules/" + digest + ".json", "rulesSchemaVersion": 1,
                    "versionCode": 999, "proxyConfigUrl": "https://evil.test"}
        try:
            with open(self.mod.RULE_MANIFEST_FILE, "w", encoding="utf-8") as stream:
                json.dump(manifest, stream)
            status, headers, received = self.req("GET", "/rules/" + digest + ".json")
            self.assertEqual(status, 200)
            self.assertEqual(received, body)
            self.assertIn("immutable", headers["Cache-Control"])
            status, _, received = self.req("GET", "/api/check-update")
            metadata = json.loads(received)
            self.assertEqual(metadata["rulesVersion"], digest)
            self.assertEqual(metadata["versionCode"], 20)
            self.assertEqual(metadata["proxyConfigUrl"], "p")
            self.assert_meta_unchanged()
            for path in ("/rules/../admin.token", "/rules/.cache", "/rule-sources/../admin.token",
                         "/rules/" + "b" * 64 + ".json"):
                self.assertEqual(self.req("GET", path)[0], 404)
            self.assertEqual(self.req("GET", "/api/rule-status")[0], 200)
        finally:
            os.unlink(artifact)
            os.unlink(self.mod.RULE_MANIFEST_FILE)

    def test_admin_page_requires_auth(self):
        s, hs, _ = self.req("GET", "/")
        self.assertEqual(s, 401)
        self.assertTrue(hs.get("WWW-Authenticate", "").startswith("Basic "))
        s, _, body = self.req("GET", "/", auth=self.auth())
        self.assertEqual(s, 200)
        self.assertIn(b"save-all", body)

    # --- 匿名写 ---
    def test_anonymous_writes_rejected(self):
        s, _, _ = self.req("POST", "/api/admin/save-all",
                           body="versionName=HACK&versionCode=999&configVersion=HACK",
                           headers={"Content-Type": "application/x-www-form-urlencoded"})
        self.assertEqual(s, 401)
        s, _, _ = self.req("POST", "/api/upload-release", body=b"x", headers={"X-Filename": "evil.apk"})
        self.assertEqual(s, 401)
        self.assert_meta_unchanged()
        self.assertFalse(os.path.exists(os.path.join(self.releases, "evil.apk")))

    def test_wrong_and_malformed_credentials(self):
        for bad in ("admin:wrong", "admin", "admin:", ":x", "root:%s" % self.password,
                    "admin:%s " % self.password):
            s, _, _ = self.req("POST", "/api/upload-release", body=b"x",
                              headers={"X-Filename": "evil.apk"}, auth=bad)
            self.assertEqual(s, 401, bad)
        s, _, _ = self.req("GET", "/", headers={"Authorization": "Basic !!!not-base64!!!"})
        self.assertEqual(s, 401)
        s, _, _ = self.req("GET", "/", headers={"Authorization": "Bearer abc"})
        self.assertEqual(s, 401)

    def test_missing_token_file_fails_closed(self):
        target = os.path.join(self.tmp, "admin.token")
        backup = target + ".bak"
        os.rename(target, backup)
        try:
            s, _, _ = self.req("GET", "/", auth=self.auth())
            self.assertEqual(s, 401)
        finally:
            os.rename(backup, target)

    # --- 授权写 ---
    def test_authorized_save_preserves_missing_fields(self):
        self.assert_meta_unchanged()
        s, hs, _ = self.req("POST", "/api/admin/save-all", body="title=only-title",
                            headers={"Content-Type": "application/x-www-form-urlencoded"},
                            auth=self.auth())
        self.assertEqual(s, 303)
        self.assertEqual(hs.get("Location"), "/")
        cfg = json.load(open(os.path.join(self.tmp, "config.json"), encoding="utf-8"))
        self.assertEqual(cfg["title"], "only-title")
        self.assertEqual(cfg["versionName"], "0.9.2")
        self.assertEqual(cfg["versionCode"], 20)
        self.assertEqual(cfg["configVersion"], "2026.10.24.5")
        # 还原，供其它用例比对基线
        with open(os.path.join(self.tmp, "config.json"), "wb") as f:
            f.write(self.meta_before)

    def test_filename_validation(self):
        for evil in ("../evil.apk", "../../etc/cron.d/evil.apk", "sub/dir.apk",
                     "evil.txt", ".hidden.apk", "a b.apk", ""):
            s, _, _ = self.req("POST", "/api/upload-release", body=b"x",
                               headers={"X-Filename": evil}, auth=self.auth())
            self.assertEqual(s, 400, repr(evil))
        self.assertFalse(os.path.exists(os.path.join(os.path.dirname(self.tmp), "evil.apk")))

    def test_upload_size_and_encoding_validation(self):
        s, _, _ = self.req("POST", "/api/upload-release", body=b"",
                           headers={"X-Filename": "ok.apk", "Content-Length": "0"}, auth=self.auth())
        self.assertEqual(s, 413)
        s, _, _ = self.req("POST", "/api/admin/save-all", body=b"x",
                           headers={"Content-Type": "text/plain"}, auth=self.auth())
        self.assertEqual(s, 415)

    def test_authorized_upload_and_overwrite_is_atomic(self):
        s, _, body = self.req("POST", "/api/upload-release", body=b"new-apk-bytes",
                              headers={"X-Filename": "PocketKit-1.0.0.apk"}, auth=self.auth())
        self.assertEqual(s, 200)
        self.assertEqual(json.loads(body)["received"], 13)
        self.assertEqual(open(os.path.join(self.releases, "PocketKit-1.0.0.apk"), "rb").read(),
                         b"new-apk-bytes")
        self.assertFalse([n for n in os.listdir(self.releases) if n.startswith(".upload-")])
        # 覆盖已有 APK 仅在新文件完整落盘后发生
        s, _, _ = self.req("POST", "/api/upload-release", body=b"updated",
                           headers={"X-Filename": "PocketKit-0.9.2.apk"}, auth=self.auth())
        self.assertEqual(s, 200)
        self.assertEqual(open(os.path.join(self.releases, "PocketKit-0.9.2.apk"), "rb").read(),
                         b"updated")
        with open(os.path.join(self.releases, "PocketKit-0.9.2.apk"), "wb") as f:
            f.write(b"existing-apk")

    def test_token_rotation_takes_effect_without_restart(self):
        old = self.auth()
        with open(self.auth_path, "w", encoding="utf-8") as f:
            f.write("admin:rotated-value\n")
        try:
            s, _, _ = self.req("GET", "/", auth=old)
            self.assertEqual(s, 401)
            s, _, _ = self.req("GET", "/", auth="admin:rotated-value")
            self.assertEqual(s, 200)
        finally:
            with open(self.auth_path, "w", encoding="utf-8") as f:
                f.write("%s:%s\n" % (self.user, self.password))


if __name__ == "__main__":
    print("selftest:", SERVER_PY)
    unittest.main(verbosity=2)
