#!/usr/bin/env python3
import argparse, json, pathlib, struct, zipfile, xml.etree.ElementTree as ET

def forbidden_archive_members(names):
    """Return (member, reason) pairs for archive paths that can leak build secrets."""
    bad = []
    for raw in names:
        normalized = raw.replace('\\', '/')
        parts = [part for part in normalized.split('/') if part not in ('', '.') ]
        lower = [part.lower() for part in parts]
        if any(part == '..' for part in lower):
            bad.append((raw, 'parent traversal'))
            continue
        if lower and (lower[0] in {'build', '.scratch'} or lower[0].startswith('private')):
            bad.append((raw, 'root-level build/.scratch/private member'))
            continue
        if any('.jks' in part or '.keystore' in part for part in lower):
            bad.append((raw, 'keystore material'))
    return bad

def main():
    ap = argparse.ArgumentParser(description='Verify a completed PocketKit release; never builds or copies artifacts.')
    ap.add_argument('--apk', type=pathlib.Path, required=True)
    ap.add_argument('--manifest', type=pathlib.Path, required=True)
    ap.add_argument('--catalog', type=pathlib.Path, required=True)
    ap.add_argument('--coverage', type=pathlib.Path, required=True, help='JSON produced after final compiled-class reflection coverage')
    ap.add_argument('--version-name', default='0.2.0', help='Expected versionName (default keeps the legacy release gate)')
    ap.add_argument('--version-code', default='8', help='Expected versionCode (default keeps the legacy release gate)')
    ap.add_argument('--abis', nargs='+', default=['arm64-v8a', 'armeabi-v7a'], help='Expected packaged device ABIs')
    args = ap.parse_args()
    for path in (args.apk, args.manifest, args.catalog, args.coverage):
        if not path.is_file(): raise SystemExit(f'FAIL: missing required final-build evidence: {path}')
    android = 'http://schemas.android.com/apk/res/android'
    doc = ET.parse(args.manifest).getroot()
    assert doc.get('{%s}versionCode' % android) == str(args.version_code), f'versionCode is not {args.version_code}'
    assert doc.get('{%s}versionName' % android) == args.version_name, f'versionName is not {args.version_name}'
    catalog = json.loads(args.catalog.read_text(encoding='utf-8'))
    ids = {str(row['id']) for row in catalog}
    coverage = json.loads(args.coverage.read_text(encoding='utf-8'))
    supported = {str(x) for x in coverage.get('supportedIds', [])}
    if not supported: raise AssertionError('coverage has no supportedIds; compiled reflection evidence is required')
    assert supported == ids, f'compiled support mismatch missing={sorted(ids-supported)} extra={sorted(supported-ids)}'
    assert coverage.get('source') == 'compiled-reflection', 'coverage is not compiled reflection evidence'
    removed = {'009','093','098','099','110','112','049'}
    assert not (ids & removed), f'removed IDs still in source catalog: {sorted(ids & removed)}'
    assert '106' in ids, '106 must be present in final catalog'
    with zipfile.ZipFile(args.apk) as z, args.apk.open('rb') as stream:
        names = z.namelist()
        apk_catalog = json.loads(z.read('assets/selected-tools.json').decode('utf-8'))
        assert apk_catalog == catalog, 'APK selected-tools.json differs from source catalog'
        apk_ids = {str(row['id']) for row in apk_catalog}
        assert not (apk_ids & removed), f'removed IDs in APK catalog: {sorted(apk_ids & removed)}'
        assert '106' in apk_ids, '106 missing from APK catalog'
        dex = [n for n in names if n.startswith('classes') and n.endswith('.dex') and '/' not in n]
        assert dex and 'classes.dex' in dex, 'missing DEX'
        assert any(b'Lcn/xianhua/pocketkit/MainActivity;' in z.read(n) for n in dex), 'missing launcher class'
        assert any(b'Lcn/xianhua/pocketkit/MlKitBootstrap;' in z.read(n) for n in dex), 'missing MLKit bootstrap class'
        abis = {n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')}
        assert abis == set(args.abis), f'ABI set mismatch: {abis}, expected {args.abis}'
        for info in z.infolist():
            if info.filename.startswith('lib/') and info.filename.endswith('.so') and info.compress_type == zipfile.ZIP_STORED:
                stream.seek(info.header_offset); header = stream.read(30); name_len, extra_len = struct.unpack_from('<HH', header, 26)
                assert (info.header_offset + 30 + name_len + extra_len) % 16384 == 0, f'16KiB alignment: {info.filename}'
        forbidden = forbidden_archive_members(names)
        if forbidden:
            details = ', '.join(f'{name} ({reason})' for name, reason in forbidden)
            raise AssertionError(f'forbidden release archive member(s): {details}')
    print(f'PASS: PocketKit {args.version_name}/code{args.version_code}; catalog={len(ids)} supported={len(supported)}; archive and alignment checks passed')

if __name__ == '__main__': main()
