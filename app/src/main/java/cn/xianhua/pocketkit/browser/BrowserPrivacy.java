package cn.xianhua.pocketkit.browser;

import android.content.Context;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.webkit.ScriptHandler;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Reduces exposed values; deliberately not an identity-spoofing or anonymity guarantee. */
public final class BrowserPrivacy {
    private static final String[] TRACKERS = {"google-analytics.com", "doubleclick.net", "googletagmanager.com",
            "hm.baidu.com", "cnzz.com", "clarity.ms", "fpjs.io", "fingerprintjs.com"};
    private BrowserPrivacy() { }
    public static boolean tracker(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        for (String entry : TRACKERS) if (host.equals(entry) || host.endsWith("." + entry)) return true;
        return false;
    }
    public static boolean earlyScripts() { return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT); }

    public static String apply(Context context, WebView view, JSONObject info) throws Exception {
        WebSettings settings = view.getSettings();
        String original = settings.getUserAgentString();
        Matcher match = Pattern.compile("Chrome/(\\d+)").matcher(original);
        String major = match.find() ? match.group(1) : "";
        if (major.isEmpty()) throw new IllegalStateException("无法识别系统网页内核，请更新 Android System WebView");
        String reduced = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/"
                + major + ".0.0.0 Mobile Safari/537.36";
        settings.setUserAgentString(reduced);
        boolean hints = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA);
        if (hints) {
            String version = major + ".0.0.0";
            UserAgentMetadata metadata = new UserAgentMetadata.Builder()
                    .setBrandVersionList(Arrays.asList(
                            new UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(version).build(),
                            new UserAgentMetadata.BrandVersion.Builder().setBrand("Android WebView").setMajorVersion(major).setFullVersion(version).build()))
                    .setFullVersion(version).setPlatform("Android").setPlatformVersion("10.0.0")
                    .setArchitecture("arm").setModel("K").setMobile(true).setBitness(64).setWow64(false).build();
            WebSettingsCompat.setUserAgentMetadata(settings, metadata);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR))
            WebSettingsCompat.setAttributionRegistrationBehavior(settings, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED);
        if (earlyScripts()) {
            try (InputStream stream = context.getAssets().open("browser/privacy.js"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
                WebViewCompat.addDocumentStartJavaScript(view, new String(out.toByteArray(), StandardCharsets.UTF_8), Collections.singleton("*"));
            }
        }
        return "UA 收敛；" + (hints ? "Client Hints 已收敛；" : "内核不支持 Client Hints 控制；")
                + (earlyScripts() ? "页面设备信息防护已启用" : "旧内核未启用页面设备信息防护，建议更新 WebView");
    }

    public static ScriptHandler restoreSession(WebView view, JSONObject info) throws Exception {
        JSONObject storage = info.optJSONObject("sessionStorage");
        if (storage == null || storage.length() == 0) return null;
        if (!earlyScripts()) throw new IllegalStateException("此冻结窗口需要新版 Android System WebView 才能安全恢复站点会话");
        String origin = BrowserUrls.origin(info.getString("url"));
        if (!origin.equals(info.optString("origin"))) throw new IllegalStateException("冻结窗口来源校验失败");
        String script = "(()=>{if(window!==window.top||location.origin!==" + JSONObject.quote(origin)
                + ")return;const data=JSON.parse(" + JSONObject.quote(storage.toString())
                + ");try{if(sessionStorage.length===0)for(const k of Object.keys(data))sessionStorage.setItem(k,data[k]);}catch(e){}})();";
        return WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton(origin));
    }
}
