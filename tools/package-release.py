#!/usr/bin/env python3
import argparse, hashlib, pathlib, zipfile

EXCLUDED_DIRS={'build','.gradle','.scratch','__pycache__','node_modules','.git','dist'}
EXCLUDED_SUFFIXES={'.jks','.keystore','.apk'}
def main():
  ap=argparse.ArgumentParser(description='Create a source archive without build output, APKs, private keys or scratch files.')
  ap.add_argument('--root',type=pathlib.Path,required=True); ap.add_argument('--output',type=pathlib.Path,required=True); ap.add_argument('--sha',type=pathlib.Path,required=True)
  a=ap.parse_args(); root=a.root.resolve(); output=a.output.resolve(); a.output.parent.mkdir(parents=True,exist_ok=True); a.sha.parent.mkdir(parents=True,exist_ok=True)
  members=[]
  with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(root.rglob('*')):
      if not p.is_file() or p.resolve()==output: continue
      rel=p.relative_to(root).as_posix(); parts=set(rel.lower().split('/')); suffix=p.suffix.lower()
      if parts & EXCLUDED_DIRS or suffix in EXCLUDED_SUFFIXES: continue
      if any(part.startswith('cache') for part in parts): continue
      z.write(p,rel); members.append(rel)
  digest=hashlib.sha256(output.read_bytes()).hexdigest(); a.sha.write_text(f'{digest}  {output.name}\n',encoding='utf-8')
  print(f'PASS: source archive {output} members={len(members)} sha256={digest}')
if __name__=='__main__': main()

