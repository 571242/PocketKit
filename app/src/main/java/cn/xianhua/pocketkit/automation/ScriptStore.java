package cn.xianhua.pocketkit.automation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Private script storage. Template assets are owned by TemplateStore and imports commit as a batch. */
public final class ScriptStore {
    public static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    private static final Object STORAGE_LOCK = new Object();
    private final Context context;
    private final File scripts;
    public ScriptStore(Context value){if(value==null)throw new IllegalArgumentException("脚本存储需要Context");context=value.getApplicationContext();scripts=new File(context.getFilesDir(),"automation/scripts");if(!scripts.exists()&&!scripts.mkdirs())throw new IllegalStateException("无法创建脚本私有目录");}
    public List<Script> list(){synchronized(STORAGE_LOCK){List<Script>out=new ArrayList<>();File[]files=scripts.listFiles((dir,name)->name.endsWith(".json"));if(files==null)return out;for(File f:files){try{out.add(loadInternal(f));}catch(RuntimeException ignored){}}return out;}}
    public Script load(String id){synchronized(STORAGE_LOCK){checkId(id);return loadInternal(scriptFile(id));}}
    public void save(Script script){synchronized(STORAGE_LOCK){script.validate();checkId(script.id);writeAtomic(scriptFile(script.id),ScriptCodec.encode(script));}}
    public void saveDraft(Script script){synchronized(STORAGE_LOCK){checkId(script.id);writeAtomic(scriptFile(script.id),ScriptCodec.encodeDraft(script));}}
    public void delete(String id){synchronized(STORAGE_LOCK){checkId(id);File f=scriptFile(id);if(f.exists()&&!f.delete())throw new IllegalStateException("脚本删除失败");}}
    public String exportScript(Script source){synchronized(STORAGE_LOCK){source.validate();try{JSONObject root=new JSONObject(ScriptCodec.encode(source));JSONObject assets=new JSONObject();TemplateStore store=new TemplateStore(context);for(String id:referenced(source)){byte[]data=store.read(id);assets.put(id,Base64.getEncoder().encodeToString(data));}root.put("templates",assets);String out=root.toString();if(out.getBytes(StandardCharsets.UTF_8).length>MAX_TRANSFER_BYTES)throw new IllegalArgumentException("导出文件超过16MiB");return out;}catch(Exception ex){if(ex instanceof IllegalArgumentException)throw(IllegalArgumentException)ex;throw new IllegalArgumentException("脚本导出失败",ex);}}}
    public Script importScript(String text){synchronized(STORAGE_LOCK){if(text==null||text.getBytes(StandardCharsets.UTF_8).length>MAX_TRANSFER_BYTES)throw new IllegalArgumentException("导入文件超过16MiB");Script imported;Map<String,byte[]>assets=new HashMap<>();try{JSONObject root=new JSONObject(text);if(root.has("templates")&&!(root.get("templates") instanceof JSONObject))throw new IllegalArgumentException("模板字段类型无效");JSONObject encoded=root.optJSONObject("templates");if(encoded==null)encoded=new JSONObject();JSONObject scriptJson=new JSONObject(root.toString());scriptJson.remove("templates");imported=ScriptCodec.decode(scriptJson.toString());for(String old:referenced(imported)){if(!encoded.has(old)||encoded.isNull(old)||!(encoded.get(old) instanceof String))throw new IllegalArgumentException("模板数据类型无效: "+old);byte[]data=Base64.getDecoder().decode((String)encoded.get(old));validateImage(data);assets.put(old,data);}imported.id=UUID.randomUUID().toString();imported.updatedAt=System.currentTimeMillis();imported.validate();}catch(Exception ex){if(ex instanceof IllegalArgumentException)throw(IllegalArgumentException)ex;throw new IllegalArgumentException("脚本导入失败",ex);}Map<String,String>remap=new HashMap<>();List<String>created=new ArrayList<>();try{TemplateStore store=new TemplateStore(context);for(Map.Entry<String,byte[]>entry:assets.entrySet()){String fresh=store.saveBytes(entry.getValue());created.add(fresh);remap.put(entry.getKey(),fresh);}remapSteps(imported.steps,remap);imported.validate();writeAtomic(scriptFile(imported.id),ScriptCodec.encode(imported));return imported;}catch(Exception ex){TemplateStore store=new TemplateStore(context);for(String id:created){try{store.delete(id);}catch(Exception ignored){}}throw new IllegalArgumentException("脚本导入提交失败",ex);}}}
    private Script loadInternal(File file){try{String text=new String(readBytes(file),StandardCharsets.UTF_8);JSONObject root=new JSONObject(text);if(root.has("draft")&&!(root.get("draft") instanceof Boolean))throw new IllegalArgumentException("draft字段类型无效");return root.optBoolean("draft",false)?ScriptCodec.decodeDraft(text):ScriptCodec.decode(text);}catch(Exception ex){throw new IllegalArgumentException("脚本读取失败",ex);}}
    private List<String>referenced(Script s){List<String>ids=new ArrayList<>();collect(s.steps,ids);return ids;}
    private void collect(List<Step>list,List<String>ids){for(Step s:list){if(!s.templateId.isEmpty()&&!ids.contains(s.templateId))ids.add(s.templateId);collect(s.children,ids);collect(s.otherwise,ids);}}
    private void remapSteps(List<Step>list,Map<String,String>map){for(Step s:list){if(map.containsKey(s.templateId))s.templateId=map.get(s.templateId);remapSteps(s.children,map);remapSteps(s.otherwise,map);}}
    private void validateImage(byte[]data){if(data==null||data.length==0||data.length>TemplateStore.MAX_BYTES)throw new IllegalArgumentException("模板大小无效");BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>TemplateStore.MAX_PIXELS)throw new IllegalArgumentException("模板像素尺寸无效");Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,null);if(b==null)throw new IllegalArgumentException("模板不是有效图片");b.recycle();}
    private File scriptFile(String id){return new File(scripts,id+".json");}
    private static void checkId(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("非法存储id");}
    private static byte[]readBytes(File file)throws java.io.IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();FileInputStream in=new FileInputStream(file);try{byte[]b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);}finally{in.close();}return out.toByteArray();}
    private static void writeAtomic(File target,String value){try{File tmp=new File(target.getParentFile(),target.getName()+"."+UUID.randomUUID()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!tmp.renameTo(target)){tmp.delete();throw new IllegalStateException("存储提交失败");}}catch(Exception ex){throw new IllegalStateException("存储写入失败",ex);}}
}
