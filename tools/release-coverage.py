#!/usr/bin/env python3
import argparse, json, pathlib, subprocess, tempfile, zipfile

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
        out.append("E\t").append(id).append("\t").append(e.toString()).append('\n');
      }
    }
    Files.writeString(Paths.get(args[1]), out.toString());
  }
}'''

def main():
    ap = argparse.ArgumentParser(description='Verify final compiled tool entry points, with their runtime dependencies.')
    ap.add_argument('--classes', type=pathlib.Path, required=True)
    ap.add_argument('--catalog', type=pathlib.Path, required=True)
    ap.add_argument('--out', type=pathlib.Path, required=True)
    a = ap.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    jdk = pathlib.Path('F:/mlbuild/tools/jdk-17.0.20.1+1')
    android = pathlib.Path('F:/mlbuild/sdk/platforms/android-36/android.jar')
    for path in (a.classes, a.catalog):
        if not path.exists(): raise SystemExit(f'FAIL missing: {path}')
    scratch = root / '.scratch'; scratch.mkdir(exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix='release-coverage-', dir=scratch))
    java = work / 'CompiledCoverageProbe.java'; java.write_text(PROBE, encoding='utf-8')
    classes = work / 'classes'; classes.mkdir(); raw = work / 'coverage.tsv'
    dependencies = []
    artifacts = root / '.scratch/runtime-artifacts.tsv'
    if artifacts.exists():
        dependencies += [pathlib.Path(row.split('\t', 1)[1]) for row in artifacts.read_text(encoding='utf-8').splitlines() if '\t' in row]
    dependencies += [root / 'app/libs' / name for name in ['libbox-1.14.3.aar', 'snakeyaml-2.0.jar', 'jsch-0.2.20.jar', 'smbj-0.13.0.jar', 'mbassador-1.3.0.jar', 'asn-one-0.6.0.jar', 'slf4j-api-2.0.13.jar']]
    cp = [str(classes), str(a.classes.resolve()), str(android)]
    for i, path in enumerate(dependencies):
        if path.suffix == '.aar':
            with zipfile.ZipFile(path) as archive:
                if 'classes.jar' not in archive.namelist(): continue
                jar = work / f'dependency-{i}.jar'; jar.write_bytes(archive.read('classes.jar')); cp.append(str(jar))
        else: cp.append(str(path))
    subprocess.run([str(jdk/'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), str(java)], check=True)
    subprocess.run([str(jdk/'bin/java.exe'), '-Dfile.encoding=UTF-8', '-cp', ';'.join(cp), 'CompiledCoverageProbe', str(a.catalog), str(raw)], check=True)
    catalog = json.loads(a.catalog.read_text(encoding='utf-8'))
    rows = [line.split('\t') for line in raw.read_text(encoding='utf-8').splitlines() if line]
    supported = [row[1] for row in rows if row[0] == 'S']
    errors = ['\t'.join(row[1:]) for row in rows if row[0] != 'S']
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps({'source':'compiled-reflection', 'catalogCount':len(catalog), 'supportedIds':supported, 'errors':errors}, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    if errors or set(supported) != {row['id'] for row in catalog}: raise SystemExit('FAIL compiled coverage: ' + str(errors))
    print(f'PASS compiled reflection: {len(supported)}/{len(catalog)}')

if __name__ == '__main__': main()
