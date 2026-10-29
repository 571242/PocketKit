package cn.xianhua.pocketkit.mocklocation;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

import cn.xianhua.pocketkit.MockPlaceSearch;

/**
 * 选点页（assets/mock_location.html）与原生之间的桥。
 *
 * 约定：
 *  - 坐标在 JS 里一律是 GCJ-02（因为底图是高德瓦片），跨桥一律是 WGS-84；
 *    两个方向的转换都由 {@link CoordTransform} 完成，避免在 JS 里再抄一份公式。
 *  - 所有改状态的方法都只是往主线程投递，不在桥线程里等结果；进度由 status 推送回来。
 */
public final class MockLocationBridge {

    private final Context context;
    private final WebView webView;
    private final MockPlaceSearch placeSearch;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    public MockLocationBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.placeSearch = new MockPlaceSearch(this.context);
    }

    public void close() {
        closed = true;
        placeSearch.close();
    }

    /** GCJ-02 → WGS-84，入参出参都是 "lat,lng"。 */
    @JavascriptInterface
    public String toWgs84(String gcj) {
        double[] point = parse(gcj);
        if (point == null) {
            return "";
        }
        double[] wgs = CoordTransform.gcj02ToWgs84(point[0], point[1]);
        return String.format(Locale.US, "%.7f,%.7f", wgs[0], wgs[1]);
    }

    /** WGS-84 → GCJ-02，入参出参都是 "lat,lng"。 */
    @JavascriptInterface
    public String toGcj02(String wgs) {
        double[] point = parse(wgs);
        if (point == null) {
            return "";
        }
        double[] gcj = CoordTransform.wgs84ToGcj02(point[0], point[1]);
        return String.format(Locale.US, "%.7f,%.7f", gcj[0], gcj[1]);
    }

    /** 地点搜索异步返回；旧请求和已销毁页面的回包不再回写。 */
    @JavascriptInterface
    public void search(final String keyword) {
        try {
            placeSearch.search(keyword, (requestId, json) -> main.post(() -> {
                if (placeSearch.isCurrent(requestId)) {
                    webView.evaluateJavascript("window.MockUI&&MockUI.onSearchResult(" + JSONObject.quote(json) + ")", null);
                }
            }));
        } catch (RejectedExecutionException ignored) {
            // 页面销毁时可能恰好收到最后一次搜索请求。
        }
    }

    @JavascriptInterface
    public void cancelSearch() {
        placeSearch.cancel();
    }

    /** 启动模拟。points 是 WGS-84 的 "lat,lng;lat,lng"，单点表示原地不动。 */
    @JavascriptInterface
    public void start(final String points, final double speedMps, final double jitterMeters) {
        final List<double[]> parsed = parseList(points);
        main.post(() -> {
            if (parsed.isEmpty()) {
                push("坐标格式不对，至少需要一个点。");
                return;
            }
            try {
                MockLocationSession.configure(parsed, speedMps, jitterMeters);
            } catch (IllegalArgumentException bad) {
                push("坐标不可用：" + bad.getMessage());
                return;
            }
            try {
                MockLocationService.launch(context);
            } catch (RuntimeException error) {
                MockLocationSession.stop();
                MockLocationSession.disarm(context);
                MockLocationSession.fail("启动模拟服务失败，请检查系统后台限制后重试（" + error.getClass().getSimpleName() + "）。");
            }
            pushStatus();
        });
    }

    @JavascriptInterface
    public void stop() {
        main.post(() -> {
            MockLocationService.halt(context);
            pushStatus();
        });
    }

    @JavascriptInterface
    public void togglePause() {
        main.post(() -> {
            MockLocationSession.togglePaused();
            pushStatus();
        });
    }

    @JavascriptInterface
    public void setMultiplier(final double value) {
        main.post(() -> {
            MockLocationSession.setMultiplier(value);
            pushStatus();
        });
    }

    /** 同步读取状态：只读内存，不做 IPC，所以不会卡住选点页。 */
    @JavascriptInterface
    public String status() {
        return statusJson();
    }

    @JavascriptInterface
    public boolean permitted() {
        return MockLocationSession.permitted(context);
    }

    @JavascriptInterface
    public boolean locationEnabled() {
        return MockLocationSession.locationEnabled(context);
    }

    @JavascriptInterface
    public boolean developerOptionsEnabled() {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 打开开发者选项。系统没有该页面时退回设置首页。 */
    @JavascriptInterface
    public void openDeveloperOptions() {
        main.post(() -> {
            // 不用 resolveActivity()：Android 11 起的包可见性会让它对设置页返回 null，
            // 那样会误判成「没有这个页面」而退回设置首页。直接 startActivity 再兜底更准。
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (Throwable missing) {
                try {
                    context.startActivity(new Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) {
                    // 个别 ROM 会拦截设置跳转；界面上的文字说明仍然可用。
                }
            }
        });
    }

    @JavascriptInterface
    public void openLocationSettings() {
        main.post(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable ignored) {
            }
        });
    }

    String statusJson() {
        MockLocationSession.Status status = MockLocationSession.status();
        try {
            JSONObject json = new JSONObject();
            json.put("state", status.stateName());
            json.put("message", status.message);
            json.put("lat", status.lat);
            json.put("lng", status.lng);
            json.put("travelled", status.travelled);
            json.put("total", status.total);
            json.put("arrived", status.arrived);
            json.put("speed", status.speed);
            json.put("multiplier", status.multiplier);
            json.put("progress", status.progress());
            return json.toString();
        } catch (Throwable error) {
            return "{}";
        }
    }

    void pushStatus() {
        main.post(() -> {
            if (!closed) {
                webView.evaluateJavascript("window.MockUI&&MockUI.onStatus(" + JSONObject.quote(statusJson()) + ")", null);
            }
        });
    }

    private void push(String message) {
        try {
            JSONObject json = new JSONObject(statusJson());
            json.put("message", message);
            if (!closed) {
                webView.evaluateJavascript("window.MockUI&&MockUI.onStatus(" + JSONObject.quote(json.toString()) + ")", null);
            }
        } catch (Throwable error) {
            pushStatus();
        }
    }

    private static double[] parse(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            return RouteTrack.valid(lat, lng) ? new double[]{lat, lng} : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static List<double[]> parseList(String text) {
        List<double[]> points = new ArrayList<>();
        if (text == null) {
            return points;
        }
        for (String chunk : text.split(";")) {
            double[] point = parse(chunk);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }
}
