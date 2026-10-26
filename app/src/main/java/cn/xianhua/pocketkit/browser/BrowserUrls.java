package cn.xianhua.pocketkit.browser;

import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** The address bar and saved entries share the same network-only boundary. */
public final class BrowserUrls {
    private BrowserUrls() { }

    public static String address(String input) {
        String value = input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请输入网址或搜索内容");
        if (value.matches("(?i)^https?://.*")) return network(value);
        if (value.contains("://") || value.matches("(?i)^(javascript|data|file|content|intent|about|tel|mailto):.*"))
            throw new IllegalArgumentException("只允许 HTTP / HTTPS 网页");
        if (!value.matches(".*\\s.*") && (value.contains(".") || value.matches("localhost(:[0-9]+)?(/.*)?")))
            return network("https://" + value);
        try { return "https://www.bing.com/search?q=" + URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    public static String network(String input) {
        try {
            URI uri = new URI(input.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String authority = uri.getRawAuthority();
            if (!(scheme.equals("https") || scheme.equals("http")) || authority == null
                    || authority.isEmpty() || authority.contains("@") || authority.contains("%") || authority.contains("\\"))
                throw new IllegalArgumentException();
            // URI.getHost does not recognize Unicode domains; normalize them before checking again.
            if (!authority.startsWith("[")) {
                int colon = authority.lastIndexOf(':');
                String host = colon < 0 ? authority : authority.substring(0, colon);
                String port = colon < 0 ? "" : authority.substring(colon);
                authority = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT) + port;
            }
            String result = scheme + "://" + authority
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            URI checked = new URI(result);
            if (checked.getHost() == null || checked.getPort() > 65535 || checked.getPort() < -1
                    || authority.endsWith(":")) throw new IllegalArgumentException();
            return checked.toASCIIString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("网址无效：仅允许不含账号密码的 HTTP / HTTPS 地址");
        }
    }

    public static String origin(String url) {
        URI uri = URI.create(network(url));
        int port = uri.getPort();
        boolean standard = port == -1 || ("https".equals(uri.getScheme()) && port == 443) || ("http".equals(uri.getScheme()) && port == 80);
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port);
    }

    public static boolean allowed(String url) {
        try { network(url); return true; } catch (IllegalArgumentException ex) { return false; }
    }
}
