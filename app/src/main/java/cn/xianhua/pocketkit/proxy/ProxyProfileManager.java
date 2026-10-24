package cn.xianhua.pocketkit.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Multi-subscription profile management & unified node aggregation pool.
 *
 * <p>Each node keeps its complete sing-box outbound JSON so the runtime config never has to
 * re-derive credentials from a lossy summary. Subscriptions are only a source of nodes; the
 * sing-box config for the active node comes from {@link #buildRuntimeConfig(String, ProxyState.Mode)}.
 */
public final class ProxyProfileManager {
    private static final String TAG = "ProxyProfileManager";
    private static final Object DB_LOCK = new Object();

    /** Controlled name for the untrusted "Hub" remote config; no URL or credentials baked in. */
    public static final String HUB_SUBSCRIPTION_NAME = "Hub 订阅";

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String USER_AGENT = "clash.meta/1.19.0 sing-box/1.14.3";

    public static final class Subscription {
        public String id;
        public String name;
        public String url;
        public int nodeCount;
        public long lastUpdated;
        public boolean enabled;

        public Subscription(String id, String name, String url) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.name = name;
            this.url = url;
            this.nodeCount = 0;
            this.lastUpdated = 0;
            this.enabled = true;
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("name", name);
                obj.put("url", url);
                obj.put("nodeCount", nodeCount);
                obj.put("lastUpdated", lastUpdated);
                obj.put("enabled", enabled);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Subscription fromJson(JSONObject obj) {
            Subscription s = new Subscription(
                    obj.optString("id", UUID.randomUUID().toString()),
                    obj.optString("name", "未命名订阅"),
                    obj.optString("url", "")
            );
            s.nodeCount = obj.optInt("nodeCount", 0);
            s.lastUpdated = obj.optLong("lastUpdated", 0);
            s.enabled = obj.optBoolean("enabled", true);
            return s;
        }
    }

    public static final class Node {
        public final String name;
        public final String type;
        public final String server;
        public final int port;
        public final String subId;
        public final String subName;
        public int delayMs = -1; // -1: untested, 0: testing, >0: delay in ms

        /** Complete sing-box outbound JSON (no tag); null when the node was rejected. */
        public final String outboundJson;
        /** Why this node cannot be used, null when usable. */
        public final String rejectReason;

        public Node(String name, String type, String server, int port, String subId, String subName) {
            this(name, type, server, port, subId, subName, null, null);
        }

        public Node(String name, String type, String server, int port, String subId, String subName,
                    String outboundJson, String rejectReason) {
            this.name = name;
            this.type = type;
            this.server = server;
            this.port = port;
            this.subId = subId;
            this.subName = subName;
            this.outboundJson = outboundJson;
            this.rejectReason = rejectReason;
        }

        public boolean usable() {
            return outboundJson != null && !outboundJson.isEmpty();
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", name);
                obj.put("type", type);
                obj.put("server", server);
                obj.put("port", port);
                obj.put("subId", subId);
                obj.put("subName", subName);
                obj.put("delayMs", delayMs);
                if (outboundJson != null) obj.put("outbound", outboundJson);
                if (rejectReason != null) obj.put("rejectReason", rejectReason);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Node fromJson(JSONObject obj) {
            Node n = new Node(
                    obj.optString("name", "未命名"),
                    obj.optString("type", "ss"),
                    obj.optString("server", ""),
                    obj.optInt("port", 443),
                    obj.optString("subId", ""),
                    obj.optString("subName", ""),
                    obj.isNull("outbound") ? null : obj.optString("outbound", null),
                    obj.isNull("rejectReason") ? null : obj.optString("rejectReason", null)
            );
            n.delayMs = obj.optInt("delayMs", -1);
            return n;
        }
    }

    private static final String PREF_PROFILES = "proxy_multi_subscriptions_db";
    private static final String KEY_SUBSCRIPTIONS = "subscriptions_list";
    private static final String KEY_NODES_POOL = "unified_nodes_pool";
    private static final String KEY_HUB_REMOVED = "hub_subscription_removed";

    private final Context context;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    /** Set once the pool has been read from disk; every mutator keeps {@link #nodes} current. */
    private boolean loaded;
    private volatile String lastError;

    public ProxyProfileManager(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    /** Last failure that a UI can show; cleared by the next successful operation. Never contains URLs. */
    public String getLastError() {
        return lastError;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREF_PROFILES, Context.MODE_PRIVATE);
    }

    public boolean isHubSyncDisabled() { return prefs().getBoolean(KEY_HUB_REMOVED, false); }

    public void load() {
        synchronized (DB_LOCK) {
            SharedPreferences sp = prefs();
            subscriptions.clear();
            subscriptions.addAll(readSubscriptions(sp));
            nodes.clear();
            nodes.addAll(retainActiveNodes(subscriptions, readNodes(sp)));
            loaded = true;
        }
    }

    /** Drop deleted-source and credential-less placeholders; preserve usable manual entries. */
    static List<Node> retainActiveNodes(List<Subscription> subs, List<Node> stored) {
        Set<String> owners = new HashSet<>();
        for (Subscription sub : subs) owners.add(sub.id);
        List<Node> kept = new ArrayList<>();
        for (Node node : stored) {
            if (node != null && node.usable()
                    && (node.subId == null || node.subId.isEmpty() || owners.contains(node.subId))) kept.add(node);
        }
        return kept;
    }

    private static List<Subscription> readSubscriptions(SharedPreferences sp) {
        List<Subscription> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_SUBSCRIPTIONS, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(Subscription.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    private static List<Node> readNodes(SharedPreferences sp) {
        List<Node> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_NODES_POOL, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(Node.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    private static void write(SharedPreferences sp, List<Subscription> subs, List<Node> nodeList) {
        List<Node> kept = retainActiveNodes(subs, nodeList);
        JSONArray subArr = new JSONArray();
        for (Subscription s : subs) {
            s.nodeCount = 0;
            for (Node n : kept) if (s.id.equals(n.subId)) s.nodeCount++;
            subArr.put(s.toJson());
        }
        JSONArray nodeArr = new JSONArray();
        for (Node n : kept) nodeArr.put(n.toJson());
        sp.edit()
                .putString(KEY_SUBSCRIPTIONS, subArr.toString())
                .putString(KEY_NODES_POOL, nodeArr.toString())
                .apply();
    }

    public List<Subscription> getSubscriptions() {
        synchronized (DB_LOCK) {
            load();
            return new ArrayList<>(subscriptions);
        }
    }

    public void addSubscription(String name, String url) {
        synchronized (DB_LOCK) {
            if (url == null || url.trim().isEmpty()) {
                lastError = "订阅地址为空";
                return;
            }
            // Re-read first: another instance may have added subscriptions since we loaded.
            SharedPreferences sp = prefs();
            List<Subscription> current = readSubscriptions(sp);
            String label = (name == null || name.trim().isEmpty()) ? "订阅 " + (current.size() + 1) : name.trim();
            current.add(new Subscription(UUID.randomUUID().toString(), label, url.trim()));
            if (HUB_SUBSCRIPTION_NAME.equals(label)) sp.edit().putBoolean(KEY_HUB_REMOVED, false).apply();
            write(sp, current, readNodes(sp));
            subscriptions.clear();
            subscriptions.addAll(current);
            lastError = null;
        }
    }

    public void removeSubscription(String subId) {
        synchronized (DB_LOCK) {
            SharedPreferences sp = prefs();
            List<Subscription> subs = readSubscriptions(sp);
            Iterator<Subscription> it = subs.iterator();
            boolean removedHub = false;
            while (it.hasNext()) {
                Subscription entry = it.next();
                if (entry.id.equals(subId)) {
                    removedHub |= HUB_SUBSCRIPTION_NAME.equals(entry.name);
                    it.remove();
                }
            }
            if (removedHub) sp.edit().putBoolean(KEY_HUB_REMOVED, true).apply();
            List<Node> kept = readNodes(sp);
            Iterator<Node> nIt = kept.iterator();
            while (nIt.hasNext()) {
                if (subId.equals(nIt.next().subId)) nIt.remove();
            }
            write(sp, subs, kept);
            subscriptions.clear();
            subscriptions.addAll(subs);
            nodes.clear();
            nodes.addAll(kept);
        }
    }

    public List<Node> getNodes() {
        synchronized (DB_LOCK) {
            // load() re-reads and re-parses the whole pool from disk. Every mutator here already
            // refreshes `nodes`, so this is only needed on the first call — and the callers are on
            // the main thread (list rendering), where a full JSON parse per call is visible.
            if (!loaded) {
                load();
                loaded = true;
            }
            return new ArrayList<>(nodes);
        }
    }

    /** Latency results are cosmetic: re-read the pool so a concurrent subscription refresh is not overwritten. */
    public void updateNodeDelay(String name, int delayMs) {
        synchronized (DB_LOCK) {
            SharedPreferences sp = prefs();
            List<Node> stored = readNodes(sp);
            for (Node n : stored) {
                if (n.name.equals(name)) n.delayMs = delayMs;
            }
            write(sp, readSubscriptions(sp), stored);
            nodes.clear();
            nodes.addAll(stored);
        }
    }

    public File getConfigFile() {
        File dir = new File(context.getFilesDir(), "clash");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "config.yaml");
    }

    /** Writes a caller-supplied config file only; subscription fetches never go through here. */
    public synchronized void writeConfigYaml(String yaml) {
        if (yaml == null) return;
        try (FileOutputStream fos = new FileOutputStream(getConfigFile())) {
            fos.write(yaml.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.w(TAG, "writeConfigYaml failed", e);
        }
    }

    // ---------------------------------------------------------------- runtime config

    /**
     * sing-box 1.14 runtime config for the selected node.
     *
     * @throws IllegalStateException when the mode needs a node but none is usable; the caller must
     *                               refuse to start rather than fall back to an empty tunnel.
     */
    public String buildRuntimeConfig(String selectedName, ProxyState.Mode mode) {
        return buildRuntimeConfig(selectedName, mode, null);
    }

    public String buildRuntimeConfig(String selectedName, ProxyState.Mode mode, String clashApiSecret) {
        return buildRuntimeConfig(selectedName, mode, clashApiSecret, null);
    }

    /**
     * @param rules cloud routing rules, null to fall back to the pre-rules split (private IPs + .cn)
     */
    public String buildRuntimeConfig(String selectedName, ProxyState.Mode mode, String clashApiSecret,
                                     ProxyRules rules) {
        ProxyState.Mode effective = mode == null ? ProxyState.Mode.RULE : mode;
        if (effective == ProxyState.Mode.DIRECT) {
            lastError = null;
            return ProxyConfig.buildRuntimeConfig(null, effective.coreValue, clashApiSecret, rules);
        }

        String wanted = selectedName == null ? "" : selectedName.trim();
        if (wanted.isEmpty()) {
            lastError = "未选择节点";
            throw new IllegalStateException(lastError);
        }
        Node chosen = null;
        String reason = null;
        for (Node n : getNodes()) {
            if (!n.name.equals(wanted)) continue;
            if (n.usable()) {
                chosen = n;
                break;
            }
            if (reason == null) reason = n.rejectReason;
        }
        if (chosen == null) {
            lastError = reason != null && !reason.isEmpty()
                    ? "节点「" + wanted + "」不可用: " + reason
                    : "未找到有效节点「" + wanted + "」，请先更新订阅";
            throw new IllegalStateException(lastError);
        }
        try {
            lastError = null;
            return ProxyConfig.buildRuntimeConfig(chosen.outboundJson, effective.coreValue, clashApiSecret, rules);
        } catch (RuntimeException e) {
            lastError = "节点配置生成失败，请重新同步订阅";
            throw new IllegalStateException(lastError, e);
        }
    }

    // ---------------------------------------------------------------- fetching

    /**
     * Bounded, validated HTTP(S) fetch: 5 redirects, capped body size, gzip/deflate, always released.
     */
    public static String fetchHttpContent(String targetUrl) throws IOException {
        String currentUrl = validateUrl(targetUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("HTTP " + status + " 跳转缺少 Location");
                    }
                    String redirected = validateUrl(new URL(new URL(currentUrl), location.trim()).toString());
                    if ("https".equalsIgnoreCase(new URL(currentUrl).getProtocol())
                            && !"https".equalsIgnoreCase(new URL(redirected).getProtocol())) {
                        throw new IOException("拒绝 HTTPS 降级跳转");
                    }
                    currentUrl = redirected;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                return readBody(conn);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("跳转次数超过 " + MAX_REDIRECTS);
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getInputStream();
        try {
            String encoding = conn.getHeaderField("Content-Encoding");
            String lower = encoding == null ? "" : encoding.toLowerCase(Locale.ROOT);
            if (lower.contains("gzip")) {
                in = new GZIPInputStream(in);
            } else if (lower.contains("deflate")) {
                in = new InflaterInputStream(in);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("响应超过 " + (MAX_RESPONSE_BYTES / 1024 / 1024) + "MB 上限");
                }
                out.write(buffer, 0, read);
            }
            String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return body.startsWith("\uFEFF") ? body.substring(1) : body;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {}
        }
    }

    /** Rejects non-HTTP(S) and host-less targets before any socket is opened. */
    static String validateUrl(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException("订阅地址为空");
        URL url;
        try {
            url = new URL(raw.trim());
        } catch (Exception e) {
            throw new IOException("订阅地址不是合法 URL");
        }
        String protocol = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IOException("仅支持 http/https 订阅地址");
        }
        if (url.getHost() == null || url.getHost().isEmpty()) throw new IOException("订阅地址缺少主机名");
        if (url.getUserInfo() != null) throw new IOException("订阅地址不能含嵌入式账户口令");
        return url.toString();
    }

    /**
     * Fetch and parse one subscription into the shared node pool.
     *
     * <p>Reads the current preferences first so another instance's nodes are never clobbered, and
     * aborts (keeping the old nodes) when the subscription was deleted while we were fetching.
     */
    public boolean fetchAndParseSubscription(Subscription sub) {
        if (sub == null || sub.url == null || sub.url.trim().isEmpty()) {
            lastError = "订阅地址为空";
            return false;
        }
        String content;
        try {
            content = fetchHttpContent(sub.url);
        } catch (IOException e) {
            lastError = "订阅拉取失败（" + e.getClass().getSimpleName() + "），已保留原节点";
            Log.w(TAG, "Subscription fetch failed: " + e.getClass().getSimpleName());
            return false;
        }

        List<Node> parsed = parseContentToNodes(content, sub.id, sub.name);
        List<Node> usable = new ArrayList<>();
        String firstReject = null;
        for (Node n : parsed) {
            if (n.usable()) usable.add(n);
            else if (firstReject == null) firstReject = n.rejectReason;
        }
        if (usable.isEmpty()) {
            lastError = "「" + sub.name + "」无可用节点"
                    + (firstReject != null ? ": " + firstReject : "（内容为空或格式不支持）");
            Log.w(TAG, "no usable nodes for sub " + sub.name);
            return false;
        }

        synchronized (DB_LOCK) {
            // Deletion race: the subscription may be gone (removed by another instance) while fetching.
            SharedPreferences sp = prefs();
            List<Subscription> subs = readSubscriptions(sp);
            Subscription target = null;
            for (Subscription s : subs) {
                if (s.id.equals(sub.id)) target = s;
            }
            if (target == null) {
                lastError = "「" + sub.name + "」已在拉取期间被删除";
                load();
                return false;
            }

            List<Node> merged = mergeForSubscription(retainActiveNodes(subs, readNodes(sp)), sub.id, usable);

            target.nodeCount = usable.size();
            target.lastUpdated = System.currentTimeMillis();
            write(sp, subs, merged);

            subscriptions.clear();
            subscriptions.addAll(subs);
            nodes.clear();
            nodes.addAll(merged);

            sub.nodeCount = target.nodeCount;
            sub.lastUpdated = target.lastUpdated;
        }
        lastError = usable.size() < parsed.size()
                ? "「" + sub.name + "」部分节点被拒绝: " + (usable.size()) + "/" + parsed.size() + " 可用"
                : null;
        return true;
    }

    /**
     * Replaces one subscription's slice of the pool and keeps every other subscription's nodes.
     * Package-visible so the multi-instance merge rule is testable off-device.
     */
    static List<Node> mergeForSubscription(List<Node> stored, String subId, List<Node> fresh) {
        List<Node> merged = new ArrayList<>();
        for (Node n : stored) {
            if (!subId.equals(n.subId)) merged.add(n);
        }
        merged.addAll(fresh);
        return merged;
    }

    /**
     * Maintains the single "Hub" remote config subscription and refreshes it.
     * Used by update flows so Hub configs never overwrite user subscriptions' nodes.
     */
    public boolean importRemoteConfig(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            lastError = "Hub 订阅地址为空";
            return false;
        }
        Subscription hub;
        synchronized (DB_LOCK) {
            if (isHubSyncDisabled()) {
                lastError = "已取消云订阅自动同步；可手动添加 Hub 订阅恢复";
                return false;
            }
            SharedPreferences sp = prefs();
            List<Subscription> subs = readSubscriptions(sp);
            Subscription existing = null;
            for (Subscription s : subs) {
                if (HUB_SUBSCRIPTION_NAME.equals(s.name)) {
                    existing = s;
                    break;
                }
            }
            if (existing == null) {
                existing = new Subscription(UUID.randomUUID().toString(), HUB_SUBSCRIPTION_NAME, trimmed);
                subs.add(existing);
                write(sp, subs, readNodes(sp));
            } else if (!trimmed.equals(existing.url)) {
                existing.url = trimmed;
                write(sp, subs, readNodes(sp));
            }
            subscriptions.clear();
            subscriptions.addAll(subs);
            nodes.clear();
            nodes.addAll(readNodes(sp));
            hub = existing;
        }
        return fetchAndParseSubscription(hub);
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Parses Clash JSON/YAML, base64-wrapped URI lists and plain URI lists.
     * Rejected entries are kept (with a reason) so the UI can explain why a node is unusable.
     */
    public List<Node> parseContentToNodes(String content, String subId, String subName) {
        List<Node> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProxyConfig.Entry entry : ProxyConfig.parse(content)) {
            // Same name twice would make selection ambiguous; keep them distinct.
            String name = entry.name;
            if (!seen.add(name)) {
                int suffix = 2;
                while (!seen.add(name + " (" + suffix + ")")) suffix++;
                name = name + " (" + suffix + ")";
            }
            result.add(new Node(name, entry.type, entry.server, entry.port, subId, subName,
                    entry.outboundJson, entry.rejectReason));
        }
        return result;
    }
}
