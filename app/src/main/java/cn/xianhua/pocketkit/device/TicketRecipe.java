package cn.xianhua.pocketkit.device;

import android.content.Context;
import cn.xianhua.pocketkit.core.TicketClickPlan;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** User-recorded positions and optional visible labels; never credentials or order details. */
public final class TicketRecipe {
    public String packageName = "";
    public int width, height, intervalMs = 300, timeoutSeconds = 30;
    public final ArrayList<TicketClickPlan.Point> points = new ArrayList<>();
    public final ArrayList<String> labels = new ArrayList<>();

    public static TicketRecipe load(Context context) {
        TicketRecipe result = new TicketRecipe();
        String stored = context.getSharedPreferences("pocketkit_ticket_auto", 0).getString("recipe", "");
        if (stored.isEmpty()) return result;
        try {
            JSONObject json = new JSONObject(stored);
            result.packageName = json.getString("package");
            result.width = json.getInt("width"); result.height = json.getInt("height");
            result.intervalMs = json.optInt("interval", 300);
            result.timeoutSeconds = json.optInt("timeout", 30);
            JSONArray list = json.getJSONArray("points");
            if (list.length() > 8) throw new IllegalArgumentException("最多8个点击步骤");
            for (int i = 0; i < list.length(); i++) {
                JSONObject point = list.getJSONObject(i);
                result.points.add(new TicketClickPlan.Point(point.getInt("x"), point.getInt("y")));
                result.labels.add(point.optString("label", ""));
            }
            result.validate();
        } catch (org.json.JSONException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("保存的点击步骤无法读取，请重新录制。", ex);
        }
        return result;
    }

    public void validate() {
        if (!TicketTapService.safePackage(packageName)) throw new IllegalArgumentException("请在目标购票 App 中绑定应用。");
        if (width <= 0 || height <= 0 || points.isEmpty() || points.size() > 8) throw new IllegalArgumentException("请先录制1至8个点击位置。");
        if (intervalMs < 100 || intervalMs > 5000) throw new IllegalArgumentException("点击间隔应为100至5000毫秒。");
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("自动操作时长应为1至60秒。");
        for (TicketClickPlan.Point point : points) {
            if (point.x < 0 || point.y < 0 || point.x >= width || point.y >= height) throw new IllegalArgumentException("点击位置超出录制屏幕，请重新录制。");
        }
    }

    public void save(Context context) {
        validate();
        try {
            JSONObject json = new JSONObject().put("package", packageName).put("width", width).put("height", height)
                    .put("interval", intervalMs).put("timeout", timeoutSeconds);
            JSONArray list = new JSONArray();
            for (int i = 0; i < points.size(); i++) {
                TicketClickPlan.Point point = points.get(i);
                list.put(new JSONObject().put("x", point.x).put("y", point.y).put("label", i < labels.size() ? labels.get(i) : ""));
            }
            json.put("points", list);
            context.getSharedPreferences("pocketkit_ticket_auto", 0).edit().putString("recipe", json.toString()).apply();
        } catch (org.json.JSONException ex) { throw new IllegalStateException("点击步骤未保存", ex); }
    }
}
