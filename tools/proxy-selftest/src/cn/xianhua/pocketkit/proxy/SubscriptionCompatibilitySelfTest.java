package cn.xianhua.pocketkit.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Exercises subscription import through the real manager with an in-memory Android preferences stub. */
public final class SubscriptionCompatibilitySelfTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Prefs implements SharedPreferences {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Boolean> booleans = new HashMap<>();
        public String getString(String k, String d) { return strings.getOrDefault(k, d); }
        public boolean getBoolean(String k, boolean d) { return booleans.getOrDefault(k, d); }
        public Editor edit() { return new Editor() {
            public Editor putString(String k, String v) { strings.put(k, v); return this; }
            public Editor putBoolean(String k, boolean v) { booleans.put(k, v); return this; }
            public void apply() { }
        }; }
    }
    private static final class TestContext extends Context {
        final Prefs prefs = new Prefs();
        public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    }
    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static void parsing() throws Exception {
        String nativeJson = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"},"
                + "{\"type\":\"vless\",\"tag\":\"Native\",\"server\":\"v.example\",\"server_port\":443,"
                + "\"uuid\":\"11111111-2222-3333-4444-555555555555\",\"tls\":{\"enabled\":true,\"server_name\":\"sni.example\"}}]}";
        List<ProxyConfig.Entry> nativeEntries = ProxyConfig.parse(nativeJson);
        check(nativeEntries.size() == 1 && nativeEntries.get(0).usable(), "sing-box JSON outbound imported");
        check("Native".equals(nativeEntries.get(0).name), "native tag becomes name");
        JSONObject nativeOut = new JSONObject(nativeEntries.get(0).outboundJson);
        check(!nativeOut.has("tag") && "sni.example".equals(nativeOut.getJSONObject("tls").getString("server_name")),
                "native outbound credentials and options retained, without tag");
        String nativeYaml = "outbounds:\n  - type: trojan\n    tag: YAML\n    server: t.example\n    server_port: 443\n    password: secret\n    tls: {enabled: true}\n";
        List<ProxyConfig.Entry> yaml = ProxyConfig.parse(nativeYaml);
        check(yaml.size() == 1 && yaml.get(0).usable(), "sing-box YAML imported");
        List<ProxyConfig.Entry> wrappedYaml = ProxyConfig.parse(b64(nativeYaml));
        check(wrappedYaml.size() == 1 && wrappedYaml.get(0).usable(), "URL-safe base64-wrapped YAML imported");
        String nodes = "vless://11111111-2222-3333-4444-555555555555@v.example:443?security=tls#one\n"
                + "trojan://password@t.example:443#two";
        check(ProxyConfig.parse(b64(nodes)).size() == 2, "URL-safe unpadded base64 URI list");
        String dependent = "{\"outbounds\":[{\"type\":\"trojan\",\"tag\":\"dep\",\"server\":\"x.example\",\"server_port\":443,\"password\":\"p\",\"detour\":\"other\"}]}";
        List<ProxyConfig.Entry> dep = ProxyConfig.parse(dependent);
        check(dep.size() == 1 && !dep.get(0).usable(), "dependent native outbound is not imported broken");
        String vm = "{\"v\":\"2\",\"ps\":\"VM\",\"add\":\"vm.example\",\"port\":\"443\","
                + "\"id\":\"11111111-2222-3333-4444-555555555555\",\"net\":\"ws\",\"path\":\"/vm\",\"tls\":\"tls\"}";
        String[] shares = {
                "vmess://" + b64(vm),
                "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443?security=tls#VLESS",
                "trojan://p%40ss@t.example:443?sni=t.example#Trojan",
                "ss://" + b64("aes-128-gcm:pass") + "@s.example:8388#SIP002",
                "ss://" + b64("aes-128-gcm:pass@s.example:8388") + "#legacy",
                "hy2://pass@h.example:443?sni=h.example#HY2"
        };
        for (String share : shares) {
            List<ProxyConfig.Entry> entries = ProxyConfig.parse(share);
            check(entries.size() == 1 && entries.get(0).usable(), "share URI imports: " + share.substring(0, share.indexOf(':')));
            String runtime = ProxyConfig.buildRuntimeConfig(entries.get(0).outboundJson, "Rule", "fixture");
            java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "native-configs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("node-" + sharesIndex(share, shares) + ".json"), runtime);
        }
        String nativeRuntime = ProxyConfig.buildRuntimeConfig(nativeEntries.get(0).outboundJson, "Rule", "fixture");
        java.nio.file.Files.writeString(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "native-configs", "native.json"), nativeRuntime);
    }
    private static int sharesIndex(String value, String[] values) {
        return java.util.Arrays.asList(values).indexOf(value);
    }
    private static void directImportAndPreservation() throws Exception {
        TestContext context = new TestContext();
        ProxyProfileManager manager = new ProxyProfileManager(context);
        String direct = "vless://11111111-2222-3333-4444-555555555555@v.example:443?security=tls#One\n"
                + "trojan://password@t.example:443#Two";
        manager.addSubscription("direct", direct);
        ProxyProfileManager.Subscription sub = manager.getSubscriptions().get(0);
        check(manager.fetchAndParseSubscription(sub), "multi-line nodes imported: " + manager.getLastError());
        check(manager.getNodes().size() == 2, "both nodes entered the pool");
        ProxyProfileManager reopened = new ProxyProfileManager(context);
        check(reopened.getNodes().size() == 2, "direct nodes persist after reload");
        sub.url = "trojan://@missing.example:443#bad";
        check(!manager.fetchAndParseSubscription(sub), "invalid update is rejected");
        check(new ProxyProfileManager(context).getNodes().size() == 2, "invalid update keeps old nodes");
    }
    private static void httpImportAndPreservation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] response = {"trojan://password@net.example:443#Network"};
        server.createContext("/sub", exchange -> {
            byte[] bytes = response[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/gzip");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/gzip", exchange -> {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write(response[0].getBytes(StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, bytes.size());
            bytes.writeTo(exchange.getResponseBody());
            exchange.close();
        });
        server.start();
        try {
            TestContext context = new TestContext();
            ProxyProfileManager manager = new ProxyProfileManager(context);
            manager.addSubscription("http", "http://127.0.0.1:" + server.getAddress().getPort() + "/sub");
            ProxyProfileManager.Subscription sub = manager.getSubscriptions().get(0);
            check(manager.fetchAndParseSubscription(sub), "HTTP subscription imported: " + manager.getLastError());
            String redirected = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";
            check(ProxyProfileManager.fetchHttpContent(redirected).equals(response[0]), "relative redirect and gzip decode");
            response[0] = "not a subscription";
            check(!manager.fetchAndParseSubscription(sub), "invalid HTTP response rejected");
            check(new ProxyProfileManager(context).getNodes().size() == 1, "failed HTTP refresh keeps old node");
        } finally { server.stop(0); }
    }
    public static void main(String[] args) throws Exception {
        directImportAndPreservation();
        parsing();
        httpImportAndPreservation();
        System.out.println("Subscription compatibility self-test passed (" + checks + " checks)");
    }
}
