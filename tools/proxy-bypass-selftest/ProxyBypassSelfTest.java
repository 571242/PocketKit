package cn.xianhua.pocketkit.proxy;

import android.content.SharedPreferences;
import android.net.VpnService;
import java.lang.reflect.Proxy;
import java.util.*;

/** Run against tiny Android stubs: storage copies, clearing, stale packages and builder calls. */
public final class ProxyBypassSelfTest {
    private static int checks;
    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        Map<String, Set<String>> values = new HashMap<>();
        boolean[] commitSucceeds = { true };
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                ProxyBypassSelfTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (obj, method, a) -> {
                    switch (method.getName()) {
                        case "putStringSet": values.put((String) a[0], (Set<String>) a[1]); return obj;
                        case "commit": return commitSucceeds[0];
                        default: throw new AssertionError(method.getName());
                    }
                });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                ProxyBypassSelfTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (obj, method, a) -> {
                    switch (method.getName()) {
                        case "getStringSet": return values.getOrDefault((String) a[0], (Set<String>) a[1]);
                        case "edit": return editor;
                        default: throw new AssertionError(method.getName());
                    }
                });

        check(ProxyBypass.load(prefs).isEmpty(), "new installations start with no exclusions");
        Set<String> selected = new HashSet<>(Arrays.asList("one.app", "two.app", "missing.app"));
        check(ProxyBypass.save(prefs, selected), "save reports success");
        selected.clear();
        check(ProxyBypass.load(prefs).size() == 3, "saved set does not alias caller");
        Set<String> loaded = ProxyBypass.load(prefs);
        loaded.clear();
        check(ProxyBypass.load(prefs).size() == 3, "loaded set does not alias storage");
        VpnService.Builder builder = new VpnService.Builder();
        ProxyBypass.apply(prefs, builder);
        check(builder.applied.equals(new HashSet<>(Arrays.asList("one.app", "two.app"))),
                "installed apps are excluded and missing apps are skipped");
        check(ProxyBypass.load(prefs).contains("missing.app"), "missing app remains visible for removal");
        check(ProxyBypass.save(prefs, Collections.emptySet()), "clear saves successfully");
        builder = new VpnService.Builder();
        ProxyBypass.apply(prefs, builder);
        check(ProxyBypass.load(prefs).isEmpty() && builder.applied.isEmpty(), "clear removes every exclusion");
        commitSucceeds[0] = false;
        check(!ProxyBypass.save(prefs, Collections.singleton("one.app")), "storage failures are surfaced");
        commitSucceeds[0] = true;
        ProxyBypass.save(prefs, Collections.singleton("broken.app"));
        try {
            ProxyBypass.apply(prefs, new VpnService.Builder());
            throw new AssertionError("unexpected builder failure was swallowed");
        } catch (IllegalStateException expected) { check(true, "unexpected builder failure propagates"); }
        System.out.println("ProxyBypassSelfTest PASS: " + checks + " checks");
    }
}
