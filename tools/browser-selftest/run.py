"""Standalone browser checks. Run from any directory after resolving app dependencies."""
import os
from pathlib import Path
import subprocess
import zipfile
import shutil

root = Path(__file__).resolve().parents[2]
jdk = Path(os.environ.get('JAVA_HOME', 'F:/mlbuild/tools/jdk-17.0.20.1+1'))
sdk = Path(os.environ.get('ANDROID_HOME', 'F:/mlbuild/sdk'))
work = root / '.scratch/browser-selftest-run'
work.mkdir(parents=True, exist_ok=True)
classes = work / 'classes'
classes.mkdir(exist_ok=True)
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
aars = list((cache / 'caches/modules-2/files-2.1/androidx.webkit/webkit/1.12.1').glob('*/*.aar'))
if not aars:
    raise SystemExit('Resolve androidx.webkit:webkit:1.12.1 with Gradle first')
with zipfile.ZipFile(aars[0]) as archive:
    (work / 'webkit.jar').write_bytes(archive.read('classes.jar'))
cp = os.pathsep.join(map(str, [classes, sdk / 'platforms/android-36/android.jar', work / 'webkit.jar']))
source = root / 'app/src/main/java/cn/xianhua/pocketkit/browser'
tests = Path(__file__).resolve().parent
sources = [source / name for name in ['SessionArchive.java', 'BrowserUrls.java', 'BrowserPrivacy.java']]
sources += [tests / name for name in ['SessionArchiveTest.java', 'ArchiveBoundaryTest.java', 'BrowserUrlsTest.java', 'BrowserPrivacyTest.java']]
subprocess.run([str(jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes), *map(str, sources)], cwd=root, check=True)
for test in ['BrowserUrlsTest', 'BrowserPrivacyTest', 'SessionArchiveTest', 'ArchiveBoundaryTest']:
    subprocess.run([str(jdk / 'bin/java.exe'), '-Xmx256m', '-cp', cp, test], cwd=root, check=True)
print('PASS browser JVM suite')
# Exact task-owned build output only; runtime test fixtures are cleaned at release handoff.
assert work.resolve() == (root / '.scratch/browser-selftest-run').resolve()
shutil.rmtree(work)
