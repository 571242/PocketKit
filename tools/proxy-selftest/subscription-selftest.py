"""Run the real subscription manager with in-memory Android stubs, entirely off-device."""
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / '.scratch/subscription-selftest'
JDK = pathlib.Path(os.environ.get('JAVA_HOME', 'F:/mlbuild/tools/jdk-17.0.20.1+1'))
JSON = ROOT / '.scratch/proxy-repair/org.json.jar'
YAML = ROOT / 'app/libs/snakeyaml-2.0.jar'
STUBS = {
    'android/content/Context.java': '''package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public Context getApplicationContext(){return this;}
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
 public java.io.File getFilesDir(){throw new UnsupportedOperationException();}
}''',
    'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 String getString(String key,String def); boolean getBoolean(String key,boolean def); Editor edit();
 interface Editor { Editor putString(String k,String v); Editor putBoolean(String k,boolean v); void apply(); }
}''',
    'android/util/Log.java': '''package android.util;
public final class Log { public static int w(String t,String m){return 0;} public static int w(String t,String m,Throwable e){return 0;} }''',
}


def main():
    src = OUT / 'src'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    files = []
    for name, text in STUBS.items():
        file = src / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding='utf-8')
        files.append(str(file))
    proxy = ROOT / 'app/src/main/java/cn/xianhua/pocketkit/proxy'
    test = ROOT / 'tools/proxy-selftest/src/cn/xianhua/pocketkit/proxy'
    files.extend(str(proxy / name) for name in ('ProxyConfig.java', 'ProxyRules.java', 'ProxyProfileManager.java'))
    files.append(str(ROOT / 'tools/proxy-selftest/stub/cn/xianhua/pocketkit/proxy/ProxyState.java'))
    files.append(str(test / 'SubscriptionCompatibilitySelfTest.java'))
    cp = os.pathsep.join(map(str, (JSON, YAML)))
    subprocess.run([str(JDK / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(classes), *files], check=True)
    subprocess.run([str(JDK / 'bin/java.exe'), '-Dfile.encoding=UTF-8',
                    '-Djava.io.tmpdir=' + str(OUT), '-cp', os.pathsep.join((str(classes), cp)),
                    'cn.xianhua.pocketkit.proxy.SubscriptionCompatibilitySelfTest'], check=True)


if __name__ == '__main__':
    main()
