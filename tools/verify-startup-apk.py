import argparse,zipfile,pathlib,struct,xml.etree.ElementTree as ET
ap=argparse.ArgumentParser(description='Verify merged startup APK invariants without claiming device startup.')
ap.add_argument('--version-name',default='0.2.0',help='Expected versionName (default keeps the legacy gate)')
ap.add_argument('--version-code',default='8',help='Expected versionCode (default keeps the legacy gate)')
args=ap.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
manifest=root/'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml'
android='{http://schemas.android.com/apk/res/android}'
doc=ET.parse(manifest).getroot()
assert not any(e.get(android+'name')=='com.google.mlkit.common.internal.MlKitInitProvider' for e in doc.findall('.//provider'))
assert doc.find('uses-sdk').get(android+'minSdkVersion')=='26'
assert doc.find('uses-sdk').get(android+'targetSdkVersion')=='36'
assert doc.get(android+'versionCode')==str(args.version_code)
assert doc.get(android+'versionName')==args.version_name
with zipfile.ZipFile(apk) as z, apk.open('rb') as stream:
 names=z.namelist();dex=sorted(n for n in names if n.startswith('classes') and n.endswith('.dex') and '/' not in n)
 assert set(dex)=={'classes.dex',*[f'classes{i}.dex' for i in range(2,len(dex)+1)]}
 assert any(b'Lcn/xianhua/pocketkit/MainActivity;' in z.read(n) for n in dex)
 assert any(b'Lcn/xianhua/pocketkit/StartupDiagnostics;' in z.read(n) for n in dex)
 assert any(b'Lcn/xianhua/pocketkit/MlKitBootstrap;' in z.read(n) for n in dex)
 libs=[i for i in z.infolist() if i.filename.startswith('lib/') and i.filename.endswith('.so')]
 assert {i.filename.split('/')[1] for i in libs}=={'arm64-v8a','armeabi-v7a','x86','x86_64'}
 for info in libs:
  stream.seek(info.header_offset);header=stream.read(30)
  name_len,extra_len=struct.unpack_from('<HH',header,26)
  if info.compress_type==zipfile.ZIP_STORED:assert (info.header_offset+30+name_len+extra_len)%16384==0,info.filename
print(f'PASS: merged manifest no eager MLKit Provider, version{args.version_code} / {args.version_name}-startup, Android26+, continuous DEX, launcher/diagnostics classes, four ABIs and 16KiB ZIP alignment; not device startup proof')
