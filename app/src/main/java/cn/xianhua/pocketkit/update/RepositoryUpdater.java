package cn.xianhua.pocketkit.update;

import android.content.Context;
import android.content.SharedPreferences;
import cn.xianhua.pocketkit.proxy.ProxyProfileManager;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads releases straight from a public code repository.
 *
 * <p>The app is published on GitHub, so update checks and the download itself happen there
 * instead of on the author's small host. The hub keeps serving proxy rules only; no APK ever
 * travels through it.
 *
 * <p>Only the repository address is remembered locally. Nothing is uploaded.
 */
public final class RepositoryUpdater {
    /** Default repository; the owner can change it from the update sheet. */
    public static final String DEFAULT_REPOSITORY = "571242/PocketKit";
    private static final String PREF = "pocketkit_repository_update";
    private static final String KEY_REPOSITORY = "repository";
    private static final Pattern NUMERIC = Pattern.compile("(\\d+(?:\\.\\d+)*)");

    private RepositoryUpdater() { }

    /** One published release, reduced to what an update prompt needs. */
    public static final class Release {
        public final String version;
        public final String notes;
        public final String pageUrl;
        /** Direct APK asset when the release carries one; empty means "open the page instead". */
        public final String apkUrl;

        Release(String version, String notes, String pageUrl, String apkUrl) {
            this.version = version;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
        }
    }

    public static String repository(Context context) {
        String saved = prefs(context).getString(KEY_REPOSITORY, "");
        return saved == null || saved.trim().isEmpty() ? DEFAULT_REPOSITORY : saved.trim();
    }

    public static void setRepository(Context context, String value) {
        prefs(context).edit().putString(KEY_REPOSITORY, normalize(value)).apply();
    }

    /**
     * Accepts {@code owner/name}, a github.com URL, or a git URL, and returns {@code owner/name}.
     * An unusable value falls back to the default instead of silently keeping a broken address.
     */
    public static String normalize(String value) {
        if (value == null) return DEFAULT_REPOSITORY;
        String text = value.trim();
        if (text.isEmpty()) return DEFAULT_REPOSITORY;
        text = text.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", ""); // scheme
        text = text.replaceFirst("^[^/@]+@", "");                     // user@
        StringBuilder kept = new StringBuilder();
        for (String segment : text.split("/")) {
            String part = segment.trim();
            if (part.isEmpty()) continue;
            // Drop the host so both owner/name and a full URL collapse to the same two segments.
            if (kept.length() == 0 && (part.contains(".") || part.equals("github.com"))) continue;
            kept.append(part).append('/');
        }
        text = kept.toString().replaceFirst("\\.git/$", "").replaceFirst("/$", "");
        String[] splits = text.split("/");
        if (splits.length != 2) return DEFAULT_REPOSITORY;
        String owner = splits[0], name = splits[1];
        if (!owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") || !name.matches("[A-Za-z0-9._-]{1,100}")) {
            return DEFAULT_REPOSITORY;
        }
        return owner + "/" + name;
    }

    public static String pageUrl(Context context) {
        return "https://github.com/" + repository(context);
    }

    public static String releasesUrl(Context context) {
        return pageUrl(context) + "/releases/latest";
    }

    /** Latest published release. Blocking: call from a worker thread, never from the UI thread. */
    public static Release latest(Context context) throws IOException {
        String body = ProxyProfileManager.fetchHttpContent(
                "https://api.github.com/repos/" + repository(context) + "/releases/latest");
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception malformed) {
            throw new IOException("仓库返回的内容不是有效版本信息");
        }
        String tag = json.optString("tag_name", "").trim();
        String page = json.optString("html_url", "").trim();
        if (page.isEmpty() || !page.startsWith("https://")) page = releasesUrl(context);
        String apk = "";
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            // Prefer the plain release APK; skip checksum files and split/architecture variants.
            if (name.endsWith(".apk") && url.startsWith("https://") && !name.contains("sha")) {
                apk = url;
                break;
            }
        }
        String notes = json.optString("body", "").trim();
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        return new Release(versionOf(tag), notes, page, apk);
    }

    /** {@code v0.9.5} / {@code 0.9.5-preview} → {@code 0.9.5}; empty when no number is present. */
    static String versionOf(String tag) {
        if (tag == null) return "";
        Matcher matcher = NUMERIC.matcher(tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * Positive when {@code remote} is newer than {@code local}. Compares numeric components so
     * 0.10.0 outranks 0.9.9, and treats a missing component as zero.
     */
    public static int compare(String local, String remote) {
        int[] left = parts(local), right = parts(remote);
        if (right.length == 0) return 0;
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0, b = i < right.length ? right[i] : 0;
            if (a != b) return a < b ? 1 : -1;
        }
        return 0;
    }

    private static int[] parts(String version) {
        String clean = versionOf(version);
        if (clean.isEmpty()) return new int[0];
        String[] pieces = clean.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException overflow) {
                out[i] = Integer.MAX_VALUE; // A component too large to parse is certainly newer.
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
