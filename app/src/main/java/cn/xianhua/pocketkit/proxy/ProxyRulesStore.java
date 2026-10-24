package cn.xianhua.pocketkit.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Persistence and download of the cloud routing-rule bundle.
 *
 * <p>Rules move separately from nodes: a failed rule sync must never touch the node pool or the
 * current connection, and a bundle only becomes active after its bytes are hashed, parsed and
 * verified. The last known good file stays in place until then. Android glue only — all parsing,
 * validation and hashing lives in {@link ProxyRules} so it stays testable off-device.
 */
public final class ProxyRulesStore {
    private static final String PREF = "pocketkit_proxy_rules";
    private static final String KEY_SHA = "rules_sha256";
    private static final String KEY_UPDATED_AT = "rules_updated_at";
    private static final String KEY_ERROR = "rules_last_error";
    private static final String KEY_ENTRIES = "rules_entry_count";
    private static final String KEY_UPSTREAM = "rules_upstream_summary";

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    static final String RULES_PATH_PREFIX = "/rules/";
    private static final String DEFAULT_ASSET = "proxy-rules-default.json";
    private static volatile ProxyRules BUILT_IN;

    /** Verified candidate: bytes plus the rules parsed from exactly those bytes. */
    public static final class Pending {
        public final String sha256;
        public final byte[] bytes;
        public final ProxyRules rules;

        Pending(String sha256, byte[] bytes, ProxyRules rules) {
            this.sha256 = sha256;
            this.bytes = bytes;
            this.rules = rules;
        }
    }

    private final Context context;

    public ProxyRulesStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public File file() {
        return artifact(getActiveSha256());
    }

    private File artifact(String sha) {
        return new File(new File(context.getFilesDir(), "rules"),
                ProxyRules.isSha256Hex(sha) ? sha + ".json" : "rules.json");
    }

    public String getActiveSha256() {
        return prefs().getString(KEY_SHA, "");
    }

    public long getUpdatedAt() {
        return prefs().getLong(KEY_UPDATED_AT, 0);
    }

    public String getLastError() {
        return prefs().getString(KEY_ERROR, "");
    }

    public int getEntryCount() {
        return prefs().getInt(KEY_ENTRIES, 0);
    }

    /**
     * Active rules, or the packaged defaults. A local file that no longer matches its recorded
     * hash reports as absent, so a stale version string cannot keep broken rules in force.
     */
    public ProxyRules load() {
        String sha = getActiveSha256();
        ProxyRules verified = ProxyRules.verifyFile(artifact(sha), sha);
        return verified != null ? verified : builtIn();
    }

    /** Packaged default rules; parsed once per process, with a fallback if the asset is missing. */
    private ProxyRules builtIn() {
        ProxyRules cached = BUILT_IN;
        if (cached != null) return cached;
        synchronized (ProxyRulesStore.class) {
            if (BUILT_IN == null) BUILT_IN = readBuiltIn();
            return BUILT_IN;
        }
    }

    private ProxyRules readBuiltIn() {
        try (InputStream in = context.getAssets().open(DEFAULT_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return ProxyRules.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception error) {
            // A stripped asset must not leave the phone without any split at all.
            return ProxyRules.fallback();
        }
    }

    /**
     * Checks a bundle URL against the update hub it came from. Same HTTPS origin only: the hub
     * names its own rule file, and a redirect to any other host is refused.
     */
    static String sameOriginRulesUrl(String hubUrl, String rulesUrl) throws IOException {
        URL hub = parseUrl(hubUrl, "更新接口地址");
        URL rules = parseUrl(rulesUrl, "规则地址");
        if (!"https".equals(hub.getProtocol()) || !"https".equals(rules.getProtocol())) {
            throw new IOException("规则地址必须使用 HTTPS");
        }
        if (hub.getUserInfo() != null || rules.getUserInfo() != null) {
            throw new IOException("地址不能包含账户信息");
        }
        if (!sameOrigin(hub, rules)) throw new IOException("规则地址必须与更新源同源");
        if (!rules.getPath().matches("/rules/[0-9a-f]{64}\\.json")
                || rules.getQuery() != null || rules.getRef() != null) {
            throw new IOException("规则地址路径不合法");
        }
        return rules.toString();
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    private static URL parseUrl(String raw, String label) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException(label + "为空");
        try {
            return new URL(raw.trim());
        } catch (Exception error) {
            throw new IOException(label + "不是合法 URL");
        }
    }

    /**
     * Fetches, hashes and parses a candidate bundle without touching the active rules.
     *
     * @throws IOException when the payload is missing, oversized, mis-hashed or malformed
     */
    public Pending download(String hubUrl, String rulesUrl, String expectedSha, long declaredBytes,
                            int schemaVersion) throws IOException {
        if (!ProxyRules.isSha256Hex(expectedSha)) throw new IOException("规则版本号格式不正确");
        if (schemaVersion != ProxyRules.SCHEMA_VERSION) throw new IOException("不支持的规则版本");
        if (declaredBytes <= 0 || declaredBytes > ProxyRules.MAX_BYTES) throw new IOException("规则体积声明不合法");
        String start = sameOriginRulesUrl(hubUrl, rulesUrl);
        if (!new URL(start).getPath().equals(RULES_PATH_PREFIX + expectedSha + ".json")) {
            throw new IOException("规则文件名与版本不一致");
        }
        URL hub = parseUrl(hubUrl, "更新接口地址");

        byte[] data = fetch(start, hub);
        if (declaredBytes != data.length) {
            throw new IOException("规则体积与声明不一致");
        }
        String actual = ProxyRules.sha256Hex(data);
        if (!actual.equals(expectedSha)) throw new IOException("规则校验和不匹配");
        ProxyRules parsed = ProxyRules.parse(new String(data, StandardCharsets.UTF_8));
        if (parsed.isEmpty()) throw new IOException("规则内容为空");
        return new Pending(actual, data, parsed);
    }

    /** Bounded fetch; identity encoding so the hash covers exactly the bytes the server served. */
    private static byte[] fetch(String start, URL hub) throws IOException {
        String current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IOException("跳转缺少目标");
                    URL next = new URL(new URL(current), location.trim());
                    if (!"https".equals(next.getProtocol()) || next.getUserInfo() != null
                            || !sameOrigin(hub, next)) {
                        throw new IOException("拒绝跨源跳转");
                    }
                    current = sameOriginRulesUrl(hub.toString(), next.toString());
                    if (!new URL(start).getPath().equals(next.getPath())) throw new IOException("跳转规则版本不一致");
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                return readBounded(conn);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("跳转次数超过 " + MAX_REDIRECTS);
    }

    private static byte[] readBounded(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > ProxyRules.MAX_BYTES) throw new IOException("规则体积超过上限");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Write an immutable candidate first, then synchronously commit its pointer; never replace LKG. */
    public boolean commit(Pending pending) {
        if (pending == null || !ProxyRules.isSha256Hex(pending.sha256)
                || !pending.sha256.equals(ProxyRules.sha256Hex(pending.bytes))) return false;
        File target = artifact(pending.sha256);
        if (ProxyRules.verifyFile(target, pending.sha256) == null
                && !ProxyRules.writeAtomic(target, pending.bytes)) return false;
        ProxyRules verified = ProxyRules.verifyFile(target, pending.sha256);
        if (verified == null || verified.isEmpty()) return false;
        SharedPreferences state = prefs();
        String previousSha = getActiveSha256();
        long previousTime = getUpdatedAt();
        int previousEntries = getEntryCount();
        boolean saved = state.edit().putString(KEY_SHA, pending.sha256)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .putInt(KEY_ENTRIES, verified.entryCount()).putString(KEY_ERROR, "").commit();
        if (!saved) {
            // SharedPreferences.commit() can update memory even when disk persistence fails.
            state.edit().putString(KEY_SHA, previousSha).putLong(KEY_UPDATED_AT, previousTime)
                    .putInt(KEY_ENTRIES, previousEntries).commit();
        } else {
            pruneArtifacts(pending.sha256, previousSha);
        }
        return saved;
    }

    /**
     * Removes rule files no longer referenced.
     *
     * <p>Artifacts are named by content hash and were never deleted, so every published rule update
     * left a permanent copy behind — up to 12 MB each. Only the active file and its predecessor are
     * worth keeping; the predecessor is the last-known-good the sync path can still fall back to.
     */
    private void pruneArtifacts(String active, String previous) {
        File[] files = new File(context.getFilesDir(), "rules").listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".json")) continue;
            String sha = name.substring(0, name.length() - ".json".length());
            if (sha.equals(active) || sha.equals(previous)) continue;
            // A candidate another thread is mid-commit on is protected by its own verify step; a
            // failed delete is harmless and simply retried on the next successful commit.
            file.delete();
        }
    }

    public void recordUpstreams(JSONObject payload) {
        String summary = upstreamSummary(payload);
        if (!summary.isEmpty()) prefs().edit().putString(KEY_UPSTREAM, summary).apply();
    }

    static String upstreamSummary(JSONObject payload) {
        JSONObject releases = payload.optJSONObject("upstreamReleases");
        if (releases == null) return "";
        StringBuilder summary = new StringBuilder();
        for (String[] watch : new String[][]{{"flclash", "FlClash"}, {"mihomo", "Mihomo"}, {"singbox", "sing-box"}}) {
            JSONObject release = releases.optJSONObject(watch[0]);
            String tag = release == null ? "" : release.optString("tag", "");
            if (!tag.matches("[A-Za-z0-9._+-]{1,64}")) continue;
            if (summary.length() > 0) summary.append(" · ");
            summary.append(watch[1]).append(' ').append(tag);
        }
        return summary.length() == 0 ? "" : "上游观察：" + summary + "（协议变更需升级 APK）";
    }

    public void recordFailure(String reason) {
        prefs().edit().putString(KEY_ERROR, reason == null ? "" : reason).apply();
    }

    /**
     * One UI line; never contains a URL, credentials, or more than 8 hash characters.
     *
     * <p>Shortcut: reads recorded metadata instead of re-parsing the bundle, because this runs on
     * the main thread and a full verify costs ~250 ms on a 150k-entry file. {@link #load()} — the
     * path that actually routes traffic — always re-verifies the bytes.
     */
    public String statusLine() {
        String sha = getActiveSha256();
        String error = getLastError();
        String upstream = prefs().getString(KEY_UPSTREAM, "");
        String suffix = (error.isEmpty() ? "" : " · 最近失败：" + error)
                + (upstream.isEmpty() ? "" : "\n" + upstream);
        if (!ProxyRules.isSha256Hex(sha) || !file().isFile()) return "云规则：内置默认（" + builtIn().entryCount() + " 条）" + suffix;
        String updated = getUpdatedAt() > 0
                ? new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(getUpdatedAt()))
                : "";
        return "云规则 " + sha.substring(0, 8)
                + (getEntryCount() > 0 ? " · " + getEntryCount() + " 条" : "")
                + (updated.isEmpty() ? "" : " · " + updated) + suffix;
    }
}
