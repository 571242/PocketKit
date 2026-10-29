package cn.xianhua.pocketkit;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import cn.xianhua.pocketkit.device.Http;
import cn.xianhua.pocketkit.mocklocation.CoordTransform;
import cn.xianhua.pocketkit.mocklocation.RouteTrack;

/** 用户主动搜索时并行使用系统地理编码与 Photon；坐标结果统一为 GCJ-02。 */
public final class MockPlaceSearch {
    public interface Callback {
        void onResult(int requestId, String json);
    }

    private final Context context;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicInteger generation = new AtomicInteger();

    public MockPlaceSearch(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isCurrent(int requestId) {
        return generation.get() == requestId;
    }

    public void cancel() {
        generation.incrementAndGet();
    }

    public void close() {
        cancel();
        workers.shutdownNow();
    }

    public void search(String keyword, Callback callback) {
        String query = keyword == null ? "" : keyword.trim();
        int requestId = generation.incrementAndGet();
        if (query.isEmpty()) {
            callback.onResult(requestId, "[]");
            return;
        }
        SearchRequest request = new SearchRequest(requestId, callback);
        workers.execute(() -> {
            if (!isCurrent(requestId)) return;
            try {
                request.complete(searchSystem(query), true);
            } catch (Exception error) {
                request.complete(null, false);
            }
        });
        workers.execute(() -> {
            if (!isCurrent(requestId)) return;
            try {
                request.complete(searchPhoton(query), true);
            } catch (Exception error) {
                request.complete(null, false);
            }
        });
    }

    private final class SearchRequest {
        private final int requestId;
        private final Callback callback;
        private int remaining = 2;
        private boolean succeeded;
        private boolean delivered;

        SearchRequest(int requestId, Callback callback) {
            this.requestId = requestId;
            this.callback = callback;
        }

        void complete(JSONArray rows, boolean success) {
            String payload = null;
            synchronized (this) {
                if (delivered || !isCurrent(requestId)) return;
                remaining--;
                succeeded |= success;
                if (rows != null && rows.length() > 0) {
                    payload = rows.toString();
                } else if (remaining == 0) {
                    payload = succeeded ? "[]" : "{\"ok\":false,\"error\":\"地点搜索服务连接失败，请检查网络\"}";
                }
                if (payload != null) delivered = true;
            }
            if (payload != null) callback.onResult(requestId, payload);
        }
    }

    @SuppressWarnings("deprecation")
    private JSONArray searchSystem(String query) throws Exception {
        if (!Geocoder.isPresent()) throw new IOException("系统地点搜索不可用");
        List<Address> addresses = new Geocoder(context, Locale.SIMPLIFIED_CHINESE)
                .getFromLocationName(query, 6);
        if (addresses == null) throw new IOException("系统地点搜索无响应");
        JSONArray results = new JSONArray();
        for (Address address : addresses) {
            if (address == null || !address.hasLatitude() || !address.hasLongitude()) continue;
            String name = address.getFeatureName();
            String detail = address.getMaxAddressLineIndex() >= 0 ? address.getAddressLine(0) : "";
            add(results, address.getLatitude(), address.getLongitude(),
                    name == null || name.trim().isEmpty() ? query : name, detail);
        }
        return results;
    }

    private JSONArray searchPhoton(String query) throws Exception {
        String url = "https://photon.komoot.io/api/?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=6";
        Http.Response response = Http.get(url, "application/json");
        if (!response.ok()) throw new IOException("Photon 不可用");
        JSONArray features = new JSONObject(response.body).optJSONArray("features");
        if (features == null) throw new IOException("地点搜索返回格式错误");
        JSONArray results = new JSONArray();
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            JSONObject geometry = feature == null ? null : feature.optJSONObject("geometry");
            JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
            if (coordinates == null || coordinates.length() < 2) continue;
            JSONObject properties = feature.optJSONObject("properties");
            String name = property(properties, "name");
            StringBuilder detail = new StringBuilder();
            for (String key : new String[]{"city", "state", "country"}) {
                String part = property(properties, key);
                if (part.isEmpty()) continue;
                if (detail.length() > 0) detail.append(" · ");
                detail.append(part);
            }
            add(results, coordinates.optDouble(1, Double.NaN), coordinates.optDouble(0, Double.NaN),
                    name.isEmpty() ? "未命名地点" : name, detail.toString());
        }
        return results;
    }

    private static String property(JSONObject object, String key) {
        return object == null ? "" : object.optString(key, "").trim();
    }

    private static void add(JSONArray results, double lat, double lng, String name, String detail) throws Exception {
        if (!RouteTrack.valid(lat, lng)) return;
        double[] gcj = CoordTransform.wgs84ToGcj02(lat, lng);
        JSONObject row = new JSONObject();
        row.put("lat", gcj[0]);
        row.put("lng", gcj[1]);
        row.put("name", name);
        row.put("detail", detail == null ? "" : detail);
        results.put(row);
    }
}
