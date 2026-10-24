package cn.xianhua.pocketkit.automation;

import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict v1 codec. Drafts preserve incomplete operands but never pass runtime validation. */
public final class ScriptCodec {
    public static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    private static final int SCHEMA = 1;
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
    private static final String[] ROOT_KEYS={"schemaVersion","draft","id","name","targetPackage","width","height","updatedAt","startDelayMs","timeoutMs","repeatCount","repeatIntervalMs","steps","templates"};
    private static final String[] STEP_KEYS={"id","label","text","templateId","variable","value","operator","textSource","matchMode","type","x","y","x2","y2","left","top","right","bottom","durationMs","timeoutMs","intervalMs","atEpochMs","count","threshold","useLastMatch","clickMatch","pointPending","gesturePath","children","otherwise"};
    private ScriptCodec() { }
    public static String encode(Script script) { script.validate(); return encodeInternal(script,false); }
    public static String encodeDraft(Script script) { validateDraft(script); return encodeInternal(script,true); }
    public static Script decode(String text) { return parse(text,false); }
    public static Script decodeDraft(String text) { return parse(text,true); }
    private static String encodeInternal(Script script,boolean draft) {
        try { JSONObject root=encodeScript(script); if(draft)root.put("draft",true); String out=root.toString(); int size=out.getBytes(StandardCharsets.UTF_8).length; if(size>MAX_SCRIPT_BYTES)throw new IllegalArgumentException("脚本超过2MB"); return out; }
        catch(JSONException ex){throw new IllegalArgumentException("脚本编码失败",ex);}
    }
    private static Script parse(String text,boolean draft) {
        if(text==null||text.getBytes(StandardCharsets.UTF_8).length>MAX_SCRIPT_BYTES)throw new IllegalArgumentException("脚本数据过大");
        try { JSONObject root=new JSONObject(text); checkKeys(root,ROOT_KEYS); if(root.has("draft")&&!root.get("draft").getClass().equals(Boolean.class))throw new IllegalArgumentException("draft字段类型无效"); if(intField(root,"schemaVersion",-1)!=SCHEMA)throw new IllegalArgumentException("不支持的脚本版本");
            Script s=new Script(); s.id=requiredString(root,"id"); s.name=stringField(root,"name","未命名脚本"); s.targetPackage=stringField(root,"targetPackage",""); s.width=intField(root,"width",0); s.height=intField(root,"height",0); s.updatedAt=longField(root,"updatedAt",0); s.startDelayMs=longField(root,"startDelayMs",0); s.timeoutMs=longField(root,"timeoutMs",0); s.repeatCount=intField(root,"repeatCount",1); s.repeatIntervalMs=longField(root,"repeatIntervalMs",500);
            if(!root.has("steps")||!(root.get("steps") instanceof JSONArray))throw new IllegalArgumentException("步骤列表无效"); JSONArray list=(JSONArray)root.get("steps"); if(list.length()>10000)throw new IllegalArgumentException("步骤列表过多"); for(int i=0;i<list.length();i++){if(!(list.get(i) instanceof JSONObject))throw new IllegalArgumentException("步骤对象无效");s.steps.add(decodeStep((JSONObject)list.get(i),0));}
            if(draft)validateDraft(s);else s.validate(); return s;
        } catch(JSONException|ClassCastException ex){throw new IllegalArgumentException("脚本JSON无效",ex);}
    }
    private static JSONObject encodeScript(Script s)throws JSONException { JSONObject o=new JSONObject(); o.put("schemaVersion",SCHEMA).put("id",s.id).put("name",s.name).put("targetPackage",s.targetPackage).put("width",s.width).put("height",s.height).put("updatedAt",s.updatedAt).put("startDelayMs",s.startDelayMs).put("timeoutMs",s.timeoutMs); if(s.repeatCount!=1)o.put("repeatCount",s.repeatCount); if(s.repeatIntervalMs!=500)o.put("repeatIntervalMs",s.repeatIntervalMs); JSONArray a=new JSONArray(); for(Step step:s.steps)a.put(encodeStep(step)); return o.put("steps",a); }
    private static JSONObject encodeStep(Step s)throws JSONException { JSONObject o=new JSONObject(); o.put("id",s.id).put("label",s.label).put("text",s.text).put("templateId",s.templateId).put("variable",s.variable).put("value",s.value).put("operator",s.operator).put("textSource",s.textSource).put("matchMode",s.matchMode).put("type",s.type.name()).put("x",s.x).put("y",s.y).put("x2",s.x2).put("y2",s.y2).put("left",s.left).put("top",s.top).put("right",s.right).put("bottom",s.bottom).put("durationMs",s.durationMs).put("timeoutMs",s.timeoutMs).put("intervalMs",s.intervalMs).put("atEpochMs",s.atEpochMs).put("count",s.count).put("threshold",s.threshold).put("useLastMatch",s.useLastMatch); if(s.clickMatch)o.put("clickMatch",true); if(s.pointPending)o.put("pointPending",true); if(s.gesturePath.length>0){JSONArray path=new JSONArray();for(int value:s.gesturePath)path.put(value);o.put("gesturePath",path);} JSONArray c=new JSONArray();for(Step x:s.children)c.put(encodeStep(x));JSONArray o2=new JSONArray();for(Step x:s.otherwise)o2.put(encodeStep(x));return o.put("children",c).put("otherwise",o2); }
    private static Step decodeStep(JSONObject o,int depth)throws JSONException { if(depth>32)throw new IllegalArgumentException("步骤嵌套超过32层");checkKeys(o,STEP_KEYS);String type=requiredString(o,"type");Step s;try{s=new Step(Step.Type.valueOf(type));}catch(IllegalArgumentException ex){throw new IllegalArgumentException("未知动作: "+type,ex);}s.id=requiredString(o,"id");s.label=stringField(o,"label","");s.text=stringField(o,"text","");s.templateId=stringField(o,"templateId","");s.variable=stringField(o,"variable","");s.value=stringField(o,"value","");s.operator=stringField(o,"operator","");s.textSource=stringField(o,"textSource","ocr");s.matchMode=stringField(o,"matchMode","contains");s.x=intField(o,"x",0);s.y=intField(o,"y",0);s.x2=intField(o,"x2",0);s.y2=intField(o,"y2",0);s.left=intField(o,"left",0);s.top=intField(o,"top",0);s.right=intField(o,"right",0);s.bottom=intField(o,"bottom",0);s.durationMs=longField(o,"durationMs",0);s.timeoutMs=longField(o,"timeoutMs",0);s.intervalMs=longField(o,"intervalMs",0);s.atEpochMs=longField(o,"atEpochMs",0);s.count=intField(o,"count",0);s.threshold=doubleField(o,"threshold",0.85);s.useLastMatch=boolField(o,"useLastMatch",false);s.clickMatch=boolField(o,"clickMatch",false);s.pointPending=boolField(o,"pointPending",false);s.gesturePath=pathField(o);if(o.has("children")&&!(o.get("children") instanceof JSONArray))throw new IllegalArgumentException("子步骤字段类型无效");if(o.has("otherwise")&&!(o.get("otherwise") instanceof JSONArray))throw new IllegalArgumentException("否则步骤字段类型无效");JSONArray c=o.optJSONArray("children");if(c!=null){if(c.length()>10000)throw new IllegalArgumentException("子步骤过多");for(int i=0;i<c.length();i++){if(!(c.get(i) instanceof JSONObject))throw new IllegalArgumentException("子步骤对象无效");s.children.add(decodeStep((JSONObject)c.get(i),depth+1));}}JSONArray o2=o.optJSONArray("otherwise");if(o2!=null){if(o2.length()>10000)throw new IllegalArgumentException("否则步骤过多");for(int i=0;i<o2.length();i++){if(!(o2.get(i) instanceof JSONObject))throw new IllegalArgumentException("否则步骤对象无效");s.otherwise.add(decodeStep((JSONObject)o2.get(i),depth+1));}}return s; }
    private static void validateDraft(Script s){if(s.id==null||s.id.length()==0||s.id.length()>128||s.name==null||s.name.length()>200||s.targetPackage==null||s.targetPackage.length()>256)throw new IllegalArgumentException("草稿字段无效");if(s.width<0||s.height<0||(s.width==0)!=(s.height==0)||s.startDelayMs<0||s.timeoutMs<0||s.startDelayMs>7*24*3600_000L||s.timeoutMs>7*24*3600_000L||s.repeatCount<0||s.repeatIntervalMs<0||s.repeatIntervalMs>7*24*3600_000L)throw new IllegalArgumentException("草稿脚本参数无效");if(s.steps==null||s.steps.size()>10000)throw new IllegalArgumentException("草稿步骤过多");Set<String>ids=new HashSet<>();for(Step step:s.steps)validateDraftStep(step,0,ids,s.width,s.height);}
    private static void validateDraftStep(Step s,int depth,Set<String>ids,int width,int height){if(s==null||depth>32||ids.size()>=10000||s.type==null||s.id==null||s.id.length()==0||s.id.length()>128||!ids.add(s.id))throw new IllegalArgumentException("草稿步骤结构无效");if(s.label==null||s.label.length()>65536||s.text==null||s.text.length()>65536||s.templateId==null||s.templateId.length()>128||(!s.templateId.isEmpty()&&!s.templateId.matches("[A-Za-z0-9_-]{1,128}"))||s.variable==null||s.variable.length()>256||s.value==null||s.value.length()>65536||s.operator==null||s.operator.length()>64||s.textSource==null||s.matchMode==null)throw new IllegalArgumentException("草稿操作数字段无效");if(s.x<0||s.y<0||s.x2<0||s.y2<0||s.left<0||s.top<0||s.right<0||s.bottom<0||(width>0&&(s.x>=width||s.x2>=width||s.right>width))||(height>0&&(s.y>=height||s.y2>=height||s.bottom>height))||s.durationMs<0||s.timeoutMs<0||s.intervalMs<0||s.atEpochMs<0||s.durationMs>7*24*3600_000L||s.timeoutMs>7*24*3600_000L||s.intervalMs>7*24*3600_000L||s.count<0||Double.isNaN(s.threshold)||Double.isInfinite(s.threshold)||s.threshold<0||s.threshold>1)throw new IllegalArgumentException("草稿数字字段无效");if(!"ocr".equals(s.textSource)&&!"nodes".equals(s.textSource)||!"contains".equals(s.matchMode)&&!"exact".equals(s.matchMode)&&!"regex".equals(s.matchMode)||!s.operator.isEmpty()&&!isOperator(s.operator))throw new IllegalArgumentException("草稿模式字段无效");s.validatePath(width,height);if(s.children==null||s.otherwise==null)throw new IllegalArgumentException("草稿子步骤无效");for(Step x:s.children)validateDraftStep(x,depth+1,ids,width,height);for(Step x:s.otherwise)validateDraftStep(x,depth+1,ids,width,height);}
    private static int[] pathField(JSONObject object) throws JSONException {
        if (!object.has("gesturePath")) return new int[0];
        Object value = object.get("gesturePath");
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("滑动轨迹字段无效");
        JSONArray array = (JSONArray) value;
        if (array.length() > Step.MAX_GESTURE_PATH_VALUES || array.length() % 2 != 0)
            throw new IllegalArgumentException("滑动轨迹长度无效");
        int[] result = new int[array.length()];
        for (int i = 0; i < result.length; i++) {
            Object coordinate = array.get(i);
            if (!(coordinate instanceof Integer) || (Integer) coordinate < 0)
                throw new IllegalArgumentException("滑动轨迹坐标必须是非负整数");
            result[i] = (Integer) coordinate;
        }
        return result;
    }
    private static boolean isOperator(String op){return "equals".equals(op)||"notEquals".equals(op)||"contains".equals(op)||"regex".equals(op)||"greater".equals(op)||"greaterOrEqual".equals(op)||"less".equals(op)||"lessOrEqual".equals(op)||"==".equals(op)||"!=".equals(op)||">".equals(op)||">=".equals(op)||"<".equals(op)||"<=".equals(op);}
    private static void checkKeys(JSONObject o,String[]allowed)throws JSONException{for(Iterator<String>it=o.keys();it.hasNext();){String key=it.next();boolean ok=false;for(String a:allowed)if(a.equals(key)){ok=true;break;}if(!ok)throw new IllegalArgumentException("未知字段: "+key);}}
    private static String requiredString(JSONObject o,String key)throws JSONException{if(!o.has(key)||o.isNull(key))throw new IllegalArgumentException("缺少字段: "+key);Object v=o.get(key);if(!(v instanceof String))throw new IllegalArgumentException("字段类型无效: "+key);return(String)v;}
    private static String stringField(JSONObject o,String key,String fallback)throws JSONException{if(!o.has(key)||o.isNull(key))return fallback;Object v=o.get(key);if(!(v instanceof String))throw new IllegalArgumentException("字段类型无效: "+key);return(String)v;}
    private static boolean boolField(JSONObject o,String key,boolean fallback)throws JSONException{if(!o.has(key)||o.isNull(key))return fallback;Object v=o.get(key);if(!(v instanceof Boolean))throw new IllegalArgumentException("字段类型无效: "+key);return(Boolean)v;}
    private static int intField(JSONObject o,String key,int fallback)throws JSONException{if(!o.has(key)||o.isNull(key))return fallback;Object v=o.get(key);if(!(v instanceof Number)||v instanceof Float||v instanceof Double||((Number)v).longValue()!=((Number)v).doubleValue())throw new IllegalArgumentException("整数类型无效: "+key);long n=((Number)v).longValue();if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw new IllegalArgumentException("整数溢出: "+key);return(int)n;}
    private static long longField(JSONObject o,String key,long fallback)throws JSONException{if(!o.has(key)||o.isNull(key))return fallback;Object v=o.get(key);if(!(v instanceof Number)||v instanceof Float||v instanceof Double||((Number)v).doubleValue()!=((Number)v).longValue())throw new IllegalArgumentException("长整数类型无效: "+key);return((Number)v).longValue();}
    private static double doubleField(JSONObject o,String key,double fallback)throws JSONException{if(!o.has(key)||o.isNull(key))return fallback;Object v=o.get(key);if(!(v instanceof Number))throw new IllegalArgumentException("数字类型无效: "+key);double n=((Number)v).doubleValue();if(Double.isNaN(n)||Double.isInfinite(n))throw new IllegalArgumentException("数字无效: "+key);return n;}
}
