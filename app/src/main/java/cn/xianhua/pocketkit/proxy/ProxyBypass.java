package cn.xianhua.pocketkit.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Per-application exclusions are independent of subscriptions, nodes and route rules. */
final class ProxyBypass {
    private static final String PREFS = "proxy_bypass_apps";
    private static final String PACKAGES = "packages";

    private ProxyBypass() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> load(SharedPreferences preferences) {
        return new HashSet<>(preferences.getStringSet(PACKAGES, Collections.emptySet()));
    }

    static boolean save(SharedPreferences preferences, Set<String> packages) {
        return preferences.edit().putStringSet(PACKAGES, new HashSet<>(packages)).commit();
    }

    static void apply(SharedPreferences preferences, VpnService.Builder builder) {
        for (String name : load(preferences)) {
            try {
                builder.addDisallowedApplication(name);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep stale selections removable in the picker; they cannot block VPN startup.
            }
        }
    }
}
