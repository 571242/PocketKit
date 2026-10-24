"""Offline self-checks for rule conversion, immutable publication and failure rollback."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SCRATCH = HERE.parents[1] / ".scratch" / "proxy-rules-094-tests"
SCRATCH.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location("rule_sync", HERE / "rules-sync.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def payload(values):
    return ("payload:\n" + "".join("  - '" + value + "'\n" for value in values)).encode()


def fixture():
    return {"direct": payload(["+.cn", "apple-cdn.example.com"] + ["+.cn%d.example.com" % i for i in range(600)]),
            "proxy": payload(["scholar.google.cn", "+.apple.com"] + ["+.p%d.example.com" % i for i in range(600)]),
            "cncidr": payload(["10.%d.0.0/16" % i for i in range(128)])}


class RuleTest(unittest.TestCase):
    def test_domain_semantics_and_deterministic_json(self):
        raw = fixture()
        body, counts = sync.make_bundle(raw)
        parsed = json.loads(body)
        self.assertEqual(parsed["schemaVersion"], 1)
        self.assertIn("cn", parsed["rules"]["direct"]["domain_suffix"])
        self.assertIn("apple-cdn.example.com", parsed["rules"]["direct"]["domain"])
        self.assertIn("scholar.google.cn", parsed["rules"]["proxy"]["domain"])
        self.assertEqual(body, sync.make_bundle(raw)[0])
        self.assertEqual(sum(counts.values()), 1332)

    def test_empty_html_unknown_syntax_and_invalid_domains_rejected(self):
        for bad in (b"<html>error</html>", b"payload:\n", b"payload:\n  - !include /etc/passwd\n",
                    payload(["+.com;touch /tmp/x"]), payload(["*.example.com"]), payload(["-bad.example"])):
            with self.assertRaises(ValueError, msg=repr(bad)):
                sync.domains(bad)

    def test_cidr_validation(self):
        for bad in ("0.0.0.0/0", "::/0", "1.2.3.4/8", "host.example/24", "8.8.8.8/33"):
            with self.assertRaises(ValueError, msg=bad):
                sync.cidrs(payload([bad]))
        self.assertEqual(sync.cidrs(payload(["1.0.1.0/24", "2400:3200::/32"])), ["1.0.1.0/24", "2400:3200::/32"])

    def test_small_partial_dataset_rejected(self):
        raw = fixture()
        raw["proxy"] = payload(["+.example.com"])
        with self.assertRaises(ValueError):
            sync.make_bundle(raw)

    def test_trust_boundaries(self):
        self.assertTrue(sync.trusted_url(sync.REF_URL))
        for url in ("http://api.github.com/x", "https://evil.test/x", "https://api.github.com.evil.test/x",
                    "https://user:password@api.github.com/x", "https://api.github.com:444/x"):
            self.assertFalse(sync.trusted_url(url), url)

    def test_conditional_fetch_and_corrupt_cache(self):
        from unittest.mock import patch
        import urllib.error
        class Response(io.BytesIO):
            status = 200
            headers = {"ETag": '"snapshot"'}
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                if len(requests) == 1:
                    return Response(b"safe cached payload")
                raise urllib.error.HTTPError(request.full_url, 304, "unchanged", {}, None)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory, patch.object(sync.urllib.request, "build_opener", return_value=Opener()):
            cache = Path(directory)
            first = sync.fetch(sync.REF_URL, cache)
            self.assertEqual(sync.fetch(sync.REF_URL, cache), first)
            self.assertEqual(requests[-1].get_header("If-none-match"), '"snapshot"')
            next(cache.glob("*.body")).write_bytes(b"corrupt")
            with self.assertRaises(urllib.error.HTTPError):
                sync.fetch(sync.REF_URL, cache)
            self.assertIsNone(requests[-1].get_header("If-none-match"))

    def test_publish_hash_sources_unchanged_and_rollback(self):
        raw = fixture()
        bad = {"enabled": False}
        urls = []
        def get(url, cache):
            urls.append(url)
            if url == sync.REF_URL:
                return sync.encoded({"object": {"sha": "a" * 40}})
            if url == sync.LICENSE_URL:
                return b"GNU GENERAL PUBLIC LICENSE\nVersion 3\n"
            if "/releases/latest" in url:
                return sync.encoded({"tag_name": "v1.0", "published_at": "2026-10-24T00:00:00Z", "body": "test notes"})
            name = url.rsplit("/", 1)[1].split(".")[0]
            return b"not rules" if bad["enabled"] and name == "proxy" else raw[name]
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            self.assertEqual(sync.sync(root, get), 0)
            before = (root / "rules-manifest.json").read_bytes()
            manifest = json.loads(before)
            body = (root / "rules" / (manifest["rulesVersion"] + ".json")).read_bytes()
            self.assertEqual(hashlib.sha256(body).hexdigest(), manifest["rulesSha256"])
            self.assertEqual(len(body), manifest["rulesBytes"])
            for source in manifest["rulesSources"]:
                original = (root / "rule-sources" / (source["sha256"] + ".txt")).read_bytes()
                self.assertEqual(hashlib.sha256(original).hexdigest(), source["sha256"])
                self.assertIn("/" + "a" * 40 + "/", source["url"])
            self.assertEqual(sync.sync(root, get), 0)
            self.assertEqual(before, (root / "rules-manifest.json").read_bytes())
            self.assertFalse(sync.read_json(root / "rules-state.json", {})["changed"])
            bad["enabled"] = True
            self.assertEqual(sync.sync(root, get), 1)
            self.assertEqual(before, (root / "rules-manifest.json").read_bytes())
            self.assertEqual(body, (root / "rules" / (manifest["rulesVersion"] + ".json")).read_bytes())
            state = sync.read_json(root / "rules-state.json", {})
            self.assertTrue(state["lastError"])
            self.assertEqual(state["active"]["rulesVersion"], manifest["rulesVersion"])

    def test_native_failure_keeps_previous_manifest(self):
        raw = fixture()
        def get(url, cache):
            if url == sync.REF_URL:
                return sync.encoded({"object": {"sha": "a" * 40}})
            if url == sync.LICENSE_URL:
                return b"GNU GENERAL PUBLIC LICENSE\nVersion 3\n"
            if "/releases/latest" in url:
                raise OSError("offline")
            return raw[url.rsplit("/", 1)[1].split(".")[0]]
        def reject(body):
            raise ValueError("native check rejected")
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            self.assertEqual(sync.sync(root, get), 0)
            before = (root / "rules-manifest.json").read_bytes()
            self.assertEqual(sync.sync(root, get, reject), 1)
            self.assertEqual(before, (root / "rules-manifest.json").read_bytes())
            self.assertEqual(sync.read_json(root / "rules-state.json", {})["upstreamErrors"],
                             {"flclash": "OSError", "mihomo": "OSError", "singbox": "OSError"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
