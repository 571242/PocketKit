package cn.xianhua.pocketkit.browser;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Main-process coordinator owns disk snapshots; a live WebView holds the cross-process lease. */
public final class BrowserVault {
    public static final String SUFFIX = "incognito";
    private static final String ALIAS = "pocketkit_browser_vault_v1";
    private final Context context;
    private final File root, library;
    public final File profile;

    public BrowserVault(Context context) throws IOException {
        this.context = context.getApplicationContext();
        root = new File(context.getNoBackupFilesDir(), "private-browser");
        library = new File(root, "frozen");
        if (!library.isDirectory() && !library.mkdirs()) throw new IOException("无法创建冻结库");
        profile = new File(context.getApplicationInfo().dataDir, "app_webview_" + SUFFIX);
    }

    public static final class Lease implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;
        Lease(RandomAccessFile file, FileLock lock) { this.file = file; this.lock = lock; }
        @Override public void close() throws IOException { try { lock.release(); } finally { file.close(); } }
    }
    public Lease tryLease() throws IOException {
        RandomAccessFile file = new RandomAccessFile(new File(root, "session.lock"), "rw");
        try {
            FileLock lock;
            try { lock = file.getChannel().tryLock(); } catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) { file.close(); return null; }
            return new Lease(file, lock);
        } catch (IOException ex) { file.close(); throw ex; }
    }

    private synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key current = store.getKey(ALIAS, null);
        if (current instanceof SecretKey) return (SecretKey)current;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // SessionArchive generates fresh random archive IVs and derives distinct record IVs.
                .setRandomizedEncryptionRequired(false).setKeySize(256).build());
        return generator.generateKey();
    }

    private void writeInfo(File file, JSONObject info) throws Exception {
        byte[] bytes = info.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) throw new IOException("会话附加数据超过 1 MiB，未冻结；网页仍保留");
        byte[] sealed = SessionArchive.seal(bytes, key());
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try { stream = atomic.startWrite(); stream.write(sealed); atomic.finishWrite(stream); }
        catch (Exception ex) { if (stream != null) atomic.failWrite(stream); throw ex; }
    }
    private JSONObject readInfo(File file) throws Exception {
        if (file.length() > 1024 * 1024 + 128) throw new IOException("会话元数据损坏");
        byte[] data = new AtomicFile(file).readFully();
        return new JSONObject(new String(SessionArchive.open(data, key()), StandardCharsets.UTF_8));
    }
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private File slot(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IOException("窗口标识无效");
        return new File(library, id);
    }

    /** Called only under the lease, before starting the dedicated process. */
    public JSONObject prepare(String frozenId) throws Exception {
        if (hasPending()) throw new IOException("请先完成待冻结窗口，或明确清除暂存会话");
        eraseActive();
        JSONObject info;
        if (frozenId != null) {
            File source = slot(frozenId);
            info = readInfo(new File(source, "info.enc"));
            BrowserUrls.network(info.getString("url"));
            File restoring = new File(root, "restore");
            if (!restoring.mkdir()) throw new IOException("无法创建解冻暂存目录");
            try {
                File ready = new File(restoring, "profile");
                SessionArchive.unpack(new File(source, "profile.enc").toPath(), ready.toPath(), key());
                Files.move(ready.toPath(), profile.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } finally { SessionArchive.deleteTree(restoring.toPath()); }
            info.put("sourceId", frozenId);
        } else info = new JSONObject().put("url", "").put("title", "新无痕窗口");
        info.put("token", id());
        writeInfo(new File(root, "active.enc"), info);
        return info;
    }

    /** Browser must acquire its lease before reading this handoff. */
    public JSONObject active(String token) throws Exception {
        JSONObject info = readInfo(new File(root, "active.enc"));
        if (token == null || !token.equals(info.getString("token"))) throw new IOException("会话已失效，请返回重新打开");
        return info;
    }

    public String currentToken() throws Exception { return readInfo(new File(root, "active.enc")).getString("token"); }

    public void requestFreeze(JSONObject info) throws Exception {
        BrowserUrls.network(info.getString("url"));
        info.put("frozenId", id()).put("savedAt", System.currentTimeMillis());
        writeInfo(new File(root, "pending.enc"), info);
    }
    public boolean hasPending() { return new File(root, "pending.enc").exists() || new File(root, "pending.enc.bak").exists(); }

    /** Called after process death, under the lease. A failed save retains both pending and profile. */
    public String settle() throws Exception {
        String result = "";
        if (hasPending()) {
            JSONObject info = readInfo(new File(root, "pending.enc"));
            File destination = slot(info.getString("frozenId"));
            if (!destination.isDirectory()) {
                if (!cookieStoreQuiet(profile)) result = "Cookie 尚未完全落盘，可能有最近登录状态未写入冻结档案。";
                File stage = new File(library, info.getString("frozenId") + ".tmp");
                SessionArchive.deleteTree(stage.toPath());
                if (!stage.mkdir()) throw new IOException("无法创建冻结暂存目录");
                try {
                    if (!profile.isDirectory()) throw new IOException("待冻结的网页目录不存在，原冻结副本未改动");
                    SessionArchive.pack(profile.toPath(), new File(stage, "profile.enc").toPath(), key());
                    writeInfo(new File(stage, "info.enc"), info);
                    Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception ex) { SessionArchive.deleteTree(stage.toPath()); throw ex; }
            } else {
                JSONObject existing = readInfo(new File(destination, "info.enc"));
                if (!info.getString("frozenId").equals(existing.getString("frozenId"))) throw new IOException("冻结记录校验失败");
            }
            // Commit happens before removing the cleartext work profile or the previous snapshot.
            eraseActive();
            new AtomicFile(new File(root, "pending.enc")).delete();
            String oldId = info.optString("sourceId");
            if (!oldId.isEmpty() && !oldId.equals(info.getString("frozenId"))) {
                try { SessionArchive.deleteTree(slot(oldId).toPath()); }
                catch (IOException ex) { result = "旧副本未能删除，可在冻结库中手动删除。"; }
            }
            return "窗口已加密冻结，可长期保留。" + result;
        }
        eraseActive();
        return result;
    }

    /** Same bounded check as the browser side; does not claim a hard guarantee. */
    private static boolean cookieStoreQuiet(File profile) throws InterruptedException {
        File cookies = new File(profile, "Default/Cookies");
        long last = -1, stable = 0, deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            long stamp = cookies.lastModified();
            if (stamp > 0 && stamp == last && !new File(profile, "Default/Cookies-journal").exists()) {
                if (stable == 0) stable = System.currentTimeMillis();
                if (System.currentTimeMillis() - stable >= 200) return true;
            } else { last = stamp; stable = 0; }
            Thread.sleep(100);
        }
        return false;
    }

    public void discardPending() throws IOException {
        eraseActive();
        new AtomicFile(new File(root, "pending.enc")).delete();
    }
    private void eraseActive() throws IOException {
        SessionArchive.deleteTree(new File(root, "restore").toPath());
        SessionArchive.deleteTree(profile.toPath());
        // Chromium uses processDataDirName = "webview_" + suffix for both profile and cache subdir.
        SessionArchive.deleteTree(new File(context.getCacheDir(), "webview_" + SUFFIX).toPath());
        new AtomicFile(new File(root, "active.enc")).delete();
    }

    public List<JSONObject> list() throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        File[] files = library.listFiles();
        if (files == null) throw new IOException("无法读取冻结库");
        for (File entry : files) {
            if (!entry.getName().matches("[a-f0-9]{32}")) continue;
            try { JSONObject info = readInfo(new File(entry, "info.enc")); info.put("frozenId", entry.getName()); rows.add(info); }
            catch (Exception ex) { rows.add(new JSONObject().put("frozenId", entry.getName()).put("title", "无法解密的窗口（可删除）").put("broken", true)); }
        }
        rows.sort((a, b) -> Long.compare(b.optLong("savedAt"), a.optLong("savedAt")));
        return rows;
    }
    public void delete(String id) throws IOException { SessionArchive.deleteTree(slot(id).toPath()); }
}
