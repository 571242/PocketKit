import pathlib,sqlite3,gzip,json,hashlib,lzma,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
meta=json.loads((root/'app/src/main/assets/moe-revised-meta.json').read_text(encoding='utf-8'))
compressed=(root/'app/src/main/assets/moe-revised.pack').read_bytes()
assert hashlib.sha256(compressed).hexdigest()==meta['asset_gzip_sha256']
data=gzip.decompress(compressed)
assert len(data)==meta['sqlite_bytes'] and hashlib.sha256(data).hexdigest()==meta['sqlite_sha256']
original=json.loads(lzma.decompress((root/'sources/moe/dict-revised.json.xz').read_bytes(),memlimit=300*1048576))
scratch=root/'.scratch';scratch.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='verify-lexicon-',dir=scratch) as directory:
 path=pathlib.Path(directory)/'dictionary.sqlite';path.write_bytes(data)
 connection=sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)
 try:
  assert connection.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  checked=0
  for ordinal,title,payload in connection.execute('SELECT ordinal,title,payload FROM entries ORDER BY ordinal'):
   assert ordinal==checked and title==original[ordinal]['title'] and json.loads(payload)==original[ordinal]
   checked+=1
  assert checked==len(original)==meta['entries']==161194
  for word in ['春','美麗','快樂','行']:
   values=connection.execute('SELECT payload FROM entries WHERE title=? ORDER BY ordinal',(word,)).fetchall();assert values
   print('lookup',word,'records',len(values),'heads',sum(len(json.loads(v[0])['heteronyms']) for v in values))
  for query in ["不存在的词qqzz", "美麗' OR 1=1 --", '', '\x00']:
   assert not connection.execute('SELECT payload FROM entries WHERE title=?',(query,)).fetchall()
  assert any('synonyms' in d and 'antonyms' in d for h in json.loads(connection.execute('SELECT payload FROM entries WHERE title=?',('美麗',)).fetchone()[0])['heteronyms'] for d in h['definitions'])
 finally:
  connection.close()
print('PASS:',checked,'original entries preserved; asset SHA, SQLite integrity, real queries, unknown/injection; independent of build cache')
