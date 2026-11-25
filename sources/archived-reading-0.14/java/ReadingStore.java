package cn.xianhua.pocketkit.reading;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.AtomicFile;
import cn.xianhua.pocketkit.reading.ReadingModels.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Private content library. No network or silent source updates happen in this class. */
public final class ReadingStore {
    public static final String BUILTIN_ID = "builtin-curated";
    public static final String BUILTIN_URL = "https://9876-agqvov42zisdyuvnzmi23lrrw4.instabox.tech/content/book-sources.json";
    private static final Object LOCK = new Object();
    private static Helper helper;
    private final File chapterDir;
    private final SQLiteDatabase db;
    private static final long CACHE_BYTES = 64L * 1024 * 1024;
    private static final int CHAPTER_BYTES = 4 * 1024 * 1024;

    public ReadingStore(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) { if (helper == null) helper = new Helper(app); db = helper.getWritableDatabase(); }
        chapterDir = new File(app.getCacheDir(), "reading-chapters");
        Subscription builtin = new Subscription(); builtin.id = BUILTIN_ID; builtin.name = "拾用精选"; builtin.url = BUILTIN_URL; builtin.builtin = true;
        ContentValues v = new ContentValues(); v.put("id", builtin.id); v.put("json", builtin.toJson().toString());
        db.insertWithOnConflict("subscriptions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, "reading-library.db", null, 1); setWriteAheadLoggingEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY, origin_key TEXT NOT NULL, json TEXT NOT NULL)");
            db.execSQL("CREATE INDEX source_origin ON sources(origin_key)");
            db.execSQL("CREATE TABLE subscriptions (id TEXT PRIMARY KEY, json TEXT NOT NULL)");
            db.execSQL("CREATE TABLE history (id TEXT PRIMARY KEY, kind TEXT NOT NULL, updated_at INTEGER NOT NULL, favorite INTEGER NOT NULL, json TEXT NOT NULL)");
            db.execSQL("CREATE INDEX history_recent ON history(kind, updated_at DESC)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("不支持的阅读数据库升级"); }
    }

    private static JSONObject json(String raw) {
        try { return new JSONObject(raw); } catch (Exception e) { throw new IllegalStateException("阅读资料损坏，原数据已保留", e); }
    }
    public List<Source> sources() {
        List<Source> result = new ArrayList<>();
        try (Cursor c = db.query("sources", new String[]{"json"}, null, null, null, null, "rowid ASC")) { while (c.moveToNext()) result.add(Source.fromJson(json(c.getString(0)))); }
        return result;
    }
    public Source source(String id) {
        if (id == null || id.isBlank()) return null;
        try (Cursor c = db.query("sources", new String[]{"json"}, "id=?", new String[]{id}, null, null, null)) { return c.moveToFirst() ? Source.fromJson(json(c.getString(0))) : null; }
    }
    private Source sourceByOrigin(String key) {
        try (Cursor c = db.query("sources", new String[]{"json"}, "origin_key=?", new String[]{key}, null, null, "rowid ASC", "1")) { return c.moveToFirst() ? Source.fromJson(json(c.getString(0))) : null; }
    }
    private void putSource(Source s) {
        ContentValues v = new ContentValues(); v.put("id", s.id); v.put("origin_key", s.originKey); v.put("json", s.toJson().toString());
        if (db.update("sources", v, "id=?", new String[]{s.id}) == 0) db.insertOrThrow("sources", null, v);
    }
    public void saveSource(Source source, boolean markModified) {
        if (source == null) throw new IllegalArgumentException("书源为空");
        synchronized (LOCK) {
            Source old = source(source.id);
            try {
                Source clean = ReadingImports.normalize(source.toJson());
                if (clean.rule.toString().getBytes(StandardCharsets.UTF_8).length > ReadingImports.MAX_SOURCE_BYTES) throw new IllegalArgumentException("单个源超过 2 MiB");
                String issue = supportIssue(clean);
                if (!issue.isEmpty()) throw new IllegalArgumentException(issue);
                clean.localModified = markModified || (old != null && old.localModified) || clean.localModified;
                // Keep the original import identity even when a user edits a source URL.
                if (old != null) { clean.originKey = old.originKey; clean.upstreamHash = old.upstreamHash; clean.subscriptionId = old.subscriptionId; }
                else if (clean.upstreamHash.isEmpty()) clean.upstreamHash = ReadingImports.sourceHash(clean);
                putSource(clean);
            } catch (IllegalArgumentException e) { throw e; }
            catch (Exception e) { throw new IllegalArgumentException("无法保存书源：" + e.getMessage(), e); }
        }
    }
    public void deleteSource(String id) { synchronized (LOCK) { db.delete("sources", "id=?", new String[]{id}); } }

    public List<Subscription> subscriptions() {
        List<Subscription> result = new ArrayList<>();
        try (Cursor c = db.query("subscriptions", new String[]{"json"}, null, null, null, null, "rowid ASC")) { while (c.moveToNext()) result.add(Subscription.fromJson(json(c.getString(0)))); }
        return result;
    }
    public void saveSubscription(Subscription sub) {
        if (sub == null || sub.name.isBlank()) throw new IllegalArgumentException("请输入订阅名称");
        checkHttpUrl(sub.url);
        if (sub.id.isEmpty()) sub.id = "subscription-" + ReadingImports.digest(sub.url);
        sub.builtin = BUILTIN_ID.equals(sub.id);
        if (sub.builtin) { sub.name = "拾用精选"; sub.url = BUILTIN_URL; }
        ContentValues v = new ContentValues(); v.put("id", sub.id); v.put("json", sub.toJson().toString());
        synchronized (LOCK) { if (db.update("subscriptions", v, "id=?", new String[]{sub.id}) == 0) db.insertOrThrow("subscriptions", null, v); }
    }
    public void deleteSubscription(String id) {
        if (BUILTIN_ID.equals(id)) throw new IllegalArgumentException("内置订阅保留为获取入口，不会自动导入");
        synchronized (LOCK) { db.delete("subscriptions", "id=?", new String[]{id}); }
    }
    private static void checkHttpUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("需要不含登录凭据的 HTTP/HTTPS 地址"); }
    }
    private static String supportIssue(Source source) {
        if ("web".equals(source.kind)) { try { checkHttpUrl(source.url); return ""; } catch (Exception e) { return e.getMessage(); } }
        if (!"novel".equals(source.kind) && !"manga".equals(source.kind) && !"video".equals(source.kind)) return "暂不支持这个内容类型；网页型订阅与小说/漫画书源不同";
        try { return ReadingEngine.supportIssue(source); } catch (RuntimeException e) { return "规则格式错误：" + e.getMessage(); }
    }

    public List<ImportCandidate> preview(String text, String subscriptionId) throws Exception {
        List<ImportCandidate> result = new ArrayList<>();
        for (Source s : ReadingImports.parse(text, subscriptionId)) {
            ImportCandidate candidate = new ImportCandidate(); candidate.source = s;
            String issue = supportIssue(s);
            Source old = source(s.id); if (old == null) old = sourceByOrigin(s.originKey);
            if (old != null) { s.id = old.id; s.originKey = old.originKey; }
            if (!issue.isEmpty()) { candidate.state = "unsupported"; candidate.message = issue; }
            else if (old == null) { candidate.state = "new"; candidate.message = "新源，可选择导入"; }
            else if (ReadingImports.sourceHash(old).equals(s.upstreamHash)) { candidate.state = "unchanged"; candidate.message = "与本地一致"; }
            else if (old.localModified) { candidate.state = "conflict"; candidate.message = "本地已修改，默认保留；可明确选择替换或另存"; }
            else { candidate.state = "update"; candidate.message = "上游规则有变化，保留本地启停设置"; }
            result.add(candidate);
        }
        return result;
    }
    public int importSelected(List<ImportCandidate> selected, boolean replaceModified) throws Exception {
        int imported = 0;
        synchronized (LOCK) {
            db.beginTransaction();
            try {
                for (ImportCandidate candidate : selected) {
                    if (candidate == null || candidate.source == null || "unsupported".equals(candidate.state)) continue;
                    Source incoming = ReadingImports.normalize(candidate.source.toJson());
                    if (!supportIssue(incoming).isEmpty()) continue;
                    Source old = source(incoming.id); if (old == null) old = sourceByOrigin(incoming.originKey);
                    // Recheck inside the transaction: a source may have been edited since preview.
                    if (old != null && old.localModified && !replaceModified) continue;
                    if (old != null) { incoming.id = old.id; incoming.originKey = old.originKey; incoming.enabled = old.enabled; }
                    incoming.localModified = false;
                    incoming.upstreamHash = ReadingImports.sourceHash(incoming);
                    putSource(incoming); imported++;
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return imported;
    }

    public List<Progress> history(String kind) {
        List<Progress> result = new ArrayList<>();
        String selection = kind == null || kind.isEmpty() ? null : "kind=?";
        String[] args = selection == null ? null : new String[]{kind};
        try (Cursor c = db.query("history", new String[]{"json"}, selection, args, null, null, "favorite DESC, updated_at DESC", "300")) { while (c.moveToNext()) result.add(Progress.fromJson(json(c.getString(0)))); }
        return result;
    }
    public void saveProgress(Progress progress) {
        if (progress == null || progress.detail == null || progress.detail.item == null) return;
        if (progress.sourceId.isEmpty()) progress.sourceId = progress.detail.item.sourceId;
        if (progress.id.isEmpty()) progress.id = ReadingImports.digest(progress.sourceId + "\n" + progress.detail.item.url);
        progress.updatedAt = System.currentTimeMillis();
        progress.chapterIndex = Math.max(0, Math.min(progress.chapterIndex, Math.max(0, progress.detail.chapters.size() - 1)));
        progress.scrollY = Math.max(0, progress.scrollY); progress.positionMs = Math.max(0, progress.positionMs);
        String raw = progress.toJson().toString();
        if (raw.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IllegalArgumentException("目录过大，无法保存进度");
        ContentValues v = new ContentValues(); v.put("id", progress.id); v.put("kind", progress.kind); v.put("updated_at", progress.updatedAt); v.put("favorite", progress.favorite ? 1 : 0); v.put("json", raw);
        synchronized (LOCK) {
            db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM history WHERE favorite=0 AND id NOT IN (SELECT id FROM history ORDER BY updated_at DESC LIMIT 300)");
        }
    }
    public void deleteProgress(String id) { synchronized (LOCK) { db.delete("history", "id=?", new String[]{id}); } }

    private File chapterFile(String sourceId, String url) { return new File(chapterDir, ReadingImports.digest(sourceId + "\n" + url) + ".txt"); }
    public String cachedChapter(String sourceId, String chapterUrl) {
        synchronized (LOCK) {
            File file = chapterFile(sourceId, chapterUrl);
            if (!file.isFile() || file.length() > CHAPTER_BYTES) return null;
            try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); } catch (Exception unavailable) { return null; }
        }
    }
    public void cacheChapter(String sourceId, String chapterUrl, String text) throws Exception {
        if (text == null || text.isBlank()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CHAPTER_BYTES) throw new IllegalArgumentException("章节超过缓存上限");
        synchronized (LOCK) {
            if (!chapterDir.isDirectory() && !chapterDir.mkdirs()) throw new java.io.IOException("无法建立章节缓存");
            AtomicFile file = new AtomicFile(chapterFile(sourceId, chapterUrl)); FileOutputStream out = null;
            try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
            catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
            File[] cached = chapterDir.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.txt"));
            if (cached == null) return;
            long total = 0; for (File c : cached) total += c.length();
            Arrays.sort(cached, Comparator.comparingLong(File::lastModified));
            for (File c : cached) { if (total <= CACHE_BYTES) break; long length=c.length(); if (c.delete()) total-=length; }
        }
    }
}
