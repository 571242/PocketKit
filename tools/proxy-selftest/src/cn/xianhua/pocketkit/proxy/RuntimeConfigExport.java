package cn.xianhua.pocketkit.proxy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/**
 * Emits runtime configs for every mode and rule-set combination so a schema check can run outside
 * this test harness. Fixture outbound only: no subscription credentials are read or printed.
 */
public final class RuntimeConfigExport {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        String outbound = new JSONObject()
                .put("type", "trojan").put("server", "t.example.com").put("server_port", 443)
                .put("password", "fixture-only")
                .put("tls", new JSONObject().put("enabled", true).put("server_name", "t.example.com"))
                .toString();
        ProxyRules full = ProxyRules.parse("{\"schemaVersion\":1,\"rules\":{"
                + "\"direct\":{\"domain\":[\"12306.cn\",\"baidu.com\"],"
                + "\"domain_suffix\":[\"gov.cn\",\".cn\"],"
                + "\"ip_cidr\":[\"1.0.1.0/24\",\"240e::/20\",\"10.0.0.0/8\"]},"
                + "\"proxy\":{\"domain\":[\"openai.com\"],\"domain_suffix\":[\"google.com\",\"github.com\"]}}}");
        ProxyRules empty = ProxyRules.parse("{\"schemaVersion\":1,\"rules\":{"
                + "\"direct\":{\"domain\":[],\"domain_suffix\":[],\"ip_cidr\":[]},"
                + "\"proxy\":{\"domain\":[],\"domain_suffix\":[]}}}");
        String asset = new String(Files.readAllBytes(
                Paths.get(args[1], "app/src/main/assets/proxy-rules-default.json")), StandardCharsets.UTF_8);
        ProxyRules builtIn = ProxyRules.parse(asset);

        write(output, "rule-full.json", ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s", full));
        write(output, "rule-empty.json", ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s", empty));
        write(output, "rule-builtin.json", ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s", builtIn));
        write(output, "rule-legacy.json", ProxyConfig.buildRuntimeConfig(outbound, "Rule", "s"));
        write(output, "global-full.json", ProxyConfig.buildRuntimeConfig(outbound, "Global", "s", full));
        write(output, "direct-full.json", ProxyConfig.buildRuntimeConfig(null, "Direct", null, full));
        write(output, "direct-legacy.json", ProxyConfig.buildRuntimeConfig(null, "Direct", null));
        System.out.println("Exported runtime configs for schema validation");
    }

    private static void write(Path output, String name, String config) throws Exception {
        Files.write(output.resolve(name), config.getBytes(StandardCharsets.UTF_8));
    }
}
