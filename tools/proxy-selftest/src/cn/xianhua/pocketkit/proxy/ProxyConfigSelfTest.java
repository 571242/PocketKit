package cn.xianhua.pocketkit.proxy;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Off-device self-test for ProxyConfig (pure Java: org.json + SnakeYAML only).
 * Run with tools/proxy-selftest/run.sh. Exits non-zero on the first failure.
 */
public final class ProxyConfigSelfTest {
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        clashYamlVlessWsTls();
        clashJsonProxies();
        rejectsUnsupportedOptions();
        uriVlessReality();
        uriTrojanSsVmess();
        uriHysteria2();
        base64UriList();
        runtimeConfigModes();
        runtimeConfigDirect();
        System.out.println("ProxyConfig self-test passed (" + checks + " checks)");
    }

    /** The real payload shape: Clash YAML with a VLESS + WS + TLS node. */
    private static void clashYamlVlessWsTls() throws Exception {
        String yaml = String.join("\n",
                "mixed-port: 7890",
                "allow-lan: false",
                "proxies:",
                "  - name: \"东京 01\"",
                "    type: vless",
                "    server: a.example.com",
                "    port: 443",
                "    uuid: 11111111-2222-3333-4444-555555555555",
                "    tls: true",
                "    servername: cdn.example.com",
                "    client-fingerprint: chrome",
                "    network: ws",
                "    ws-opts:",
                "      path: /ray?ed=2048",
                "      headers:",
                "        Host: cdn.example.com",
                "    udp: true",
                "proxy-groups:",
                "  - name: PROXY",
                "    type: select",
                "rules:",
                "  - DOMAIN-SUFFIX,cn,DIRECT",
                "");
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(yaml);
        check(entries.size() == 1, "yaml: one proxy, got " + entries.size());
        ProxyConfig.Entry e = entries.get(0);
        check("东京 01".equals(e.name), "yaml: name kept, got " + e.name);
        check(e.usable(), "yaml: usable, reason=" + e.rejectReason);
        check("a.example.com".equals(e.server) && e.port == 443, "yaml: server/port");

        JSONObject out = new JSONObject(e.outboundJson);
        check("vless".equals(out.getString("type")), "outbound type");
        check("a.example.com".equals(out.getString("server")), "outbound server");
        check(443 == out.getInt("server_port"), "outbound port");
        check("11111111-2222-3333-4444-555555555555".equals(out.getString("uuid")), "uuid persisted");

        JSONObject tls = out.getJSONObject("tls");
        check(tls.getBoolean("enabled"), "tls enabled");
        check("cdn.example.com".equals(tls.getString("server_name")), "sni");
        check("chrome".equals(tls.getJSONObject("utls").getString("fingerprint")), "utls fingerprint");

        JSONObject transport = out.getJSONObject("transport");
        check("ws".equals(transport.getString("type")), "ws transport");
        check("/ray".equals(transport.getString("path")), "ws path stripped of ?ed=");
        check("cdn.example.com".equals(transport.getJSONObject("headers").getString("Host")), "ws Host header");
        check(2048 == transport.getInt("max_early_data"), "ws early data");
    }

    private static void clashJsonProxies() throws Exception {
        String json = "{\"proxies\":[{\"name\":\"ss-1\",\"type\":\"shadowsocks\",\"server\":\"1.2.3.4\","
                + "\"port\":8388,\"cipher\":\"aes-128-gcm\",\"password\":\"p@ss:word\"},"
                + "{\"name\":\"hy2-1\",\"type\":\"hysteria2\",\"server\":\"h.example.com\",\"port\":8443,"
                + "\"password\":\"secret\",\"sni\":\"h.example.com\",\"skip-cert-verify\":true,"
                + "\"obfs\":\"salamander\",\"obfs-password\":\"ob\"}]}";
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(json);
        check(entries.size() == 2, "json clash: two entries, got " + entries.size());

        JSONObject ss = new JSONObject(entries.get(0).outboundJson);
        check("shadowsocks".equals(ss.getString("type")), "ss type");
        check("aes-128-gcm".equals(ss.getString("method")), "ss method");
        check("p@ss:word".equals(ss.getString("password")), "ss password with colon kept");

        JSONObject hy = new JSONObject(entries.get(1).outboundJson);
        check("hysteria2".equals(hy.getString("type")), "hy2 type");
        check(hy.getJSONObject("tls").getBoolean("insecure"), "hy2 skip-cert-verify");
        check("salamander".equals(hy.getJSONObject("obfs").getString("type")), "hy2 obfs");
    }

    private static void rejectsUnsupportedOptions() throws Exception {
        String yaml = String.join("\n",
                "proxies:",
                "  - {name: plug, type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: x, plugin: obfs}",
                "  - {name: kcp, type: vless, server: 1.1.1.1, port: 443, uuid: u, network: kcp}",
                "  - {name: nope, type: socks5, server: 1.1.1.1, port: 1080}",
                "  - {name: ok, type: trojan, server: 1.1.1.1, port: 443, password: pw}",
                "");
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(yaml);
        check(entries.size() == 4, "reject: four entries, got " + entries.size());
        check(!entries.get(0).usable() && entries.get(0).rejectReason.contains("插件"),
                "ss plugin rejected: " + entries.get(0).rejectReason);
        check(!entries.get(1).usable() && entries.get(1).rejectReason.contains("传输"),
                "kcp rejected: " + entries.get(1).rejectReason);
        check(!entries.get(2).usable() && entries.get(2).rejectReason.contains("协议"),
                "socks5 rejected: " + entries.get(2).rejectReason);
        check(entries.get(3).usable(), "valid trojan still usable");
        check(new JSONObject(entries.get(3).outboundJson).getJSONObject("tls").getBoolean("enabled"),
                "trojan implies tls");
    }

    private static void uriVlessReality() throws Exception {
        String uri = "vless://11111111-2222-3333-4444-555555555555@r.example.com:443"
                + "?encryption=none&security=reality&sni=www.example.com&fp=chrome"
                + "&pbk=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ&sid=abcd&type=tcp&flow=xtls-rprx-vision"
                + "#%E9%A6%99%E6%B8%AF%2001";
        List<ProxyConfig.Entry> entries = ProxyConfig.parseUris(uri);
        check(entries.size() == 1, "vless uri: one entry");
        ProxyConfig.Entry e = entries.get(0);
        check("香港 01".equals(e.name), "vless uri: decoded name, got " + e.name);
        JSONObject out = new JSONObject(e.outboundJson);
        check("xtls-rprx-vision".equals(out.getString("flow")), "reality flow");
        JSONObject reality = out.getJSONObject("tls").getJSONObject("reality");
        check(reality.getBoolean("enabled"), "reality enabled");
        check("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ".equals(reality.getString("public_key")), "pbk");
        check("abcd".equals(reality.getString("short_id")), "sid");
        check("www.example.com".equals(out.getJSONObject("tls").getString("server_name")), "reality sni");
    }

    private static void uriTrojanSsVmess() throws Exception {
        // Standard SS URI: base64 of method:password@host:port
        String ss = "ss://YWVzLTEyOC1nY206cGFzcw==@1.2.3.4:8388#ss-node";
        List<ProxyConfig.Entry> ssEntries = ProxyConfig.parseUris(ss);
        check(ssEntries.size() == 1, "ss uri: one entry");
        JSONObject ssOut = new JSONObject(ssEntries.get(0).outboundJson);
        check("aes-128-gcm".equals(ssOut.getString("method")), "ss uri method, got " + ssOut.optString("method"));
        check("pass".equals(ssOut.getString("password")), "ss uri password");
        check("1.2.3.4".equals(ssOut.getString("server")) && 8388 == ssOut.getInt("server_port"), "ss uri host");

        String trojan = "trojan://my%40password@t.example.com:8443?sni=t.example.com&type=ws&path=%2Fws&host=t.example.com#tj";
        List<ProxyConfig.Entry> tjEntries = ProxyConfig.parseUris(trojan);
        ProxyConfig.Entry tj = tjEntries.get(0);
        check(tj.usable(), "trojan ws usable: " + tj.rejectReason);
        JSONObject tjOut = new JSONObject(tj.outboundJson);
        check("my@password".equals(tjOut.getString("password")), "trojan password decoded");
        check("/ws".equals(tjOut.getJSONObject("transport").getString("path")), "trojan ws path");
        check(tjOut.getJSONObject("tls").getBoolean("enabled"), "trojan tls");

        String vmessJson = "{\"v\":\"2\",\"ps\":\"vm-1\",\"add\":\"v.example.com\",\"port\":\"443\","
                + "\"id\":\"11111111-2222-3333-4444-555555555555\",\"aid\":\"0\",\"scy\":\"auto\","
                + "\"net\":\"ws\",\"host\":\"v.example.com\",\"path\":\"/vm\",\"tls\":\"tls\",\"sni\":\"v.example.com\"}";
        String vmess = "vmess://" + java.util.Base64.getEncoder().encodeToString(
                vmessJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<ProxyConfig.Entry> vmEntries = ProxyConfig.parseUris(vmess);
        ProxyConfig.Entry vm = vmEntries.get(0);
        check(vm.usable(), "vmess usable: " + vm.rejectReason);
        JSONObject vmOut = new JSONObject(vm.outboundJson);
        check("vm-1".equals(vm.name), "vmess ps name, got " + vm.name);
        check("v.example.com".equals(vmOut.getString("server")), "vmess server");
        check("ws".equals(vmOut.getJSONObject("transport").getString("type")), "vmess ws transport");
        check(vmOut.getJSONObject("tls").getBoolean("enabled"), "vmess tls");
        check("auto".equals(vmOut.getString("security")), "vmess security");
    }

    private static void uriHysteria2() throws Exception {
        String uri = "hy2://password123@h.example.com:443?sni=h.example.com&obfs=salamander"
                + "&obfs-password=obfspw&insecure=1#hy2-node";
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(uri);
        check(entries.size() == 1, "hy2 uri: one entry");
        JSONObject out = new JSONObject(entries.get(0).outboundJson);
        check("hysteria2".equals(out.getString("type")), "hy2 type, got " + out.optString("type"));
        check("password123".equals(out.getString("password")), "hy2 password");
        check("salamander".equals(out.getJSONObject("obfs").getString("type")), "hy2 obfs type");
        check(out.getJSONObject("tls").getBoolean("insecure"), "hy2 insecure");
        check(out.getJSONObject("tls").getBoolean("enabled"), "hy2 always tls");
    }

    private static void base64UriList() throws Exception {
        String list = "vless://uuid-1@a.example.com:443?security=tls&sni=a.example.com#A\n"
                + "trojan://pw@b.example.com:443#B\n";
        String b64 = java.util.Base64.getEncoder().encodeToString(
                list.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(b64);
        check(entries.size() == 2, "base64 list: two entries, got " + entries.size());
        check(entries.get(0).usable() && "A".equals(entries.get(0).name), "base64 list: first node");
        check(entries.get(1).usable() && "B".equals(entries.get(1).name), "base64 list: second node");
    }

    private static void runtimeConfigModes() throws Exception {
        String outbound = new JSONObject()
                .put("type", "trojan").put("server", "t.example.com").put("server_port", 443)
                .put("password", "pw")
                .put("tls", new JSONObject().put("enabled", true).put("server_name", "t.example.com"))
                .toString();

        for (String mode : new String[]{"Rule", "Global"}) {
            JSONObject root = new JSONObject(ProxyConfig.buildRuntimeConfig(outbound, mode, "s3cret"));
            JSONObject tun = root.getJSONArray("inbounds").getJSONObject(0);
            check("tun".equals(tun.getString("type")), mode + ": tun inbound");
            JSONArray address = tun.getJSONArray("address");
            check(address.length() == 2, mode + ": two tun addresses");
            check("172.19.0.1/30".equals(address.getString(0)), mode + ": tun ipv4");
            check("fdfe:dcba:9876::1/126".equals(address.getString(1)), mode + ": tun ipv6");
            check(1500 == tun.getInt("mtu"), mode + ": mtu");
            check("mixed".equals(tun.getString("stack")), mode + ": stack mixed");
            check(tun.getBoolean("auto_route"), mode + ": auto_route");

            JSONObject mixed = root.getJSONArray("inbounds").getJSONObject(1);
            check("127.0.0.1".equals(mixed.getString("listen")), mode + ": mixed listen");
            check(2080 == mixed.getInt("listen_port"), mode + ": mixed port");

            JSONObject clashApi = root.getJSONObject("experimental").getJSONObject("clash_api");
            check("127.0.0.1:9090".equals(clashApi.getString("external_controller")), mode + ": clash api");
            check("s3cret".equals(clashApi.getString("secret")), mode + ": clash secret");

            JSONArray servers = root.getJSONObject("dns").getJSONArray("servers");
            check("udp".equals(servers.getJSONObject(0).getString("type")), mode + ": dns typed udp");
            check("1.1.1.1".equals(servers.getJSONObject(0).getString("server")), mode + ": proxy dns address");
            check("proxy".equals(servers.getJSONObject(0).getString("detour")), mode + ": proxy dns detour");
            check("223.5.5.5".equals(servers.getJSONObject(1).getString("server")), mode + ": direct dns address");
            check(!servers.getJSONObject(1).has("detour"), mode + ": direct dns must omit empty direct detour");

            JSONObject route = root.getJSONObject("route");
            check("direct-dns".equals(route.getString("default_domain_resolver")), mode + ": no DNS loop");
            check("proxy".equals(route.getString("final")), mode + ": rule/global final is proxy");

            JSONArray outbounds = root.getJSONArray("outbounds");
            check("direct".equals(outbounds.getJSONObject(0).getString("tag")), mode + ": direct tag");
            check("proxy".equals(outbounds.getJSONObject(1).getString("tag")), mode + ": proxy tag");
            check("trojan".equals(outbounds.getJSONObject(1).getString("type")), mode + ": node outbound kept");

            JSONArray rules = route.getJSONArray("rules");
            check(hasAction(rules, "hijack-dns"), mode + ": hijack-dns rule");
            check(hasAction(rules, "sniff"), mode + ": sniff rule");
            check(hasRoute(rules, "direct", "ip_is_private"), mode + ": private ip direct");
            boolean hasCn = hasRoute(rules, "direct", "domain_suffix");
            check(hasCn == "Rule".equals(mode), mode + ": .cn direct only in RULE mode");
        }
    }

    private static void runtimeConfigDirect() throws Exception {
        JSONObject root = new JSONObject(ProxyConfig.buildRuntimeConfig(null, "Direct", null));
        check("direct".equals(root.getJSONObject("route").getString("final")), "direct: final direct");
        check(root.getJSONArray("outbounds").length() == 1, "direct: no proxy outbound");
        check(root.getJSONObject("route").getJSONArray("rules").length() == 2, "direct: only sniff+hijack");
        check(root.getJSONObject("experimental").getJSONObject("clash_api").getString("secret").isEmpty(),
                "direct: empty secret allowed");

        boolean threw = false;
        try {
            ProxyConfig.buildRuntimeConfig(null, "Rule", null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "rule mode without outbound must be refused");
    }

    private static boolean hasAction(JSONArray rules, String action) {
        for (int i = 0; i < rules.length(); i++) {
            if (action.equals(rules.getJSONObject(i).optString("action"))) return true;
        }
        return false;
    }

    private static boolean hasRoute(JSONArray rules, String outbound, String matchField) {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if ("route".equals(rule.optString("action"))
                    && outbound.equals(rule.optString("outbound"))
                    && rule.has(matchField)) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            System.err.println("FAIL: " + label);
            System.exit(1);
        }
    }
}
