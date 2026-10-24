#!/usr/bin/env python3
import argparse, json, pathlib, subprocess, shutil

PROBE = r'''import java.nio.file.*;
import java.lang.reflect.*;
import java.util.regex.*;
public final class CompiledCoverageProbe {
  public static void main(String[] args) throws Exception {
    String text = Files.readString(Paths.get(args[0]));
    Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\".*?\"module\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL).matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String id = m.group(1), module = m.group(2);
      try {
        Class<?> c = Class.forName("cn.xianhua.pocketkit." + module);
        Method f = c.getMethod("supports", String.class);
        if (Boolean.TRUE.equals(f.invoke(null, id))) out.append("S\t").append(id).append('\n');
        else out.append("U\t").append(id).append('\n');
      } catch (Throwable e) {
        out.append("E\t").append(id).append("\t").append(e.getClass().getName()).append('\n');
      }
    }
    Files.writeString(Paths.get(args[1]), out.toString());
  }
}'''

def main():
    ap=argparse.ArgumentParser(description='Generate compiled-reflection coverage after final Gradle classes exist.')
    ap.add_argument('--classes',type=pathlib.Path,required=True); ap.add_argument('--catalog',type=pathlib.Path,required=True); ap.add_argument('--out',type=pathlib.Path,required=True)
    a=ap.parse_args(); root=pathlib.Path(__file__).resolve().parents[1]; jdk=pathlib.Path(r'F:/mlbuild/tools/jdk-17.0.20.1+1'); android=pathlib.Path(r'F:/mlbuild/sdk/platforms/android-36/android.jar')
    for p in (a.classes,a.catalog):
        if not p.exists(): raise SystemExit(f'FAIL missing: {p}')
    work=root/'.scratch/release-coverage'; shutil.rmtree(work,ignore_errors=True); work.mkdir(parents=True)
    java=work/'CompiledCoverageProbe.java'; java.write_text(PROBE,encoding='utf-8'); probe_classes=work/'classes'; probe_classes.mkdir(); raw=work/'coverage.tsv'
    cp=str(android)+';'+str(a.classes); subprocess.run([str(jdk/'bin/javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(probe_classes),str(java)],check=True)
    run_cp=str(probe_classes)+';'+str(a.classes)+';'+str(android); subprocess.run([str(jdk/'bin/java.exe'),'-Dfile.encoding=UTF-8','-cp',run_cp,'CompiledCoverageProbe',str(a.catalog),str(raw)],check=True)
    ids=json.loads(a.catalog.read_text(encoding='utf-8')); rows=[line.split('\t') for line in raw.read_text(encoding='utf-8').splitlines() if line]; supported=[r[1] for r in rows if r[0]=='S']; errors=[r[1]+':'+r[2] for r in rows if r[0]=='E']
    a.out.parent.mkdir(parents=True,exist_ok=True); a.out.write_text(json.dumps({'source':'compiled-reflection','catalogCount':len(ids),'supportedIds':supported,'errors':errors},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"PASS: compiled reflection coverage catalog={len(ids)} supported={len(supported)} errors={len(errors)}")

if __name__=='__main__': main()
