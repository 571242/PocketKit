#!/usr/bin/env python3
import importlib.util
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_verify", ROOT / "tools" / "release-verify.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

accepted = [
    "META-INF/com/android/build/gradle/app-metadata.properties",
    "classes.dex",
    "lib/arm64-v8a/libc.so",
]
assert module.forbidden_archive_members(accepted) == [], "legal archive members rejected"

rejected = [
    "build/intermediates/secrets.txt",
    ".scratch/debug-dump.txt",
    "private/config.json",
    "assets/keys/release-key.jks",
    "META-INF/signing.keystore.bak",
]
bad = module.forbidden_archive_members(rejected)
assert [name for name, _ in bad] == rejected, bad
assert all(reason for _, reason in bad), bad
print("PASS: release archive classifier accepts AGP META-INF metadata and rejects root workdirs/keystore material")
