package cn.xianhua.pocketkit;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;

/** User-selected catalog only; unimplemented module entries stay visibly disabled. */
public final class ToolRegistry {
    public static final class Entry {
        public final String id, name, category, module;
        public final List<String> enhancements = new ArrayList<>();
        public boolean ready;
        public String limitation = "";
        Entry(JSONObject json) throws JSONException {
            id=json.getString("id"); name=ToolCatalog.name(id,json.getString("name")); category=ToolCatalog.category(id,json.getString("category")); module="048".equals(id)?"AiVoiceToolsActivity":json.getString("module");
            JSONArray extras=json.optJSONArray("enhancements");
            if(extras!=null) for(int i=0;i<extras.length();i++) enhancements.add(extras.getString(i));
        }
        public Class<?> activityClass() throws ClassNotFoundException { return Class.forName("cn.xianhua.pocketkit."+module); }
        void refresh() {
            try {
                Class<?> type=activityClass();
                Method method=type.getMethod("supports",String.class);
                ready=Boolean.TRUE.equals(method.invoke(null,id));
                Object text=type.getMethod("limitation",String.class).invoke(null,id);
                limitation=text==null?"":text.toString();
                if(!ready && limitation.trim().isEmpty()) limitation="该功能尚未接通，暂不可用。";
            } catch(ClassNotFoundException ex) {
                ready=false; limitation="模块正在开发中，暂不可用。";
            } catch(LinkageError ex) {
                ready=false; limitation="模块加载失败："+ex.getClass().getSimpleName()+"；其他工具仍可使用。";
                android.util.Log.e("PocketKit", "Loading module "+module, ex);
            } catch(ReflectiveOperationException ex) {
                ready=false; limitation="模块接口异常："+ex.getClass().getSimpleName();
            }
        }
    }
    private ToolRegistry() {}
    public static List<Entry> load(Context context) throws IOException, JSONException {
        StringBuilder text=new StringBuilder();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("selected-tools.json"),StandardCharsets.UTF_8))) {
            String line; while((line=reader.readLine())!=null) text.append(line).append('\n');
        }
        JSONArray array=new JSONArray(text.toString()); List<Entry> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<array.length();i++) {
            Entry e=new Entry(array.getJSONObject(i));
            if(!ids.add(e.id)) throw new IOException("目录编号重复："+e.id);
            e.refresh(); result.add(e);
        }
        return result;
    }
}
