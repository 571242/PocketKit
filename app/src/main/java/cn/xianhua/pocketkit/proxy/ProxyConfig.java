package cn.xianhua.pocketkit.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONArray;
import org.json.JSONException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Subscription payloads -> sing-box 1.14 outbound JSON, plus runtime config assembly.
 *
 * <p>Pure Java (SnakeYAML + org.json) so tools/proxy-selftest can run off-device.
 * Config objects are built as plain {@code Map}/{@code List} and serialized once, because Android's
 * org.json throws checked exceptions on every mutation accessor.
 * Unsupported options are rejected with a readable reason instead of being silently dropped.
 */
public final class ProxyConfig {
    /** Types we can translate to a complete sing-box outbound. */
    private static final Set<String> TYPES =
            new HashSet<>(Arrays.asList("vless", "vmess", "trojan", "shadowsocks", "hysteria2"));

    private static final Set<String> FINGERPRINTS = new HashSet<>(Arrays.asList(
            "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
            "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk"));

    private static final Pattern CLASH_MARKER =
            Pattern.compile("(?m)^\\s*[\"']?(proxies|Proxy)[\"']?\\s*:");

    /** One parsed proxy: either a complete outbound JSON, or a rejection reason. */
    public static final class Entry {
        public final String name;
        public final String type;
        public final String server;
        public final int port;
        /** sing-box outbound JSON (no tag), null when rejected. */
        public final String outboundJson;
        /** Why this entry cannot be used, null when usable. */
        public final String rejectReason;

        Entry(String name, String type, String server, int port, String outboundJson, String rejectReason) {
            this.name = name;
            this.type = type;
            this.server = server;
            this.port = port;
            this.outboundJson = outboundJson;
            this.rejectReason = rejectReason;
        }

        public boolean usable() {
            return outboundJson != null;
        }
    }

    private ProxyConfig() {}

    // ---------------------------------------------------------------- entry points

    /** Auto-detect Clash JSON/YAML, base64-wrapped or plain URI list. */
    public static List<Entry> parse(String content) {
        List<Entry> out = new ArrayList<>();
        if (content == null) return out;
        String text = content.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (text.isEmpty()) return out;

        if (text.startsWith("{") || text.startsWith("[") || CLASH_MARKER.matcher(text).find()) {
            List<Entry> clash = parseClash(text);
            if (!clash.isEmpty()) return clash;
        }
        String decoded = decodeBase64Text(text);
        return parseUris(decoded != null ? decoded : text);
    }

    /** Clash / Clash.Meta "proxies" list via SnakeYAML SafeConstructor (JSON is valid YAML). */
    static List<Entry> parseClash(String text) {
        List<Entry> out = new ArrayList<>();
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(true);
            options.setCodePointLimit(9_000_000);
            root = new Yaml(new SafeConstructor(options)).load(text);
        } catch (Throwable t) {
            return out;
        }
        if (!(root instanceof Map)) return out;
        Map<?, ?> map = (Map<?, ?>) root;
        Object proxies = map.get("proxies");
        if (proxies == null) proxies = map.get("Proxy");
        if (!(proxies instanceof List)) return out;
        for (Object item : (List<?>) proxies) {
            if (item instanceof Map) out.add(entryFromClash((Map<?, ?>) item));
        }
        return out;
    }

    /** One proxy URI per line; unknown lines are ignored. */
    public static List<Entry> parseUris(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("[\\r\\n]+")) {
            String raw = line.trim();
            if (raw.isEmpty() || raw.startsWith("//")) continue;
            if (raw.indexOf("://") <= 0) continue;
            Entry entry = parseUri(raw);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    /** Never throws: failures come back as a rejected entry. */
    public static Entry parseUri(String raw) {
        if (raw == null) return null;
        String uri = raw.trim();
        String name = "";
        int hash = uri.indexOf('#');
        if (hash >= 0) {
            name = urlDecode(uri.substring(hash + 1)).trim();
            uri = uri.substring(0, hash);
        }
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd <= 0) return null;
        String scheme = uri.substring(0, schemeEnd).toLowerCase(Locale.ROOT);

        String query = "";
        String body = uri.substring(schemeEnd + 3);
        int q = body.indexOf('?');
        if (q >= 0) {
            query = body.substring(q + 1);
            body = body.substring(0, q);
        }
        body = body.trim();
        Map<String, String> params = parseQuery(query);
        String type = normalizeType(scheme);

        try {
            if ("vmess".equals(type)) return entryFromVmess(body, name, params);
            if (!TYPES.contains(type)) {
                return rejected(name, type, "", 0, "不支持的协议: " + scheme);
            }

            String user = null;
            String hostPart = body;
            int at = body.lastIndexOf('@');
            if (at >= 0) {
                user = urlDecode(body.substring(0, at));
                hostPart = body.substring(at + 1);
            }
            if ("shadowsocks".equals(type)) {
                if (at < 0) {
                    // Legacy form: ss://base64(method:password@host:port)
                    String decoded = decodeText(b64(body));
                    if (decoded == null) return rejected(name, type, "", 0, "ss 链接缺少可解析的凭据");
                    int inner = decoded.lastIndexOf('@');
                    if (inner < 0) return rejected(name, type, "", 0, "ss 链接缺少服务器地址");
                    user = decoded.substring(0, inner);
                    hostPart = decoded.substring(inner + 1);
                } else if (user.indexOf(':') < 0) {
                    // SIP002 form: userinfo is base64(method:password)
                    String decoded = decodeText(b64(user));
                    if (decoded != null && decoded.indexOf(':') > 0) user = decoded;
                }
            }
            String[] hostPort = splitHostPort(hostPart);
            String server = hostPort[0];
            int port = parseInt(hostPort[1], 0);
            if (name.isEmpty()) name = server.isEmpty() ? "未命名节点" : server + ":" + port;
            if (server.isEmpty() || port <= 0) return rejected(name, type, server, port, "缺少 server/port");

            Svc svc = svcFromUri(type, server, port, user == null ? "" : user, params);
            applyWsPathEd(svc);
            return new Entry(name, type, server, port, json(buildOutbound(svc)), null);
        } catch (Reject reject) {
            String[] hostPort = splitHostPort(body.substring(body.lastIndexOf('@') + 1));
            return rejected(name.isEmpty() ? type + " 节点" : name, type, hostPort[0],
                    parseInt(hostPort[1], 0), reject.getMessage());
        } catch (Exception e) {
            return rejected(name.isEmpty() ? type + " 节点" : name, type, "", 0, "链接无法解析");
        }
    }

    /** Clash and share links disagree on some names (ss/shadowsocks, hy2/hysteria2). */
    private static String normalizeType(String raw) {
        String type = lower(raw);
        if ("ss".equals(type) || "shadowsocks".equals(type)) return "shadowsocks";
        if ("hy2".equals(type) || "hysteria2".equals(type)) return "hysteria2";
        return type;
    }

    /**
     * Runtime config: TUN + mixed + clash-api + DNS split routing.
     *
     * @param outboundJson sing-box outbound JSON of the selected node (tag assigned here), null only for DIRECT
     * @param mode         "rule" / "global" / "direct" (any case)
     * @param secret       clash_api secret; null/empty disables it
     */
    public static String buildRuntimeConfig(String outboundJson, String mode, String secret) {
        return buildRuntimeConfig(outboundJson, mode, secret, null);
    }

    /**
     * Same as the three-argument form plus cloud routing rules. {@code rules == null} means "no cloud
     * rules": RULE mode then only splits on private IPs and .cn, exactly like before the rules feature.
     *
     * <p>RULE order: sniff, DNS hijack, private IP direct, exact proxy/direct hosts, proxy/direct
     * suffixes, CN CIDRs direct, final proxy. GLOBAL and DIRECT ignore the bundle entirely.
     */
    public static String buildRuntimeConfig(String outboundJson, String mode, String secret, ProxyRules rules) {
        String normalized = mode == null ? "" : mode.trim();
        boolean direct = "direct".equalsIgnoreCase(normalized);
        boolean global = "global".equalsIgnoreCase(normalized);
        String finalOut = direct ? "direct" : "proxy";

        List<Object> outbounds = new ArrayList<>();
        outbounds.add(map("type", "direct", "tag", "direct"));
        if (!direct) {
            if (outboundJson == null || outboundJson.trim().isEmpty()) {
                throw new IllegalArgumentException("当前模式需要有效节点，但未提供 outbound 配置");
            }
            Map<String, Object> proxy = new LinkedHashMap<>(readFlatJson(outboundJson));
            proxy.put("tag", "proxy");
            outbounds.add(proxy);
        }

        List<Object> servers = new ArrayList<>();
        if (!direct) {
            servers.add(dnsServer("proxy-dns", "1.1.1.1", "proxy"));
        }
        servers.add(dnsServer("direct-dns", "223.5.5.5", "direct"));

        boolean ruleMode = !direct && !global && rules != null;
        List<Object> routeRules = new ArrayList<>();
        routeRules.add(map("action", "sniff"));
        routeRules.add(map("protocol", "dns", "action", "hijack-dns"));
        if (!direct) {
            routeRules.add(map("ip_is_private", true, "action", "route", "outbound", "direct"));
            if (ruleMode) {
                // Exact hosts precede suffixes: preserve Chinese Apple service exceptions.
                // Within each specificity tier an explicit proxy match wins.
                addDomainRule(routeRules, rules.proxyDomain, Collections.emptyList(), "proxy");
                addDomainRule(routeRules, rules.directDomain, Collections.emptyList(), "direct");
                addDomainRule(routeRules, Collections.emptyList(), rules.proxySuffix, "proxy");
                List<String> directSuffix = new ArrayList<>(rules.directSuffix);
                directSuffix.add(".cn");
                addDomainRule(routeRules, Collections.emptyList(), directSuffix, "direct");
                if (!rules.directCidr.isEmpty()) {
                    // Domain-only connections need an IP for CN fallback, after explicit domain policy.
                    routeRules.add(map("action", "resolve", "server", "direct-dns", "strategy", "ipv4_only"));
                    routeRules.add(map("ip_cidr", new ArrayList<Object>(rules.directCidr),
                            "action", "route", "outbound", "direct"));
                }
            } else if (!global) {
                // No cloud rules yet: keep the original .cn-only split.
                routeRules.add(map("domain_suffix", list(".cn"), "action", "route", "outbound", "direct"));
            }
        }

        Map<String, Object> tun = new LinkedHashMap<>();
        tun.put("type", "tun");
        tun.put("tag", "tun-in");
        tun.put("address", list("172.19.0.1/30", "fdfe:dcba:9876::1/126"));
        tun.put("mtu", 1500);
        tun.put("stack", "mixed");
        tun.put("auto_route", true);
        tun.put("strict_route", false);

        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("type", "mixed");
        mixed.put("tag", "mixed-in");
        mixed.put("listen", "127.0.0.1");
        mixed.put("listen_port", 2080);

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("rules", routeRules);
        route.put("final", finalOut);
        route.put("auto_detect_interface", true);
        // 代理服务器域名靠国内 DNS 解析，避免经代理 DNS 造成的解析回环。
        route.put("default_domain_resolver", "direct-dns");

        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("servers", servers);
        dns.put("final", direct ? "direct-dns" : "proxy-dns");
        // Some node exits return AAAA records but cannot complete IPv6 TLS; preference is not a filter.
        dns.put("strategy", direct ? "prefer_ipv4" : "ipv4_only");
        if (ruleMode) {
            List<Object> dnsRules = new ArrayList<>();
            List<String> chinaSuffix = new ArrayList<>(rules.directSuffix);
            chinaSuffix.add(".cn");
            addDnsRule(dnsRules, rules.proxyDomain, Collections.emptyList(), "proxy-dns");
            addDnsRule(dnsRules, rules.directDomain, Collections.emptyList(), "direct-dns");
            addDnsRule(dnsRules, Collections.emptyList(), rules.proxySuffix, "proxy-dns");
            addDnsRule(dnsRules, Collections.emptyList(), chinaSuffix, "direct-dns");
            if (!dnsRules.isEmpty()) dns.put("rules", dnsRules);
        } else if (!direct && !global) {
            dns.put("rules", list(map("domain_suffix", list(".cn"), "action", "route", "server", "direct-dns")));
        }

        Map<String, Object> clashApi = new LinkedHashMap<>();
        clashApi.put("external_controller", "127.0.0.1:9090");
        clashApi.put("secret", secret == null ? "" : secret);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("log", map("level", "warn", "timestamp", true));
        root.put("dns", dns);
        root.put("inbounds", list(tun, mixed));
        root.put("outbounds", outbounds);
        root.put("route", route);
        root.put("experimental", map("clash_api", clashApi));
        return json(root);
    }

    private static Map<String, Object> dnsServer(String tag, String address, String detour) {
        Map<String, Object> server = new LinkedHashMap<>();
        // Proxy nodes may support TCP only; DNS must not require UDP relay support.
        server.put("type", "proxy".equals(detour) ? "tcp" : "udp");
        server.put("tag", tag);
        server.put("server", address);
        // A bare direct outbound cannot be used as a DNS detour in sing-box 1.14.
        if (!"direct".equals(detour)) server.put("detour", detour);
        return server;
    }

    /** One route rule per destination; empty categories emit nothing at all. */
    private static void addDomainRule(List<Object> targets, List<String> domains, List<String> suffixes,
                                      String outbound) {
        Map<String, Object> rule = new LinkedHashMap<>();
        if (!domains.isEmpty()) rule.put("domain", new ArrayList<Object>(domains));
        if (!suffixes.isEmpty()) rule.put("domain_suffix", new ArrayList<Object>(suffixes));
        if (rule.isEmpty()) return;
        rule.put("action", "route");
        rule.put("outbound", outbound);
        targets.add(rule);
    }

    private static void addDnsRule(List<Object> targets, List<String> domains, List<String> suffixes,
                                   String server) {
        Map<String, Object> rule = new LinkedHashMap<>();
        if (!domains.isEmpty()) rule.put("domain", new ArrayList<Object>(domains));
        if (!suffixes.isEmpty()) rule.put("domain_suffix", new ArrayList<Object>(suffixes));
        if (rule.isEmpty()) return;
        rule.put("action", "route");
        rule.put("server", server);
        targets.add(rule);
    }

    // ---------------------------------------------------------------- clash → outbound

    private static Entry entryFromClash(Map<?, ?> proxy) {
        String type = normalizeType(str(proxy.get("type")));
        String server = str(proxy.get("server"));
        int port = parseInt(str(proxy.get("port")), 0);
        String name = str(proxy.get("name"));
        if (name.isEmpty()) name = server.isEmpty() ? "未命名节点" : server + ":" + port;
        try {
            if (server.isEmpty() || port <= 0) throw new Reject("缺少 server/port");
            Svc svc = svcFromClash(type, server, port, proxy);
            return new Entry(name, type, server, port, json(buildOutbound(svc)), null);
        } catch (Reject reject) {
            return new Entry(name, type, server, port, null, reject.getMessage());
        }
    }

    private static Svc svcFromClash(String type, String server, int port, Map<?, ?> p) throws Reject {
        Svc s = new Svc(type, server, port);
        s.tls = bool(p.get("tls")) || p.get("reality-opts") != null;
        s.sni = firstNonEmpty(str(p.get("servername")), str(p.get("sni")), str(p.get("peer")));
        s.insecure = bool(p.get("skip-cert-verify"));
        s.fingerprint = firstNonEmpty(str(p.get("client-fingerprint")), str(p.get("fingerprint")));
        s.alpn = listOf(p.get("alpn"));

        Map<?, ?> reality = mapOf(p.get("reality-opts"));
        if (reality != null) {
            s.reality = true;
            s.publicKey = str(reality.get("public-key"));
            s.shortId = str(reality.get("short-id"));
        }

        String network = lower(str(p.get("network")));
        s.net = network.isEmpty() ? "tcp" : network;
        Map<?, ?> ws = mapOf(p.get("ws-opts"));
        if (ws != null) {
            s.path = str(ws.get("path"));
            Map<?, ?> headers = mapOf(ws.get("headers"));
            if (headers != null) s.host = str(headers.get("Host"));
            s.maxEarlyData = parseInt(str(ws.get("max-early-data")), 0);
            s.earlyDataHeader = str(ws.get("early-data-header-name"));
        }
        if (s.path == null || s.path.isEmpty()) s.path = str(p.get("ws-path"));
        Map<?, ?> wsHeaders = mapOf(p.get("ws-headers"));
        if (wsHeaders != null && (s.host == null || s.host.isEmpty())) s.host = str(wsHeaders.get("Host"));

        Map<?, ?> grpc = mapOf(p.get("grpc-opts"));
        if (grpc != null) s.serviceName = str(grpc.get("grpc-service-name"));
        Map<?, ?> h2 = mapOf(p.get("h2-opts"));
        if (h2 != null) {
            s.path = firstNonEmpty(str(h2.get("path")), s.path);
            s.host = firstNonEmpty(listOf(h2.get("host")), s.host);
        }

        switch (type) {
            case "vless":
                s.uuid = str(p.get("uuid"));
                s.flow = str(p.get("flow"));
                break;
            case "vmess":
                s.uuid = str(p.get("uuid"));
                s.alterId = parseInt(firstNonEmpty(str(p.get("alterId")), str(p.get("aid"))), 0);
                s.method = firstNonEmpty(str(p.get("cipher")), str(p.get("security")));
                break;
            case "trojan":
                s.password = str(p.get("password"));
                s.tls = true;
                break;
            case "shadowsocks":
                s.method = firstNonEmpty(str(p.get("cipher")), str(p.get("method")));
                s.password = str(p.get("password"));
                if (!str(p.get("plugin")).isEmpty()) {
                    throw new Reject("ss 插件不受支持: " + str(p.get("plugin")));
                }
                break;
            case "hysteria2":
                s.password = firstNonEmpty(str(p.get("password")), str(p.get("auth")));
                s.obfs = str(p.get("obfs"));
                s.obfsPassword = str(p.get("obfs-password"));
                s.upMbps = parseInt(str(p.get("up")), 0);
                s.downMbps = parseInt(str(p.get("down")), 0);
                break;
            default:
                throw new Reject("不支持的协议: " + (type.isEmpty() ? "(缺少 type)" : type));
        }
        applyWsPathEd(s);
        return s;
    }

    // ---------------------------------------------------------------- uri → outbound

    private static Svc svcFromUri(String type, String server, int port, String user, Map<String, String> q)
            throws Reject {
        Svc s = new Svc(type, server, port);
        String security = lower(param(q, "security"));
        s.net = firstNonEmpty(lower(param(q, "type")), lower(param(q, "network")), "tcp");
        if ("none".equals(s.net)) s.net = "tcp";
        String sni = firstNonEmpty(param(q, "sni"), param(q, "peer"), param(q, "servername"));
        s.insecure = truthy(param(q, "allowinsecure")) || truthy(param(q, "insecure"))
                || truthy(param(q, "skip-cert-verify"));
        s.fingerprint = firstNonEmpty(param(q, "fp"), param(q, "fingerprint"));
        s.alpn = param(q, "alpn");
        s.path = param(q, "path");
        s.host = param(q, "host");
        s.serviceName = param(q, "servicename");

        switch (type) {
            case "vless":
                s.uuid = user;
                s.flow = param(q, "flow");
                s.publicKey = param(q, "pbk");
                s.shortId = param(q, "sid");
                s.reality = "reality".equals(security);
                s.tls = s.reality || "tls".equals(security);
                if ("xtls".equals(security)) throw new Reject("不支持的传输安全: xtls");
                String encryption = lower(param(q, "encryption"));
                if (!encryption.isEmpty() && !"none".equals(encryption)) {
                    throw new Reject("不支持的 VLESS encryption: " + encryption);
                }
                if (!param(q, "headertype").isEmpty() && !"none".equals(lower(param(q, "headertype")))) {
                    throw new Reject("不支持的 VLESS headerType: " + param(q, "headertype"));
                }
                break;
            case "vmess":
                s.net = "tcp";  // vmess:// keeps transport inside the base64 JSON
                break;
            case "trojan":
                s.password = user;
                s.reality = "reality".equals(security);
                s.tls = s.reality || !"none".equals(security);
                s.publicKey = param(q, "pbk");
                s.shortId = param(q, "sid");
                break;
            case "shadowsocks": {
                String plugin = param(q, "plugin");
                if (!plugin.isEmpty()) throw new Reject("ss 插件不受支持: " + plugin);
                int split = user.indexOf(':');
                if (split > 0) {
                    s.method = user.substring(0, split);
                    s.password = user.substring(split + 1);
                } else {
                    throw new Reject("ss 链接缺少 method:password");
                }
                break;
            }
            case "hysteria2": {
                String auth = user.isEmpty() ? param(q, "auth") : user;
                int colon = auth.indexOf(':');
                s.password = colon >= 0 ? auth.substring(colon + 1) : auth;
                s.obfs = param(q, "obfs");
                s.obfsPassword = firstNonEmpty(param(q, "obfs-password"), param(q, "obfs_password"));
                s.upMbps = parseInt(firstNonEmpty(param(q, "upmbps"), param(q, "up")), 0);
                s.downMbps = parseInt(firstNonEmpty(param(q, "downmbps"), param(q, "down")), 0);
                if (!param(q, "pinsha256").isEmpty() || !param(q, "pin").isEmpty()) {
                    throw new Reject("不支持证书固定 (pinSHA256)");
                }
                break;
            }
            default:
                throw new Reject("不支持的协议: " + type);
        }
        if (s.sni == null || s.sni.isEmpty()) s.sni = sni;
        return s;
    }

    private static Entry entryFromVmess(String body, String name, Map<String, String> params) throws Reject {
        String json = decodeText(b64(body));
        if (json == null) {
            return rejected(name.isEmpty() ? "VMess 节点" : name, "vmess", "", 0, "vmess 链接不是有效的 base64");
        }
        Map<?, ?> v;
        try {
            Object parsed = readJson(json);
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("not an object");
            v = (Map<?, ?>) parsed;
        } catch (Exception e) {
            return rejected(name.isEmpty() ? "VMess 节点" : name, "vmess", "", 0, "vmess 链接内容不是 JSON");
        }
        String server = str(v.get("add"));
        int port = parseInt(str(v.get("port")), 0);
        String nodeName = name.isEmpty() ? str(v.get("ps")) : name;
        if (nodeName.isEmpty()) nodeName = server.isEmpty() ? "VMess 节点" : server + ":" + port;
        try {
            if (server.isEmpty() || port <= 0) throw new Reject("缺少 server/port");
            Svc s = new Svc("vmess", server, port);
            s.uuid = str(v.get("id"));
            s.alterId = parseInt(str(v.get("aid")), 0);
            s.method = str(v.get("scy"));
            String net = lower(str(v.get("net")));
            s.net = net.isEmpty() ? "tcp" : net;
            s.path = str(v.get("path"));
            s.host = str(v.get("host"));
            s.serviceName = firstNonEmpty(str(v.get("path")), s.host);
            s.tls = "tls".equals(lower(str(v.get("tls"))));
            s.sni = str(v.get("sni"));
            s.alpn = str(v.get("alpn"));
            s.fingerprint = str(v.get("fp"));
            applyWsPathEd(s);
            return new Entry(nodeName, "vmess", server, port, json(buildOutbound(s)), null);
        } catch (Reject reject) {
            return new Entry(nodeName, "vmess", server, port, null, reject.getMessage());
        }
    }


    // ---------------------------------------------------------------- outbound assembly

    private static Map<String, Object> buildOutbound(Svc s) throws Reject {
        if (!TYPES.contains(s.type)) throw new Reject("不支持的协议: " + s.type);
        if (s.server == null || s.server.isEmpty() || s.port <= 0) throw new Reject("缺少 server/port");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", s.type);
        out.put("server", s.server);
        out.put("server_port", s.port);

        switch (s.type) {
            case "vless":
                require(s.uuid, "缺少 uuid");
                out.put("uuid", s.uuid);
                if (s.flow != null && !s.flow.isEmpty()) {
                    if (!"xtls-rprx-vision".equals(s.flow)) throw new Reject("不支持的 VLESS flow: " + s.flow);
                    out.put("flow", s.flow);
                }
                break;
            case "vmess": {
                require(s.uuid, "缺少 uuid");
                String security = (s.method == null || s.method.isEmpty()) ? "auto" : s.method.toLowerCase(Locale.ROOT);
                if (!Arrays.asList("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305").contains(security)) {
                    throw new Reject("不支持的 VMess 加密: " + s.method);
                }
                out.put("uuid", s.uuid);
                out.put("security", security);
                if (s.alterId > 0) out.put("alter_id", s.alterId);
                break;
            }
            case "trojan":
                require(s.password, "缺少 password");
                out.put("password", s.password);
                break;
            case "shadowsocks":
                require(s.method, "缺少加密方式");
                require(s.password, "缺少 password");
                out.put("method", s.method);
                out.put("password", s.password);
                break;
            case "hysteria2":
                require(s.password, "缺少 password");
                out.put("password", s.password);
                if (s.obfs != null && !s.obfs.isEmpty()) {
                    if (!"salamander".equals(lower(s.obfs))) throw new Reject("不支持的 hysteria2 obfs: " + s.obfs);
                    if (s.obfsPassword == null || s.obfsPassword.isEmpty()) throw new Reject("hysteria2 obfs 缺少密码");
                    out.put("obfs", map("type", "salamander", "password", s.obfsPassword));
                }
                if (s.upMbps > 0) out.put("up_mbps", s.upMbps);
                if (s.downMbps > 0) out.put("down_mbps", s.downMbps);
                break;
            default:
                throw new Reject("不支持的协议: " + s.type);
        }

        if ("hysteria2".equals(s.type) || s.tls || s.reality) out.put("tls", buildTls(s));
        if (s.net != null && !s.net.isEmpty() && !"tcp".equals(s.net)) out.put("transport", buildTransport(s));
        return out;
    }

    private static Map<String, Object> buildTls(Svc s) throws Reject {
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("enabled", true);
        if (s.sni != null && !s.sni.isEmpty()) tls.put("server_name", s.sni);
        if (s.insecure) tls.put("insecure", true);
        List<Object> alpn = csvList(s.alpn);
        if (!alpn.isEmpty()) tls.put("alpn", alpn);
        if (s.reality) {
            if (s.publicKey == null || s.publicKey.isEmpty()) throw new Reject("Reality 缺少 public-key");
            tls.put("reality", map("enabled", true, "public_key", s.publicKey,
                    "short_id", s.shortId == null ? "" : s.shortId));
            tls.put("utls", utls(firstNonEmpty(s.fingerprint, "chrome")));
        } else if (s.fingerprint != null && !s.fingerprint.isEmpty()) {
            tls.put("utls", utls(s.fingerprint));
        }
        return tls;
    }

    private static Map<String, Object> utls(String fingerprint) throws Reject {
        String fp = lower(fingerprint);
        if (!FINGERPRINTS.contains(fp)) throw new Reject("不支持的 uTLS 指纹: " + fingerprint);
        return map("enabled", true, "fingerprint", fp);
    }

    private static Map<String, Object> buildTransport(Svc s) throws Reject {
        String net = lower(s.net);
        Map<String, Object> transport = new LinkedHashMap<>();
        switch (net) {
            case "ws":
                transport.put("type", "ws");
                transport.put("path", nonEmpty(s.path, "/"));
                if (s.host != null && !s.host.isEmpty()) transport.put("headers", map("Host", s.host));
                if (s.maxEarlyData > 0) {
                    transport.put("max_early_data", s.maxEarlyData);
                    transport.put("early_data_header_name",
                            nonEmpty(s.earlyDataHeader, "Sec-WebSocket-Protocol"));
                }
                return transport;
            case "grpc":
                transport.put("type", "grpc");
                if (s.serviceName != null && !s.serviceName.isEmpty()) {
                    transport.put("service_name", s.serviceName);
                }
                return transport;
            case "h2":
            case "http":
                transport.put("type", "http");
                transport.put("path", nonEmpty(s.path, "/"));
                List<Object> hosts = csvList(s.host);
                if (!hosts.isEmpty()) transport.put("host", hosts);
                return transport;
            case "httpupgrade":
                transport.put("type", "httpupgrade");
                transport.put("path", nonEmpty(s.path, "/"));
                if (s.host != null && !s.host.isEmpty()) transport.put("host", s.host);
                return transport;
            default:
                throw new Reject("不支持的传输网络: " + net);
        }
    }

    /** ws paths often carry "?ed=2048" early-data hints; sing-box takes them as separate fields. */
    private static void applyWsPathEd(Svc s) {
        if (s.path == null) return;
        int idx = s.path.indexOf("?ed=");
        if (idx <= 0) return;
        String rest = s.path.substring(idx + 4);
        int amp = rest.indexOf('&');
        if (amp >= 0) rest = rest.substring(0, amp);
        int ed = parseInt(rest, 0);
        if (ed > 0) s.maxEarlyData = ed;
        s.path = s.path.substring(0, idx);
    }

    // ---------------------------------------------------------------- JSON conversion

    static String json(Object value) {
        if (value instanceof Map) return new JSONObject((Map<?, ?>) value).toString();
        if (value instanceof List) return new JSONArray((List<?>) value).toString();
        throw new IllegalArgumentException("JSON root must be an object or array");
    }

    private static Object readJson(String text) {
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (tokener.nextClean() != 0) throw new JSONException("Trailing JSON data");
            return unwrapJson(value);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid node JSON", error);
        }
    }

    private static Object unwrapJson(Object value) throws JSONException {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Object> result = new LinkedHashMap<>();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, unwrapJson(object.get(key)));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) result.add(unwrapJson(array.get(i)));
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readFlatJson(String text) {
        Object parsed = readJson(text);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Node JSON must be an object");
        return (Map<String, Object>) parsed;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    // ---------------------------------------------------------------- helpers

    private static final class Svc {
        final String type;
        final String server;
        final int port;
        String uuid;
        String password;
        String method;
        String flow;
        String sni;
        String fingerprint;
        String alpn;
        String publicKey;
        String shortId;
        String net = "tcp";
        String path;
        String host;
        String serviceName;
        String obfs;
        String obfsPassword;
        String earlyDataHeader;
        int alterId;
        int maxEarlyData;
        int upMbps;
        int downMbps;
        boolean tls;
        boolean insecure;
        boolean reality;

        Svc(String type, String server, int port) {
            this.type = type;
            this.server = server;
            this.port = port;
        }
    }

    private static final class Reject extends Exception {
        Reject(String message) {
            super(message);
        }
    }

    private static Entry rejected(String name, String type, String server, int port, String reason) {
        return new Entry(name.isEmpty() ? "未命名节点" : name, type, server, port, null, reason);
    }

    private static void require(String value, String reason) throws Reject {
        if (value == null || value.isEmpty()) throw new Reject(reason);
    }

    static String[] splitHostPort(String authority) {
        String host = authority;
        String port = "";
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0) {
                port = close + 1 < host.length() && host.charAt(close + 1) == ':'
                        ? host.substring(close + 2) : "";
                host = host.substring(1, close);
            }
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
        }
        if (port.indexOf('/') >= 0) port = port.substring(0, port.indexOf('/'));
        return new String[]{host.trim(), port.trim()};
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            String k = urlDecode(key).trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && !map.containsKey(k)) map.put(k, urlDecode(value).trim());
        }
        return map;
    }

    private static String param(Map<String, String> query, String key) {
        String v = query.get(key);
        return v == null ? "" : v;
    }

    /** Percent-decoding that leaves '+' alone (base64 in userinfo would otherwise be mangled). */
    static String urlDecode(String value) {
        if (value == null || value.indexOf('%') < 0) return value == null ? "" : value;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static byte[] b64(String value) {
        if (value == null) return null;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-') sb.append('+');
            else if (c == '_') sb.append('/');
            else if (!Character.isWhitespace(c)) sb.append(c);
        }
        int mod = sb.length() % 4;
        for (int i = 0; i < (4 - mod) % 4; i++) sb.append('=');
        try {
            return Base64.getDecoder().decode(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String decodeText(byte[] data) {
        if (data == null || data.length == 0) return null;
        String text = new String(data, StandardCharsets.UTF_8).trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        return text.isEmpty() ? null : text;
    }

    /** Only decode when the payload really is a base64 blob wrapping a proxy list. */
    static String decodeBase64Text(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() < 16 || compact.indexOf(':') >= 0) return null;
        String decoded = decodeText(b64(compact));
        if (decoded == null) return null;
        return decoded.indexOf("://") >= 0 ? decoded : null;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        String text = lower(str(value));
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private static boolean truthy(String value) {
        String text = lower(value);
        return "1".equals(text) || "true".equals(text) || "yes".equals(text);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        String digits = value.trim();
        int end = 0;
        while (end < digits.length() && Character.isDigit(digits.charAt(end))) end++;
        if (end == 0) return fallback;
        try {
            return Integer.parseInt(digits.substring(0, end));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Map<?, ?> mapOf(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Clash values may be a string, a list, or null. */
    private static String listOf(Object value) {
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<?>) value) {
                if (sb.length() > 0) sb.append(',');
                sb.append(str(item));
            }
            return sb.toString();
        }
        return str(value);
    }

    private static List<Object> csvList(String csv) {
        List<Object> items = new ArrayList<>();
        if (csv == null) return items;
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) items.add(trimmed);
        }
        return items;
    }

}
