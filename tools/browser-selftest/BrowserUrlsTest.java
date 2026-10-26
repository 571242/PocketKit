import cn.xianhua.pocketkit.browser.BrowserUrls;

/** Standalone JVM checks: no Android device or third-party test framework required. */
public final class BrowserUrlsTest {
    private static int checks;
    private static void same(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    private static void denied(String value) {
        checks++;
        if (BrowserUrls.allowed(value)) throw new AssertionError("Accepted: " + value);
    }
    private static void addressDenied(String value) {
        checks++;
        try { BrowserUrls.address(value); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Address accepted: " + value);
    }
    public static void main(String[] args) {
        same("https://example.com", BrowserUrls.address(" example.com "));
        same("https://example.com/path?q=a%20b#section", BrowserUrls.address("https://example.com/path?q=a%20b#section"));
        same("http://127.0.0.1:8080/test", BrowserUrls.network("http://127.0.0.1:8080/test"));
        same("http://[::1]:8080/", BrowserUrls.network("http://[::1]:8080/"));
        same("https://localhost:8080/", BrowserUrls.address("localhost:8080/"));
        same("https://example.com/%E4%B8%AD%E6%96%87", BrowserUrls.network("HTTPS://EXAMPLE.COM/中文"));
        same("https://xn--fsqu00a.xn--0zwm56d", BrowserUrls.address("例子.测试"));
        same("https://www.bing.com/search?q=hello+world", BrowserUrls.address("hello world"));
        same("https://www.bing.com/search?q=%E6%8B%BE%E7%94%A8", BrowserUrls.address("拾用"));
        same("https://example.com", BrowserUrls.origin("https://EXAMPLE.com:443/page?q=1"));
        same("http://example.com", BrowserUrls.origin("http://example.com:80/page"));
        same("https://example.com:8443", BrowserUrls.origin("https://example.com:8443/page"));
        same("http://[::1]:8080", BrowserUrls.origin("http://[::1]:8080/page"));
        for (String value : new String[]{null, "", "about:blank", "javascript:alert(1)", "file:///sdcard/test",
                "content://provider/test", "data:text/html,test", "intent://test", "ftp://example.com",
                "https:///missing-host", "https://user:password@example.com", "https://user@example.com",
                "https://example.com\\@evil.test", "https://example.com/%GG", "https://example.com:99999",
                "https://example.com:", "https://example.com:-1", "https://example.com:abc", "https://%65xample.com/",
                "https://example.com/\nheader", "https://example.com/space here"}) denied(value);
        for (String value : new String[]{"", "javascript:alert(1)", "intent://example.com", "file:///sdcard/file",
                "content://test", "data:text/html,hi", "https://user@example.com"}) addressDenied(value);
        System.out.println("PASS " + checks + " browser URL checks");
    }
}
