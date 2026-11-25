package cn.xianhua.pocketkit.reading;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data crossing the source engine, local library and reading screens. No executable UI content. */
public final class ReadingModels {
    private ReadingModels() { }
    static String text(JSONObject j, String key) { return j == null || j.isNull(key) ? "" : j.optString(key, ""); }
    static JSONObject object(Object... pairs) {
        JSONObject j = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) j.put((String) pairs[i], pairs[i + 1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return j;
    }
    public static final class Source {
        public String id = "", name = "", kind = "novel", url = "", subscriptionId = "", upstreamHash = "", originKey = "";
        public JSONObject rule = new JSONObject();
        public boolean enabled = true, localModified = false;
        public Source() { }
        public JSONObject toJson() { return object("id", id, "name", name, "kind", kind, "url", url, "subscriptionId", subscriptionId,
                "upstreamHash", upstreamHash, "originKey", originKey, "rule", rule, "enabled", enabled, "localModified", localModified); }
        public static Source fromJson(JSONObject j) {
            Source s = new Source(); if (j == null) return s;
            s.id = text(j, "id"); s.name = text(j, "name"); s.kind = j.optString("kind", "novel"); s.url = text(j, "url");
            s.subscriptionId = text(j, "subscriptionId"); s.upstreamHash = text(j, "upstreamHash"); s.originKey = text(j, "originKey");
            s.rule = j.optJSONObject("rule") == null ? new JSONObject() : j.optJSONObject("rule");
            s.enabled = j.optBoolean("enabled", true); s.localModified = j.optBoolean("localModified", false); return s;
        }
    }
    public static final class Item {
        public String sourceId = "", kind = "novel", title = "", author = "", url = "", cover = "", description = "";
        public Item() { }
        public JSONObject toJson() { return object("sourceId", sourceId, "kind", kind, "title", title, "author", author, "url", url, "cover", cover, "description", description); }
        public static Item fromJson(JSONObject j) {
            Item i = new Item(); if (j == null) return i;
            i.sourceId = text(j,"sourceId"); i.kind = j.optString("kind","novel"); i.title=text(j,"title"); i.author=text(j,"author");
            i.url=text(j,"url"); i.cover=text(j,"cover"); i.description=text(j,"description"); return i;
        }
    }
    public static final class Chapter {
        public String name = "", url = "", group = "";
        public Chapter() { }
        public JSONObject toJson() { return object("name", name, "url", url, "group", group); }
        public static Chapter fromJson(JSONObject j) {
            Chapter c = new Chapter(); if (j != null) { c.name=text(j,"name"); c.url=text(j,"url"); c.group=text(j,"group"); } return c;
        }
    }
    public static final class Detail {
        public Item item = new Item();
        public List<Chapter> chapters = new ArrayList<>();
        public Detail() { }
        public JSONObject toJson() { JSONArray a=new JSONArray(); for(Chapter c:chapters) a.put(c.toJson()); return object("item",item.toJson(),"chapters",a); }
        public static Detail fromJson(JSONObject j) {
            Detail d=new Detail(); if(j==null)return d; d.item=Item.fromJson(j.optJSONObject("item")); JSONArray a=j.optJSONArray("chapters");
            if(a!=null)for(int i=0;i<a.length();i++){ JSONObject c=a.optJSONObject(i); if(c!=null)d.chapters.add(Chapter.fromJson(c)); } return d;
        }
    }
    public static final class Playback {
        public String url = "", mimeType = "";
        public Map<String,String> headers = new LinkedHashMap<>();
        public Playback() { }
        public JSONObject toJson() { return object("url",url,"mimeType",mimeType,"headers",new JSONObject(headers)); }
        public static Playback fromJson(JSONObject j) {
            Playback p=new Playback(); if(j==null)return p; p.url=text(j,"url"); p.mimeType=text(j,"mimeType"); JSONObject h=j.optJSONObject("headers");
            if(h!=null){ java.util.Iterator<String> it=h.keys(); while(it.hasNext()){String k=it.next();p.headers.put(k,text(h,k));} } return p;
        }
    }
    public static final class Subscription {
        public String id="", name="", url="", lastMessage="", updatedAt="";
        public boolean builtin;
        public Subscription() { }
        public JSONObject toJson() { return object("id",id,"name",name,"url",url,"lastMessage",lastMessage,"updatedAt",updatedAt,"builtin",builtin); }
        public static Subscription fromJson(JSONObject j) {
            Subscription s=new Subscription(); if(j==null)return s; s.id=text(j,"id");s.name=text(j,"name");s.url=text(j,"url");
            s.lastMessage=text(j,"lastMessage");s.updatedAt=text(j,"updatedAt");s.builtin=j.optBoolean("builtin");return s;
        }
    }
    public static final class ImportCandidate {
        public Source source = new Source();
        public String state="new", message="";
        public ImportCandidate() { }
        public JSONObject toJson(){ return object("source",source.toJson(),"state",state,"message",message); }
        public static ImportCandidate fromJson(JSONObject j){ ImportCandidate c=new ImportCandidate();if(j!=null){c.source=Source.fromJson(j.optJSONObject("source"));c.state=j.optString("state","new");c.message=text(j,"message");}return c; }
    }
    public static final class Progress {
        public String id="", sourceId="", kind="novel";
        public Detail detail=new Detail();
        public int chapterIndex, scrollY;
        public long positionMs, updatedAt;
        public boolean favorite;
        public Progress() { }
        public JSONObject toJson(){ return object("id",id,"sourceId",sourceId,"kind",kind,"detail",detail.toJson(),"chapterIndex",chapterIndex,
                "scrollY",scrollY,"positionMs",positionMs,"updatedAt",updatedAt,"favorite",favorite); }
        public static Progress fromJson(JSONObject j){
            Progress p=new Progress();if(j==null)return p;p.id=text(j,"id");p.sourceId=text(j,"sourceId");p.kind=j.optString("kind","novel");
            p.detail=Detail.fromJson(j.optJSONObject("detail"));p.chapterIndex=j.optInt("chapterIndex");p.scrollY=j.optInt("scrollY");
            p.positionMs=j.optLong("positionMs");p.updatedAt=j.optLong("updatedAt");p.favorite=j.optBoolean("favorite");return p;
        }
    }
}
