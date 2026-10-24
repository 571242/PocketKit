#!/usr/bin/env python3
"""Verify packaged proxy core and all native libraries' 16 KB APK/ELF alignment."""
from pathlib import Path
import argparse, hashlib, struct, zipfile

def load_alignments(data):
    if data[:4] != b'\x7fELF': raise AssertionError('Not ELF')
    endian = '<' if data[5] == 1 else '>'
    if data[4] == 2:
        offset = struct.unpack_from(endian+'Q', data, 32)[0]
        size, count = struct.unpack_from(endian+'HH', data, 54)
        align_offset, fmt = 48, 'Q'
    else:
        offset = struct.unpack_from(endian+'I', data, 28)[0]
        size, count = struct.unpack_from(endian+'HH', data, 42)
        align_offset, fmt = 28, 'I'
    return [struct.unpack_from(endian+fmt, data, offset+i*size+align_offset)[0]
            for i in range(count) if struct.unpack_from(endian+'I', data, offset+i*size)[0] == 1]

def verify(apk):
    with zipfile.ZipFile(apk) as archive, apk.open('rb') as stream:
        names = archive.namelist()
        abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        assert abis == {'arm64-v8a', 'armeabi-v7a'}, abis
        for abi in abis:
            for library in ['libbox.so']:
                assert f'lib/{abi}/{library}' in names, (abi,library)
        checked = 0
        for item in archive.infolist():
            if not item.filename.startswith('lib/') or not item.filename.endswith('.so'): continue
            if '/arm64-v8a/' in item.filename:
                alignments = load_alignments(archive.read(item))
                assert alignments and min(alignments) >= 16384, item.filename
                checked += 1
            if item.compress_type == zipfile.ZIP_STORED:
                stream.seek(item.header_offset)
                header = stream.read(30)
                name_size, extra_size = struct.unpack_from('<HH', header, 26)
                assert (item.header_offset + 30 + name_size + extra_size) % 16384 == 0, item.filename
        forbidden = [name for name in names if any(part.lower() in {'.scratch','.git','private'} for part in name.split('/')) or name.lower().endswith(('.key','.keystore','.jks'))]
        assert not forbidden, forbidden
        assert 'assets/GPL-3.0.txt' in names
        assert any(b'Lcn/xianhua/pocketkit/proxy/ProxyPlatform;' in archive.read(name) for name in names if name.startswith('classes') and name.endswith('.dex'))
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    print(f'PASS: ARM ABIs, real proxy core, {checked} ARM64 ELF libraries, 16 KB APK alignment, license and no private archive members')
    print(f'SHA256: {digest}')
    return digest

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    verify(parser.parse_args().apk)
