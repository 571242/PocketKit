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
# Shares are received by the activity itself; "open with" locating is a separately labelled
# alias, so the chooser entry stays honest and the activity never claims to be a viewer.
actions={x.get(ns+'name') for x in activity.findall('./intent-filter/action')}
assert {'android.intent.action.SEND','android.intent.action.SEND_MULTIPLE'}<=actions
locate=next(x for x in app.findall('activity-alias') if x.get(ns+'name').endswith('.ManagerLocateActivity'))
assert locate.get(ns+'targetActivity').endswith('.ManagerActivity') and locate.get(ns+'exported')=='true'
assert locate.get(ns+'label')=='拾用·定位文件位置' and locate.get(ns+'priority') is None
assert {x.get(ns+'name') for x in locate.findall('./intent-filter/action')}=={'android.intent.action.VIEW'}
assert {x.get(ns+'name') for x in locate.findall('./intent-filter/category')}=={'android.intent.category.DEFAULT'}
# Two filters: a typed one for MIME-carrying intents and a scheme-only one for senders that call
# setData without a type. Neither may declare anything beyond VIEW + DEFAULT.
assert len(locate.findall('intent-filter'))==2
assert all({d.get(ns+'scheme') for d in f.findall('data')}=={'content','file'} for f in locate.findall('intent-filter'))
assert {(d.get(ns+'scheme'),d.get(ns+'mimeType')) for f in locate.findall('intent-filter') for d in f.findall('data') if d.get(ns+'mimeType')}=={('content','*/*'),('file','*/*')}
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
assert 'String[] labels = {"常用", "全部", "管理器"' in home
assert 'private String scope = "常用"' in home
assert '"置顶工具" : "最近使用"' in home
assert 'AutomationToolsActivity.class' in home
assert 'scope.equals("最近")' not in home
print('PASS: manager DEX classes, six compiled-package entries, share entry, labelled locate alias (VIEW/content+file/*/*, no priority), private share provider, home navigation contracts')
