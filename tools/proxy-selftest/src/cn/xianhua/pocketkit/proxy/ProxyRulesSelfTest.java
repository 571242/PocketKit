package cn.xianhua.pocketkit.proxy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Off-device self-test for the cloud rule bundle: parser, hash, atomic persistence and the
 * route/DNS ordering it produces. Pure Java (org.json + Android's Context type only), so it runs on
 * a desktop JVM without a device. Run with tools/proxy-selftest/run.sh.
 */
public final class ProxyRulesSelfTest {
    private static int checks = 0;
    private static String root = ".";

    public static void main(String[] args) throws Exception {
        if (args.length > 0) root = args[0];
        parsesValidBundle();
        rejectsMalformedBundles();
        hashesAndVerifiesFiles();
        writesAtomically();
        validatesRulesUrl();
        upstreamStatus();
        ruleModeOrdering();
        globalAndDirectIgnoreRules();
        legacyThreeArgSignature();
        System.out.println("ProxyRules self-test passed (" + checks + " checks)");
    }

    private static final String VALID = "{\"schemaVersion\":1,\"rules\":{"
            + "\"direct\":{\"domain\":[\"Example.COM\"],\"domain_suffix\":[\".cn\",\"gov.cn\"],"
            + "\"ip_cidr\":[\"10.0.0.0/8\",\"fc00::/7\"]},"
            + "\"proxy\":{\"domain\":[\"openai.com\"],\"domain_suffix\":[\"google.com\"]}}}";

    private static void parsesValidBundle() throws Exception {
        ProxyRules rules = ProxyRules.parse(VALID);
        check(rules.directDomain.size() == 1, "direct domain count");
        check("example.com".equals(rules.directDomain.get(0)), "domain lowercased");
        check(rules.directSuffix.size() == 2, "suffix count");
        check(rules.directCidr.size() == 2, "cidr count");
        check("openai.com".equals(rules.proxyDomain.get(0)), "proxy domain kept");
        check("google.com".equals(rules.proxySuffix.get(0)), "proxy suffix kept");
        check(!rules.isEmpty() && rules.entryCount() == 7, "entry count, got " + rules.entryCount());
        acceptsCidrLiterals();

        // The packaged default must be parseable by exactly this parser.
        String asset = new String(Files.readAllBytes(
                Path.of(root, "app/src/main/assets/proxy-rules-default.json")), StandardCharsets.UTF_8);
        ProxyRules defaults = ProxyRules.parse(asset);
        check(defaults.directSuffix.size() > 20, "default has CN suffixes");
        check(defaults.directSuffix.contains("baidu.com"), "default covers www and other CN .com subdomains");
        check(defaults.directSuffix.contains("gov.cn"), "default has gov.cn suffix");
        check(defaults.directCidr.contains("192.168.0.0/16"), "default has private CIDR");
        check(ProxyRules.parse(VALID).entryCount() > 0, "valid bundle not empty");
    }

    /** CIDRs are validated as literals, so a hostname can never trigger a DNS lookup while parsing. */
    private static void acceptsCidrLiterals() throws Exception {
        for (String good : new String[]{"1.2.3.4/32", "0.0.0.0/0", "255.255.255.255/32",
                "2001:db8::/32", "::/0", "::1/128", "fe80::/10", "240e::/20",
                "2001:db8:0:0:0:0:0:1/128", "::ffff:192.168.0.0/112",
                // sing-box accepts a bare address; rejecting it would drop a legitimate bundle.
                "192.168.0.1", "2001:db8::1"}) {
            cidrAccepted(good, true);
        }
        for (String bad : new String[]{"1.2.3/24", "1.2.3.4.5/24", "256.1.1.1/24",
                "1.2.3.4/-1", "1.2.3.4/33", "2001:db8::/129", "2001:db8::1/129",
                "hostname.local/24", "hostname.local", "1.2.3.4/", "/24", "1.2.3.4/8/8", "",
                ":::1/128", "2001:db8::g/32", "2001:db8::1::2/64"}) {
            cidrAccepted(bad, false);
        }
        // Presentation does not decide validity: case and padding normalise instead of rejecting.
        cidrAccepted(" ::FFFF:192.168.0.0/112 ", true);
        String normalised = ProxyRules.parse("{\"schemaVersion\":1,\"rules\":{\"direct\":{\"ip_cidr\":"
                + "[\"::FFFF:192.168.0.0/112\"]},\"proxy\":{}}}").directCidr.get(0);
        check("::ffff:192.168.0.0/112".equals(normalised), "uppercase hex normalised, got " + normalised);
    }

    private static void cidrAccepted(String cidr, boolean expected) {
        String json = "{\"schemaVersion\":1,\"rules\":{\"direct\":{\"ip_cidr\":[\""
                + cidr + "\"]},\"proxy\":{}}}";
        boolean parsed;
        try {
            ProxyRules.parse(json);
            parsed = true;
        } catch (Exception rejected) {
            parsed = false;
        }
        check(parsed == expected, "cidr '" + cidr + "' accepted=" + parsed + ", expected " + expected);
    }

    private static void rejectsMalformedBundles() {
        rejects(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "wrong schemaVersion");
        rejects(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"nodes\":[]"), "extra root field");
        rejects(VALID.replace("\"proxy\":{", "\"proxy\":{\"ip_cidr\":[\"1.1.1.0/24\"],"),
                "proxy.ip_cidr is not in the contract");
        rejects(VALID.replace("\"rules\":{", "\"rules\":{\"block\":{\"domain\":[]},"), "unknown group");
        rejects(VALID.replace("\"domain\":[\"Example.COM\"]", "\"domain\":[\"bad domain\"]"), "space in domain");
        rejects(VALID.replace("\"domain\":[\"Example.COM\"]", "\"domain\":[\"*.example.com\"]"), "wildcard");
        rejects(VALID.replace("\"domain\":[\"Example.COM\"]", "\"domain\":[\"localhost\"]"), "single-label exact");
        rejects(VALID.replace("\"ip_cidr\":[\"10.0.0.0/8\",\"fc00::/7\"]", "\"ip_cidr\":[\"10.0.0.0/33\"]"),
                "ipv4 prefix too long");
        rejects(VALID.replace("\"ip_cidr\":[\"10.0.0.0/8\",\"fc00::/7\"]", "\"ip_cidr\":[\"example.com/8\"]"),
                "hostname instead of literal");
        rejects(VALID.replace("\"domain\":[\"Example.COM\"]", "\"domain\":\"example.com\""),
                "domain not an array");
        rejects("{}", "no rules group");
        rejects("not json", "not json");
        rejects("", "empty body");
        rejects(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), "string schema version");
        rejects(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":1.2"), "fractional schema version");
        rejects(" ".repeat(ProxyRules.MAX_BYTES) + VALID, "oversized otherwise valid JSON");
        rejects("{\"schemaVersion\":1,\"rules\":{\"direct\":{\"domain\":[\"a.com\""
                + ",\"a.com\"".repeat(ProxyRules.MAX_ENTRIES) + "]},\"proxy\":{}}}", "raw entry budget before deduplication");
    }

    private static void rejects(String json, String label) {
        try {
            ProxyRules.parse(json);
            check(false, "expected rejection: " + label);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void hashesAndVerifiesFiles() throws Exception {
        // Known vector: SHA-256("abc").
        check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                .equals(ProxyRules.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))), "sha256 known vector");
        check(ProxyRules.isSha256Hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                "accepts a lowercase hash");
        check(!ProxyRules.isSha256Hex("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"),
                "rejects uppercase hash");
        check(!ProxyRules.isSha256Hex("ba7816bf"), "rejects a short version");
        check(!ProxyRules.isSha256Hex(null), "rejects null version");

        // The hub sends the same hash twice; a disagreeing pair means an inconsistent payload.
        String h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        check(ProxyRules.versionsAgree(h, h), "matching version and sha accepted");
        check(ProxyRules.versionsAgree(h, ""), "rulesVersion alone is enough");
        check(ProxyRules.versionsAgree("", h), "rulesSha256 alone is enough");
        check(!ProxyRules.versionsAgree(h, "0".repeat(64)), "disagreeing pair rejected");
        check(!ProxyRules.versionsAgree("", ""), "both empty rejected");
        check(!ProxyRules.versionsAgree("v3", "v3"), "non-hash version rejected");

        File dir = Files.createTempDirectory("rules-test").toFile();
        File file = new File(dir, "rules.json");
        byte[] bytes = VALID.getBytes(StandardCharsets.UTF_8);
        String sha = ProxyRules.sha256Hex(bytes);
        check(ProxyRules.writeAtomic(file, bytes), "atomic write");
        check(file.length() == bytes.length, "file length matches");
        check(!new File(dir, "rules.json.tmp").exists(), "temp file cleaned up");

        ProxyRules verified = ProxyRules.verifyFile(file, sha);
        check(verified != null, "verified rules load");
        check(sha.equals(verified.sha256), "verified rules carry their hash");
        check(ProxyRules.verifyFile(file, "0".repeat(64)) == null, "wrong hash rejects the file");
        check(ProxyRules.verifyFile(file, "") == null, "missing hash rejects the file");
        check(ProxyRules.verifyFile(new File(dir, "absent.json"), sha) == null, "absent file");

        // A truncated/corrupted local file must look absent even when the version string matches.
        Files.write(file.toPath(), "{\"schemaVersion\":1".getBytes(StandardCharsets.UTF_8));
        check(ProxyRules.verifyFile(file, sha) == null, "corrupted file rejects even with matching version");

        // Overwriting a good file with different valid bytes changes the hash it reports.
        byte[] other = VALID.replace("\"gov.cn\"", "\"other.cn\"").getBytes(StandardCharsets.UTF_8);
        check(!java.util.Arrays.equals(bytes, other), "replacement fixture really differs");
        check(ProxyRules.writeAtomic(file, other), "second atomic write replaces the file");
        check(ProxyRules.verifyFile(file, sha) == null, "old hash no longer matches replaced file");
        check(ProxyRules.verifyFile(file, ProxyRules.sha256Hex(other)) != null, "new hash matches replaced file");
    }

    private static void writesAtomically() throws Exception {
        File dir = Files.createTempDirectory("rules-atomic").toFile();
        File nested = new File(new File(dir, "rules"), "rules.json");
        check(ProxyRules.writeAtomic(nested, "{}".getBytes(StandardCharsets.UTF_8)), "creates parent directory");
        check(nested.isFile(), "file exists after write");
        // Oversized payloads are refused before they ever reach a parser.
        byte[] tooBig = new byte[ProxyRules.MAX_BYTES + 1];
        check(ProxyRules.MAX_BYTES == 12 * 1024 * 1024, "byte cap is 12 MiB");
        check(ProxyRules.MAX_ENTRIES == 300_000, "entry cap is 300k");
        check(tooBig.length > ProxyRules.MAX_BYTES, "oversize fixture is larger than the cap");
        check(!ProxyRules.writeAtomic(nested, tooBig), "oversize write is refused");
        check("{}".equals(Files.readString(nested.toPath())), "failed write retains existing bytes");
        byte[] good = VALID.getBytes(StandardCharsets.UTF_8);
        String sha = ProxyRules.sha256Hex(good);
        File previous = new File(dir, sha + ".json");
        check(ProxyRules.writeAtomic(previous, good), "immutable old artifact created");
        byte[] next = VALID.replace("gov.cn", "other.cn").getBytes(StandardCharsets.UTF_8);
        File candidate = new File(dir, ProxyRules.sha256Hex(next) + ".json");
        check(new File(dir, candidate.getName() + ".tmp").mkdir(), "force candidate I/O failure");
        check(!ProxyRules.writeAtomic(candidate, next), "candidate write fails safely");
        check(ProxyRules.verifyFile(previous, sha) != null, "old hash-named artifact survives candidate failure");
    }

    private static void validatesRulesUrl() throws Exception {
        String hub = "https://hub.example.com/api/check-update";
        String ok = ProxyRulesStore.sameOriginRulesUrl(hub, "https://hub.example.com/rules/" + "a".repeat(64) + ".json");
        check(ok.contains("/rules/"), "same-origin rules url accepted");
        rejectsUrl(hub, "http://hub.example.com/rules/x.json", "plain http");
        rejectsUrl(hub, "https://evil.example.com/rules/x.json", "other host");
        rejectsUrl(hub, "https://hub.example.com:8443/rules/x.json", "other port");
        rejectsUrl(hub, "https://hub.example.com/other/x.json", "wrong path");
        rejectsUrl(hub, "https://user:pw@hub.example.com/rules/x.json", "embedded credentials");
        rejectsUrl("http://hub.example.com/api/check-update", "http://hub.example.com/rules/x.json",
                "http hub is not https");
        rejectsUrl(hub, "", "empty url");
        rejectsUrl(hub, "https://hub.example.com/rules/x.json", "non-hash filename");
        rejectsUrl(hub, "https://hub.example.com/rules/../api/check-update", "path traversal");
        rejectsUrl(hub, ok + "?file=other", "unexpected query");
        rejectsUrl(hub, ok + "#fragment", "unexpected fragment");
    }

    private static void rejectsUrl(String hub, String rules, String label) {
        try {
            ProxyRulesStore.sameOriginRulesUrl(hub, rules);
            check(false, "expected url rejection: " + label);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void upstreamStatus() {
        JSONObject payload = new JSONObject("{\"upstreamReleases\":{\"flclash\":{\"tag\":\"v0.9.2\"},"
                + "\"mihomo\":{\"tag\":\"v1.19.33\"},\"singbox\":{\"tag\":\"v1.14.4\"}}}");
        String summary = ProxyRulesStore.upstreamSummary(payload);
        check(summary.contains("FlClash v0.9.2"), "FlClash observed version visible");
        check(summary.contains("Mihomo v1.19.33"), "Mihomo observed version visible");
        check(summary.contains("sing-box v1.14.4"), "sing-box observed version visible");
        check(summary.contains("协议变更需升级 APK"), "observation never claims native hot install");
        JSONObject bad = new JSONObject().put("upstreamReleases", new JSONObject()
                .put("flclash", new JSONObject().put("tag", "v1\nunsafe")));
        check(ProxyRulesStore.upstreamSummary(bad).isEmpty(), "unsafe release text rejected");
    }

    private static void ruleModeOrdering() throws Exception {
        ProxyRules rules = ProxyRules.parse(VALID);
        String outbound = new JSONObject()
                .put("type", "trojan").put("server", "t.example.com").put("server_port", 443)
                .put("password", "pw").toString();
        JSONObject root = new JSONObject(ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s", rules));
        JSONArray route = root.getJSONObject("route").getJSONArray("rules");

        int sniff = indexOfAction(route, "sniff");
        int hijack = indexOfAction(route, "hijack-dns");
        int privateIp = indexOfRoute(route, "direct", "ip_is_private");
        int proxyDomains = indexOfRoute(route, "proxy", "domain");
        int exactDirect = indexOfRoute(route, "direct", "domain");
        int proxySuffix = indexOfRoute(route, "proxy", "domain_suffix");
        int chinaDomains = indexOfRoute(route, "direct", "domain_suffix");
        int cnCidr = indexOfRoute(route, "direct", "ip_cidr");
        check(sniff == 0, "sniff first, got " + sniff);
        check(hijack == 1, "dns hijack second, got " + hijack);
        check(privateIp == 2, "private ip third, got " + privateIp);
        check(proxyDomains == 3, "explicit proxy domains before China direct, got " + proxyDomains);
        check(exactDirect > proxyDomains, "proxy exact precedes direct exact");
        check(proxySuffix > exactDirect, "exact direct exceptions precede broad proxy suffixes");
        check(chinaDomains > proxySuffix, "proxy suffix precedes direct suffix");
        check(cnCidr > chinaDomains, "CN cidr after China domains");
        check("proxy".equals(root.getJSONObject("route").getString("final")), "final is proxy");

        JSONArray proxyRule = route.getJSONObject(proxyDomains).getJSONArray("domain");
        check("openai.com".equals(proxyRule.getString(0)), "proxy rule carries bundle domain");
        check(!route.getJSONObject(proxyDomains).has("domain_suffix"), "exact proxy rule has no broad suffix");
        check(route.getJSONObject(proxySuffix).getJSONArray("domain_suffix").length() == 1,
                "proxy suffix is a separate priority tier");
        JSONArray chinaSuffix = route.getJSONObject(chinaDomains).getJSONArray("domain_suffix");
        check(contains(chinaSuffix, ".cn"), "China suffix always includes .cn");
        check(contains(chinaSuffix, "gov.cn"), "China suffix keeps bundle suffixes");
        check(route.getJSONObject(cnCidr).getJSONArray("ip_cidr").length() == 2, "CN cidr list carried");

        JSONArray dnsRules = root.getJSONObject("dns").getJSONArray("rules");
        check(dnsRules.length() == 4, "DNS has matching four specificity tiers");
        check("proxy-dns".equals(dnsRules.getJSONObject(0).getString("server")), "DNS proxy exact first");
        check("openai.com".equals(dnsRules.getJSONObject(0).getJSONArray("domain").getString(0)),
                "DNS exact proxy domain preserved");
        check("direct-dns".equals(dnsRules.getJSONObject(1).getString("server")), "DNS direct exact second");
        check("proxy-dns".equals(dnsRules.getJSONObject(2).getString("server")), "DNS proxy suffix third");
        check(contains(dnsRules.getJSONObject(3).getJSONArray("domain_suffix"), ".cn"), "DNS direct suffix includes .cn");
        check("proxy-dns".equals(root.getJSONObject("dns").getString("final")), "DNS final stays proxy-dns");
        ProxyRules apple = ProxyRules.parse(VALID.replace("Example.COM", "apps.apple.com")
                .replace("google.com", "apple.com"));
        JSONObject appleConfig = new JSONObject(ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s", apple));
        JSONArray appleRoute = appleConfig.getJSONObject("route").getJSONArray("rules");
        JSONArray appleDns = appleConfig.getJSONObject("dns").getJSONArray("rules");
        check("direct".equals(domainDecision(appleRoute, "apps.apple.com", "outbound")), "Apple China exact exception is direct");
        check("proxy".equals(domainDecision(appleRoute, "music.apple.com", "outbound")), "other Apple hosts retain proxy suffix");
        check("direct-dns".equals(domainDecision(appleDns, "apps.apple.com", "server")), "Apple exception DNS also direct");
        check("proxy-dns".equals(domainDecision(appleDns, "music.apple.com", "server")), "other Apple DNS remains proxied");
    }

    private static void globalAndDirectIgnoreRules() throws Exception {
        ProxyRules rules = ProxyRules.parse(VALID);
        String outbound = new JSONObject()
                .put("type", "trojan").put("server", "t.example.com").put("server_port", 443)
                .put("password", "pw").toString();

        JSONObject global = new JSONObject(ProxyConfig.buildRuntimeConfig(outbound, "Global", "s", rules));
        JSONArray globalRules = global.getJSONObject("route").getJSONArray("rules");
        check(indexOfRoute(globalRules, "proxy", "domain") < 0, "global has no proxy-domain rule");
        check(indexOfRoute(globalRules, "direct", "domain_suffix") < 0, "global has no China rule");
        check(!global.getJSONObject("dns").has("rules"), "global has no dns rules");

        JSONObject direct = new JSONObject(ProxyConfig.buildRuntimeConfig(null, "Direct", null, rules));
        check(direct.getJSONObject("route").getJSONArray("rules").length() == 2,
                "direct keeps only sniff+hijack");
        check("direct".equals(direct.getJSONObject("route").getString("final")), "direct final direct");

        // An empty bundle still keeps the built-in .cn split, and never emits an empty rule object.
        JSONObject minimal = new JSONObject(ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s",
                ProxyRules.parse("{\"schemaVersion\":1,\"rules\":{"
                        + "\"direct\":{\"domain\":[],\"domain_suffix\":[],\"ip_cidr\":[]},"
                        + "\"proxy\":{\"domain\":[],\"domain_suffix\":[]}}}")));
        JSONArray minimalRules = minimal.getJSONObject("route").getJSONArray("rules");
        check(minimalRules.length() == 4, "empty bundle keeps sniff+hijack+private+.cn, got " + minimalRules.length());
        check(minimalRules.getJSONObject(2).has("ip_is_private"), "private ip rule survives");
        for (int i = 0; i < minimalRules.length(); i++) {
            JSONObject rule = minimalRules.getJSONObject(i);
            check(rule.has("action"), "route rule " + i + " carries an action");
            check(!(rule.has("domain") && rule.getJSONArray("domain").length() == 0), "no empty domain list");
            check(!(rule.has("ip_cidr") && rule.getJSONArray("ip_cidr").length() == 0), "no empty cidr list");
        }
        JSONArray minimalDns = minimal.getJSONObject("dns").getJSONArray("rules");
        check(minimalDns.length() == 1, "empty bundle keeps the .cn dns rule only, got " + minimalDns.length());
        check("direct-dns".equals(minimalDns.getJSONObject(0).getString("server")), "dns direct-dns rule");
    }

    private static void legacyThreeArgSignature() throws Exception {
        String outbound = new JSONObject()
                .put("type", "trojan").put("server", "t.example.com").put("server_port", 443)
                .put("password", "pw").toString();
        JSONObject root = new JSONObject(ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s"));
        JSONArray rules = root.getJSONObject("route").getJSONArray("rules");
        check(indexOfRoute(rules, "direct", "domain_suffix") >= 0, "three-arg form keeps .cn direct");
        check(rules.getJSONObject(indexOfRoute(rules, "direct", "domain_suffix"))
                .getJSONArray("domain_suffix").length() == 1, "three-arg form has only .cn");
        check(root.getJSONObject("dns").getJSONArray("rules").length() == 1, "three-arg dns rule preserved");
    }

    private static String domainDecision(JSONArray rules, String host, String target) {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            JSONArray exact = rule.optJSONArray("domain");
            boolean match = exact != null && contains(exact, host);
            JSONArray suffixes = rule.optJSONArray("domain_suffix");
            if (suffixes != null) for (int j = 0; j < suffixes.length(); j++) {
                String suffix = suffixes.getString(j).replaceFirst("^\\.", "");
                match |= host.equals(suffix) || host.endsWith("." + suffix);
            }
            if (match) return rule.getString(target);
        }
        return "<final>";
    }

    private static int indexOfAction(JSONArray rules, String action) {
        for (int i = 0; i < rules.length(); i++) {
            if (action.equals(rules.getJSONObject(i).optString("action"))) return i;
        }
        return -1;
    }

    private static int indexOfRoute(JSONArray rules, String outbound, String matchField) {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if ("route".equals(rule.optString("action"))
                    && outbound.equals(rule.optString("outbound"))
                    && rule.has(matchField)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) if (value.equals(array.optString(i))) return true;
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
