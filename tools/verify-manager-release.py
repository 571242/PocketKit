#!/usr/bin/env python3
"""Read-only package/entrypoint contract gate; not a substitute for device tests."""
import argparse, json, pathlib, zipfile, xml.etree.ElementTree as ET
p=argparse.ArgumentParser()
p.add_argument('--apk',required=True,type=pathlib.Path)
p.add_argument('--manifest',required=True,type=pathlib.Path)
p.add_argument('--root',type=pathlib.Path,default=pathlib.Path('.'))
a=p.parse_args()
ns='{http://schemas.android.com/apk/res/android}'
root=ET.parse(a.manifest).getroot();app=root.find('application')
activity=next(x for x in app.findall('activity') if x.get(ns+'name').endswith('.ManagerActivity'))
assert activity.get(ns+'exported')=='true'
actions={x.get(ns+'name') for x in activity.findall('./intent-filter/action')}
assert {'android.intent.action.VIEW','android.intent.action.SEND','android.intent.action.SEND_MULTIPLE'}<=actions
provider=next(x for x in app.findall('provider') if x.get(ns+'name').endswith('.ManagerShareProvider'))
assert provider.get(ns+'exported')=='false' and provider.get(ns+'grantUriPermissions')=='true'
assert provider.get(ns+'authorities')=='cn.xianhua.pocketkit.manager.share'
with zipfile.ZipFile(a.apk) as z:
    catalog=json.loads(z.read('assets/selected-tools.json'))
    source=json.loads((a.root/'app/src/main/assets/selected-tools.json').read_text(encoding='utf-8'))
    assert catalog==source
    for i in range(1,7):
        entry=next(x for x in catalog if x['id']==f'M{i:02}')
        assert entry['module']=='ManagerActivity'
    dex=[z.read(x) for x in z.namelist() if x.startswith('classes') and x.endswith('.dex')]
    for name in ('ManagerActivity','ManagerStore','ManagerAlgorithms','ManagerShareProvider'):
        assert any(f'Lcn/xianhua/pocketkit/{name};'.encode() in b for b in dex),name
home=(a.root/'app/src/main/java/cn/xianhua/pocketkit/MainActivity.java').read_text(encoding='utf-8')
assert 'new String[]{"常用","全部","管理器"}' in home
assert 'private String scope="常用"' in home
assert '"置顶工具":"最近使用"' in home
assert 'AutomationToolsActivity.class' in home
assert 'scope.equals("最近")' not in home
print('PASS: manager DEX classes, six compiled-package entries, VIEW/SEND/SEND_MULTIPLE, private share provider, home navigation contracts')
