package cn.xianhua.pocketkit.device;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** HTTPS timestamp reference. HTTP Date (whole seconds) is deliberately not a fallback. */
final class TicketClock {
    private static volatile Sample sample;

    private TicketClock() {}

    static long now() {
        Sample current = sample;
        return current == null ? System.currentTimeMillis()
                : current.epochAtReceive + SystemClock.elapsedRealtime() - current.elapsedAtReceive;
    }

    static String status() {
        Sample current = sample;
        if (current == null) return "手机时间 · 尚未网络校准";
        long age = (SystemClock.elapsedRealtime() - current.elapsedAtReceive) / 60000;
        return String.format(Locale.US, "淘宝网络参考 · 往返 %d ms%s\n不是购票平台授时，不保证毫秒级准确。",
                current.roundTrip, age >= 15 ? " · 建议重新校准" : "");
    }

    static void synchronize() throws IOException {
        Sample best = null;
        IOException failure = null;
        for (int i = 0; i < 3; i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("对时已取消");
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp&nocache=" + SystemClock.elapsedRealtime());
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("User-Agent", "PocketKit/0.2.2");
                long sent = SystemClock.elapsedRealtime();
                if (connection.getResponseCode() != 200) throw new IOException("授时接口暂不可用");
                StringBuilder text = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        text.append(line);
                        if (text.length() > 16384) throw new IOException("授时响应异常");
                    }
                }
                long received = SystemClock.elapsedRealtime();
                JSONObject data = new JSONObject(text.toString()).getJSONObject("data");
                long epoch = Long.parseLong(data.getString("t"));
                if (epoch < 1577836800000L || epoch > 4102444800000L) throw new IOException("授时数据无效");
                long rtt = received - sent;
                Sample measured = new Sample(epoch + rtt / 2, received, rtt);
                if (best == null || measured.roundTrip < best.roundTrip) best = measured;
            } catch (IOException ex) {
                failure = ex;
                if (best == null) break;
            } catch (JSONException | NumberFormatException ex) {
                failure = new IOException("授时接口未返回有效的毫秒时间戳", ex);
                if (best == null) break;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (best == null) throw failure == null ? new IOException("网络对时失败") : failure;
        if (Thread.currentThread().isInterrupted()) throw new IOException("对时已取消，保留原时间来源");
        if (TicketTapService.isActive()) throw new IOException("已有自动任务，保留原时间来源");
        long remaining = TicketOverlayService.target() - now();
        if (TicketOverlayService.isRunning() && remaining > 0 && remaining < 15000) {
            throw new IOException("临近开抢，保留原时间来源");
        }
        sample = best;
    }

    private static final class Sample {
        final long epochAtReceive, elapsedAtReceive, roundTrip;
        Sample(long epoch, long elapsed, long rtt) {
            epochAtReceive = epoch;
            elapsedAtReceive = elapsed;
            roundTrip = rtt;
        }
    }
}
