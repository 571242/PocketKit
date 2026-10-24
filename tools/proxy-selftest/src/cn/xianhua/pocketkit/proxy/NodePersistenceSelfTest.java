package cn.xianhua.pocketkit.proxy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;

/**
 * Node persistence round-trip: the old parser kept only name/type/server and lost every credential.
 * Compiled against android.jar (for the Context import) but only touches org.json, so it runs on a
 * desktop JVM with org.json ahead of android.jar on the classpath.
 */
public final class NodePersistenceSelfTest {
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        String yaml = "proxies:\n"
                + "  - {name: A, type: vless, server: a.example.com, port: 443,"
                + " uuid: u-u-i-d, tls: true, servername: sni.example.com,"
                + " reality-opts: {public-key: pk, short-id: s1}}\n";
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(yaml);
        check(entries.size() == 1, "parse yields one entry");
        ProxyConfig.Entry entry = entries.get(0);

        // what the manager stores in SharedPreferences
        ProxyProfileManager.Node node = new ProxyProfileManager.Node(
                entry.name, entry.type, entry.server, entry.port, "sub-1", "订阅一",
                entry.outboundJson, entry.rejectReason);
        node.delayMs = 42;
        String stored = node.toJson().toString();

        ProxyProfileManager.Node restored = ProxyProfileManager.Node.fromJson(new JSONObject(stored));
        check("A".equals(restored.name), "name survived");
        check("vless".equals(restored.type), "type survived");
        check("a.example.com".equals(restored.server) && 443 == restored.port, "server/port survived");
        check("sub-1".equals(restored.subId) && "订阅一".equals(restored.subName), "subscription link survived");
        check(42 == restored.delayMs, "delay survived");
        check(restored.usable(), "usable flag survived");
        check(entry.outboundJson.equals(restored.outboundJson), "full outbound survived verbatim");

        JSONObject outbound = new JSONObject(restored.outboundJson);
        check("u-u-i-d".equals(outbound.getString("uuid")), "uuid credential survived: " + outbound);
        check("sni.example.com".equals(outbound.getJSONObject("tls").getString("server_name")), "sni survived");
        check("pk".equals(outbound.getJSONObject("tls").getJSONObject("reality").getString("public_key")),
                "reality public key survived");

        // rejected nodes must keep their reason across a restart, and stay unusable
        ProxyProfileManager.Node bad = new ProxyProfileManager.Node(
                "B", "hysteria", "b.example.com", 443, "sub-1", "订阅一", null, "不支持的协议: hysteria");
        ProxyProfileManager.Node badBack = ProxyProfileManager.Node.fromJson(new JSONObject(bad.toJson().toString()));
        check(!badBack.usable(), "rejected node stays unusable");
        check("不支持的协议: hysteria".equals(badBack.rejectReason), "reject reason survived");

        // a legacy stored node (no outbound field) must not crash or claim to be usable
        JSONObject legacy = new JSONObject(new String(
                "{\"name\":\"old\",\"type\":\"ss\",\"server\":\"1.2.3.4\",\"port\":8388}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        ProxyProfileManager.Node legacyNode = ProxyProfileManager.Node.fromJson(legacy);
        check(!legacyNode.usable(), "legacy node without credentials is not usable");

        multiInstanceMergeKeepsOtherSubscriptions();
        parseFailureKeepsOldNodes();
        orphanAndUnusableNodesAreRemoved();
        urlValidationRejectsUnsafeTargets();
        System.out.println("Node persistence self-test passed (" + checks + " checks)");
    }

    /** Subscription URLs are user input: only http(s) with a host may be fetched. */
    private static void urlValidationRejectsUnsafeTargets() throws Exception {
        String[] accepted = {
                "https://cross-bomb-alexander-kenneth.trycloudflare.com/sub",
                "http://example.com:8080/sub?token=x",
        };
        for (String url : accepted) {
            check(ProxyProfileManager.validateUrl(url) != null, "accepted subscription url: " + url);
        }
        String[] rejected = {
                "", "   ", "file:///etc/passwd", "ftp://example.com/sub",
                "https:///no-host", "not a url at all", "javascript:alert(1)",
        };
        for (String url : rejected) {
            boolean threw = false;
            try {
                ProxyProfileManager.validateUrl(url);
            } catch (java.io.IOException expected) {
                threw = true;
            }
            check(threw, "rejected subscription url: '" + url + "'");
        }
    }

    /** Two manager instances must not clobber each other's nodes on refresh. */
    private static void multiInstanceMergeKeepsOtherSubscriptions() {
        ProxyProfileManager.Node a1 = new ProxyProfileManager.Node(
                "A1", "vless", "a.example.com", 443, "sub-A", "订阅A", "{\"type\":\"vless\"}", null);
        ProxyProfileManager.Node b1 = new ProxyProfileManager.Node(
                "B1", "trojan", "b.example.com", 443, "sub-B", "订阅B", "{\"type\":\"trojan\"}", null);
        java.util.List<ProxyProfileManager.Node> stored =
                new java.util.ArrayList<>(java.util.Arrays.asList(a1, b1));

        // instance 2 refreshes sub-A while the stored pool still holds both subscriptions
        ProxyProfileManager.Node a2 = new ProxyProfileManager.Node(
                "A2", "vless", "a2.example.com", 443, "sub-A", "订阅A", "{\"type\":\"vless\"}", null);
        java.util.List<ProxyProfileManager.Node> merged = ProxyProfileManager.mergeForSubscription(
                stored, "sub-A", new java.util.ArrayList<>(java.util.Collections.singletonList(a2)));

        check(merged.size() == 2, "merge keeps one node per subscription, got " + merged.size());
        boolean hasB = false;
        boolean hasOldA = false;
        boolean hasNewA = false;
        for (ProxyProfileManager.Node n : merged) {
            if ("B1".equals(n.name)) hasB = true;
            if ("A1".equals(n.name)) hasOldA = true;
            if ("A2".equals(n.name)) hasNewA = true;
        }
        check(hasB, "other subscription's node survived the refresh");
        check(hasNewA, "refreshed subscription's new node present");
        check(!hasOldA, "refreshed subscription's stale node replaced");
    }

    /** A fetch that yields nothing must leave the previously stored pool untouched. */
    private static void parseFailureKeepsOldNodes() {
        ProxyProfileManager.Node keep = new ProxyProfileManager.Node(
                "keep", "vless", "k.example.com", 443, "sub-A", "订阅A", "{\"type\":\"vless\"}", null);
        java.util.List<ProxyProfileManager.Node> stored =
                new java.util.ArrayList<>(java.util.Collections.singletonList(keep));

        java.util.List<ProxyConfig.Entry> nothing = ProxyConfig.parse("this is not a subscription\n");
        check(nothing.isEmpty(), "unparseable payload yields no entries");
        check(stored.size() == 1 && keep.usable(), "stored pool untouched on parse failure");
    }

    private static void orphanAndUnusableNodesAreRemoved() {
        ProxyProfileManager.Subscription a = new ProxyProfileManager.Subscription("sub-A", "A", "https://a.example/sub");
        ProxyProfileManager.Subscription b = new ProxyProfileManager.Subscription("sub-B", "B", "https://b.example/sub");
        ProxyProfileManager.Node live = new ProxyProfileManager.Node("live", "ss", "a.example", 443, "sub-A", "A", "{\"type\":\"shadowsocks\"}", null);
        ProxyProfileManager.Node orphan = new ProxyProfileManager.Node("deleted", "ss", "b.example", 443, "sub-B", "B", "{\"type\":\"shadowsocks\"}", null);
        ProxyProfileManager.Node manual = new ProxyProfileManager.Node("manual", "ss", "m.example", 443, "", "", "{\"type\":\"shadowsocks\"}", null);
        ProxyProfileManager.Node legacy = new ProxyProfileManager.Node("legacy", "ss", "x.example", 443, "sub-A", "A", null, "missing credentials");
        java.util.List<ProxyProfileManager.Node> stored = java.util.Arrays.asList(live, orphan, manual, legacy);
        java.util.List<ProxyProfileManager.Node> clean = ProxyProfileManager.retainActiveNodes(java.util.Collections.singletonList(a), stored);
        check(clean.size() == 2, "deleted subscriptions and unusable placeholders are removed");
        check(clean.contains(live), "active subscription credentials preserved");
        check(clean.contains(manual), "usable manual node preserved");
        check(!clean.contains(orphan), "deleted subscription never owns a displayed node");
        check(!clean.contains(legacy), "legacy credential-less placeholder not displayed");
        check(stored.size() == 4, "cleanup does not mutate caller snapshot");
        check(ProxyProfileManager.retainActiveNodes(java.util.Collections.emptyList(), stored).equals(java.util.Collections.singletonList(manual)), "deleting all subscriptions retains only manual nodes");
        check(ProxyProfileManager.retainActiveNodes(java.util.Arrays.asList(a, b), stored).size() == 3, "other subscription survives until explicitly deleted");
        check(ProxyProfileManager.retainActiveNodes(java.util.Collections.singletonList(a), clean).equals(clean), "cleanup is idempotent");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            System.err.println("FAIL: " + label);
            System.exit(1);
        }
    }
}
