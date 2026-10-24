"""Check compiled startup ordering; this is not Android device execution."""
import pathlib, subprocess
root = pathlib.Path(__file__).resolve().parents[2]
classes = root / 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
javap = pathlib.Path('F:/mlbuild/tools/jdk-17.0.20.1+1/bin/javap.exe')
result = subprocess.run([str(javap), '-c', '-p', '-classpath', str(classes), 'cn.xianhua.pocketkit.BaseToolActivity'], check=True, capture_output=True, text=True)
code = result.stdout
assert code.count('Window.getInsetsController:') == 1
assert code.index('setContentView:') < code.index('Window.getInsetsController:') < code.index('requestApplyInsets:')
print('PASS: compiled BaseToolActivity creates content before accessing WindowInsetsController; not device startup proof')
