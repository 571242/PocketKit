package cn.xianhua.pocketkit.proxy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cloud routing rules: {@code {schemaVersion:1, rules:{direct:{...}, proxy:{...}}}}.
 *
 * <p>This payload is untrusted: unknown fields, unknown groups, malformed domains and malformed
 * CIDRs are rejected instead of being dropped, and the whole bundle is bounded in size and entry
 * count. Only four routing fields exist on purpose — the bundle can never carry nodes, code or an
 * arbitrary route/action. Pure Java (org.json + java.io) so tools/proxy-selftest runs off-device.
 */
public final class ProxyRules {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    public static final int MAX_ENTRIES = 300_000;

    private static final int MAX_DOMAIN_LENGTH = 253;
    /** Exact host: at least two labels, no wildcards. */
    private static final Pattern DOMAIN = Pattern.compile(
            "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");
    /** Suffix: same as a domain but a single label (a TLD) and an optional leading dot are allowed. */
    private static final Pattern SUFFIX = Pattern.compile(
            "\\.?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

    public final List<String> directDomain, directSuffix, directCidr, proxyDomain, proxySuffix;
    /** Lowercase hex SHA-256 of the exact bytes these rules were parsed from; "" for built-ins. */
    public String sha256 = "";

    private ProxyRules(List<String> directDomain, List<String> directSuffix, List<String> directCidr,
                       List<String> proxyDomain, List<String> proxySuffix) {
        this.directDomain = directDomain;
        this.directSuffix = directSuffix;
        this.directCidr = directCidr;
        this.proxyDomain = proxyDomain;
        this.proxySuffix = proxySuffix;
    }

    /**
     * Last-resort fallback used only if the packaged default asset cannot be read: private ranges,
     * which the runtime config already blocks before any rule runs. The real built-in list ships as
     * assets/proxy-rules-default.json and is read by {@link ProxyRulesStore#load()}.
     */
    public static ProxyRules fallback() {
        try {
            return parse("{\"schemaVersion\":1,\"rules\":{"
                    + "\"direct\":{\"ip_cidr\":[\"10.0.0.0/8\",\"172.16.0.0/12\",\"192.168.0.0/16\","
                    + "\"127.0.0.0/8\",\"169.254.0.0/16\",\"100.64.0.0/10\",\"fc00::/7\",\"fe80::/10\"]},"
                    + "\"proxy\":{\"domain\":[],\"domain_suffix\":[]}}}");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** @throws IOException with a user-safe reason (never echoes a URL or credentials). */
    public static ProxyRules parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) throw new IOException("规则内容为空");
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("规则体积超过上限");
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception error) {
            throw new IOException("规则不是合法 JSON");
        }
        rejectUnknown(root, "schemaVersion", "rules");
        Object schema = root.opt("schemaVersion");
        if (!(schema instanceof Number) || ((Number) schema).doubleValue() != SCHEMA_VERSION) {
            throw new IOException("不支持的规则 schemaVersion");
        }
        JSONObject groups = root.optJSONObject("rules");
        if (groups == null) throw new IOException("规则缺少 rules 分组");
        rejectUnknown(groups, "direct", "proxy");
        JSONObject direct = groups.optJSONObject("direct");
        JSONObject proxy = groups.optJSONObject("proxy");
        if (direct == null || proxy == null) throw new IOException("规则缺少 direct 或 proxy 分组");
        rejectUnknown(direct, "domain", "domain_suffix", "ip_cidr");
        // proxy.ip_cidr would silently send traffic out through the proxy; the contract has no such field.
        rejectUnknown(proxy, "domain", "domain_suffix");

        int[] budget = {MAX_ENTRIES};
        return new ProxyRules(
                domains(direct, "domain", budget, true),
                domains(direct, "domain_suffix", budget, false),
                cidrs(direct, "ip_cidr", budget),
                domains(proxy, "domain", budget, true),
                domains(proxy, "domain_suffix", budget, false));
    }

    public boolean isEmpty() {
        return directDomain.isEmpty() && directSuffix.isEmpty() && directCidr.isEmpty()
                && proxyDomain.isEmpty() && proxySuffix.isEmpty();
    }

    public int entryCount() {
        return directDomain.size() + directSuffix.size() + directCidr.size()
                + proxyDomain.size() + proxySuffix.size();
    }

    // ---------------------------------------------------------------- hashing / persistence

    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** The wire form of a rules version: 64 lowercase hex characters, nothing else. */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * The hub publishes the same hash twice (rulesVersion and rulesSha256). Either field alone is
     * accepted, but a pair that disagrees makes the payload inconsistent, so it is refused.
     */
    public static boolean versionsAgree(String rulesVersion, String rulesSha256) {
        String announced = rulesSha256 == null ? "" : rulesSha256.trim();
        String version = rulesVersion == null ? "" : rulesVersion.trim();
        String effective = announced.isEmpty() ? version : announced;
        if (!isSha256Hex(effective)) return false;
        return version.isEmpty() || announced.isEmpty() || version.equals(announced);
    }

    /**
     * Reads a persisted bundle and re-checks it against the expected hash. A bad local file reports
     * as absent, so a stale version string can never make broken rules look active.
     */
    public static ProxyRules verifyFile(File file, String sha256) {
        if (file == null || !isSha256Hex(sha256) || !file.isFile()) return null;
        if (file.length() <= 0 || file.length() > MAX_BYTES) return null;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (!sha256.equals(sha256Hex(data))) return null;
            ProxyRules rules = parse(new String(data, StandardCharsets.UTF_8));
            rules.sha256 = sha256;
            return rules;
        } catch (Exception error) {
            return null;
        }
    }

    /** Writes {@code data} through a sibling temp file so a crash never leaves a half-written bundle. */
    public static boolean writeAtomic(File file, byte[] data) {
        if (file == null || data == null || data.length == 0 || data.length > MAX_BYTES) return false;
        File dir = file.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        File temp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (Exception error) {
            temp.delete();
            return false;
        }
        try {
            Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception atomicFailed) {
            // Never weaken the crash-safety guarantee on an unsupported filesystem.
            temp.delete();
            return false;
        }
    }

    // ---------------------------------------------------------------- validation

    private static List<String> domains(JSONObject group, String key, int[] budget, boolean exact)
            throws IOException {
        Set<String> out = new LinkedHashSet<>();
        JSONArray array = array(group, key);
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (!(raw instanceof String)) throw new IOException(key + " 含非字符串条目");
            String value = ((String) raw).trim().toLowerCase(Locale.ROOT);
            boolean valid = value.length() <= MAX_DOMAIN_LENGTH
                    && (exact ? DOMAIN : SUFFIX).matcher(value).matches();
            if (!valid) throw new IOException(key + " 含非法域名: " + preview(value));
            spend(budget);
            out.add(value);
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    private static List<String> cidrs(JSONObject group, String key, int[] budget) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        JSONArray array = array(group, key);
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (!(raw instanceof String)) throw new IOException(key + " 含非字符串条目");
            // sing-box accepts uppercase hex and bare addresses; normalising keeps a legitimate
            // bundle from being rejected wholesale over presentation.
            String value = ((String) raw).trim().toLowerCase(Locale.ROOT);
            if (!isIpOrCidr(value)) throw new IOException(key + " 含非法网段: " + preview(value));
            spend(budget);
            out.add(value);
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    /** A bare address means a single-host range, which is what sing-box does with it too. */
    private static boolean isIpOrCidr(String value) {
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (address.isEmpty()) return false;
        if (slash >= 0 && slash != value.lastIndexOf('/')) return false;
        boolean v6 = address.indexOf(':') >= 0;
        int maxBits = v6 ? 128 : 32;
        if (slash >= 0) {
            String prefix = value.substring(slash + 1);
            if (prefix.isEmpty() || prefix.length() > 3) return false;
            for (int i = 0; i < prefix.length(); i++) if (!Character.isDigit(prefix.charAt(i))) return false;
            if (Integer.parseInt(prefix) > maxBits) return false;
        }
        // Literals are validated structurally, never handed to InetAddress: getByName would resolve a
        // hostname, so malformed remote data could cause a DNS lookup during parsing.
        return v6 ? isIpv6(address) : isIpv4(address);
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) if (!Character.isDigit(part.charAt(i))) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** RFC 4291 text form: 8 groups, or one "::" elision; the last group may be an embedded IPv4. */
    private static boolean isIpv6(String value) {
        if (value.indexOf(':') < 0) return false;
        int elision = value.indexOf("::");
        if (elision != value.lastIndexOf("::")) return false; // at most one "::"
        String head = elision < 0 ? value : value.substring(0, elision);
        String tail = elision < 0 ? "" : value.substring(elision + 2);
        int groups = 0;
        for (String side : elision < 0 ? new String[]{value} : new String[]{head, tail}) {
            if (side.isEmpty()) continue;
            String[] parts = side.split(":", -1);
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean last = i == parts.length - 1;
                boolean lastOfValue = last && (side == tail || elision < 0);
                if (lastOfValue && part.indexOf('.') >= 0) {
                    if (!isIpv4(part)) return false;
                    groups += 2;
                    continue;
                }
                if (part.isEmpty() || part.length() > 4) return false;
                for (int c = 0; c < part.length(); c++) {
                    char ch = part.charAt(c);
                    boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
                    if (!hex) return false;
                }
                groups++;
            }
        }
        if (elision < 0) return groups == 8;
        return groups <= 7; // "::" stands for at least one zero group
    }

    private static JSONArray array(JSONObject group, String key) throws IOException {
        if (!group.has(key)) return new JSONArray();
        JSONArray array = group.optJSONArray(key);
        if (array == null) throw new IOException(key + " 必须是字符串数组");
        return array;
    }

    private static void rejectUnknown(JSONObject object, String... allowed) throws IOException {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean known = false;
            for (String candidate : allowed) if (candidate.equals(key)) known = true;
            if (!known) throw new IOException("规则含未知字段: " + preview(key));
        }
    }

    private static void spend(int[] budget) throws IOException {
        if (--budget[0] < 0) throw new IOException("规则条目超过 " + MAX_ENTRIES + " 上限");
    }

    /** Remote values end up in messages and logs: keep them short and single-line. */
    static String preview(String value) {
        if (value == null) return "(null)";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < 40; i++) {
            char c = value.charAt(i);
            out.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return out.length() < value.length() ? out + "…" : out.toString();
    }
}
