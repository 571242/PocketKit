"""Build the pinned, unmodified MOE mirror into an indexed read-only database.
Run from the project root. No translations or generated definitions are added.
"""
import pathlib,sys,json,lzma,hashlib,sqlite3,gzip,urllib.request,datetime
root=pathlib.Path(__file__).resolve().parents[1]
sources=root/'sources/moe';sources.mkdir(parents=True,exist_ok=True)
commit='a6dc997417507eb510fc29822bc514de2c92728c'
blob='4f457b60ff75dc8813ce87d68ee41fbc49f21b4a'
compressed_sha='5cc4ec0efd7e549621edf9b46d261989230c1729d9dc2c8e2056f4b21c8a93da'
source=sources/'dict-revised.json.xz'
if not source.exists():
 cached=root/'.scratch/lexicons/dict-revised.json.xz'
 if cached.exists(): source.write_bytes(cached.read_bytes())
 else:
  with urllib.request.urlopen(f'https://raw.githubusercontent.com/g0v/moedict-data/{commit}/dict-revised.json.xz',timeout=45) as r:
   source.write_bytes(r.read(24*1048576))
raw=source.read_bytes()
assert hashlib.sha256(raw).hexdigest()==compressed_sha
assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==blob
readme=sources/'README-original.txt'
if not readme.exists():
 with urllib.request.urlopen(f'https://raw.githubusercontent.com/g0v/moedict-data/{commit}/README.md',timeout=30) as r:
  notice=r.read(20000)
 assert hashlib.sha1(b'blob '+str(len(notice)).encode()+b'\0'+notice).hexdigest()=='7b4252780a29e322ec78c09bd8a21dbd736c44cc'
 readme.write_bytes(notice)
decoded=lzma.decompress(raw,memlimit=300*1048576)
assert hashlib.sha256(decoded).hexdigest()=='ad43e8e2d30341f2c2458e04fb05a3fc1b1cd486fb52eb8708d89bcfbe46530f'
entries=json.loads(decoded);assert isinstance(entries,list) and len(entries)==161194
work=root/'.scratch/offline-lexicon-build.sqlite';work.parent.mkdir(exist_ok=True)
assert not work.exists(),'Build work database already exists; inspect before replacing'
assets=root/'app/src/main/assets';target=assets/'moe-revised.pack';metapath=assets/'moe-revised-meta.json'
assert not target.exists() and not metapath.exists(),'Existing published assets must be inspected before regeneration'
connection=sqlite3.connect(work)
connection.executescript('PRAGMA page_size=4096; PRAGMA journal_mode=OFF; CREATE TABLE entries(ordinal INTEGER PRIMARY KEY,title TEXT NOT NULL,payload TEXT NOT NULL); CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);')
for ordinal,row in enumerate(entries):
 assert isinstance(row,dict) and isinstance(row.get('title'),str) and row['title']
 payload=json.dumps(row,ensure_ascii=False,separators=(',',':'))
 assert json.loads(payload)==row
 connection.execute('INSERT INTO entries VALUES(?,?,?)',(ordinal,row['title'],payload))
connection.execute('CREATE INDEX entry_title ON entries(title)')
for key,value in {'source_commit':commit,'source_blob':blob,'entries':str(len(entries)),'declared_edition':'中華民國110年11月臺灣學術網路第六版（鏡像README自述）','original_license':'CC BY-ND 3.0 Taiwan'}.items():connection.execute('INSERT INTO metadata VALUES(?,?)',(key,value))
connection.commit()
assert connection.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
assert connection.execute('SELECT COUNT(*) FROM entries').fetchone()[0]==len(entries)
for (ordinal,title,payload),original in zip(connection.execute('SELECT ordinal,title,payload FROM entries ORDER BY ordinal'),entries):
 assert title==original['title'] and json.loads(payload)==original
samples=['春','美麗','快樂','行']
fixture_dir=root/'tools/device-selftest/fixtures/moe';fixture_dir.mkdir(parents=True,exist_ok=True)
for word in samples:
 rows=connection.execute('SELECT payload FROM entries WHERE title=? ORDER BY ordinal',(word,)).fetchall();assert rows
 for index,(payload,) in enumerate(rows):
  path=fixture_dir/(str(samples.index(word))+'-'+str(index)+'.json')
  assert not path.exists(),'Inspect existing fixture before regeneration'
  path.write_text(payload,encoding='utf-8')
connection.close()
db=work.read_bytes();compressed=gzip.compress(db,compresslevel=9,mtime=0)
target.write_bytes(compressed)
meta={'source_commit':commit,'source_git_blob':blob,'source_url':f'https://raw.githubusercontent.com/g0v/moedict-data/{commit}/dict-revised.json.xz','source_compressed_sha256':compressed_sha,'source_json_sha256':hashlib.sha256(decoded).hexdigest(),'source_json_bytes':len(decoded),'entries':len(entries),'sqlite_sha256':hashlib.sha256(db).hexdigest(),'sqlite_bytes':len(db),'asset_gzip_sha256':hashlib.sha256(compressed).hexdigest(),'asset_gzip_bytes':len(compressed),'builder_sqlite_version':sqlite3.sqlite_version,'declared_edition':'中華民國110年11月臺灣學術網路第六版（鏡像README自述；不是官方最新下载版）','body_license':'CC BY-ND 3.0 Taiwan','format_editor':'kcwu / CC0','text_changed':False,'record_equality_verified':len(entries)}
metapath.write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
(root/'evidence/offline-lexicon-source.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(meta,ensure_ascii=False,indent=2))
