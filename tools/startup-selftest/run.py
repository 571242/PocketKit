import pathlib,tempfile,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
jdk=pathlib.Path('F:/mlbuild/tools/jdk-17.0.20.1+1/bin')
stubs={
'android/content/Context.java':'package android.content; public class Context { public android.content.res.AssetManager getAssets(){throw new UnsupportedOperationException();} }',
'android/content/res/AssetManager.java':'package android.content.res; public class AssetManager { public java.io.InputStream open(String name)throws java.io.IOException {throw new UnsupportedOperationException();} }',
'android/util/Log.java':'package android.util; public class Log {public static int e(String tag,String text,Throwable failure){return 0;}}',
'org/json/JSONException.java':'package org.json; public class JSONException extends Exception {}',
'org/json/JSONObject.java':'package org.json; public class JSONObject {private final java.util.Map<String,String> fields; public JSONObject(java.util.Map<String,String> f){fields=f;} public String getString(String key)throws JSONException {if(!fields.containsKey(key))throw new JSONException();return fields.get(key);} public JSONArray optJSONArray(String key){return null;}}',
'org/json/JSONArray.java':'package org.json; public class JSONArray {public JSONArray(String text)throws JSONException{throw new UnsupportedOperationException();} public int length(){throw new UnsupportedOperationException();} public JSONObject getJSONObject(int i)throws JSONException{throw new UnsupportedOperationException();} public String getString(int i)throws JSONException{throw new UnsupportedOperationException();}}'
}
scratch=root/'.scratch';scratch.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='registry-host-',dir=scratch) as directory:
 base=pathlib.Path(directory);sources=[]
 for name,source in stubs.items():
  path=base/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8');sources.append(str(path))
 sources += [str(root/'app/src/main/java/cn/xianhua/pocketkit/ToolRegistry.java'),str(root/'tools/startup-selftest/RegistryInitializationTest.java')]
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(base),*sources],check=True,cwd=root)
 subprocess.run([str(jdk/'java.exe'),'-Dfile.encoding=UTF-8','-cp',str(base),'cn.xianhua.pocketkit.RegistryInitializationTest'],check=True,cwd=root)
