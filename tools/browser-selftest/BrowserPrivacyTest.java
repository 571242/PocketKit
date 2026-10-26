import cn.xianhua.pocketkit.browser.BrowserPrivacy;

public final class BrowserPrivacyTest {
    public static void main(String[] args) {
        for (String host : new String[]{"google-analytics.com", "a.google-analytics.com", "FPJS.IO", "clarity.ms."})
            if (!BrowserPrivacy.tracker(host)) throw new AssertionError("Not blocked: " + host);
        for (String host : new String[]{null, "example.com", "notgoogle-analytics.com", "google-analytics.com.example.org", "myclarity.ms"})
            if (BrowserPrivacy.tracker(host)) throw new AssertionError("Overblocked: " + host);
        System.out.println("PASS 9 tracking domain boundary checks");
    }
}
