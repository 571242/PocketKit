package cn.xianhua.pocketkit.proxy;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Emits private test fixtures without printing subscription credentials. */
public final class ProxyConfigExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ProxyConfigExport input-subscription output-directory");
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        List<ProxyConfig.Entry> entries = ProxyConfig.parse(Files.readString(Paths.get(args[0]), StandardCharsets.UTF_8));
        Files.writeString(output.resolve("direct.json"), ProxyConfig.buildRuntimeConfig(null, "Direct", "test-only"), StandardCharsets.UTF_8);
        int count = 0;
        for (ProxyConfig.Entry entry : entries) {
            if (!entry.usable()) continue;
            for (String mode : new String[]{"Global", "Rule"}) {
                Files.writeString(output.resolve("node-" + count + "-" + mode.toLowerCase(Locale.ROOT) + ".json"),
                        ProxyConfig.buildRuntimeConfig(entry.outboundJson, mode, "test-only"), StandardCharsets.UTF_8);
            }
            count++;
        }
        if (count == 0) throw new AssertionError("No complete outbound found");
        System.out.println("Exported " + count + " complete outbounds for native-core validation; no credentials printed");
    }
}
