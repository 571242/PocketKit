package cn.xianhua.pocketkit.device;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 113 以图搜番。
 *
 * 用 trace.moe 的匿名公开接口（已实测：GET /me 返回配额，POST /search 接受图片字节并返回结果）。
 *
 * 上传纪律（合同与任务书都点名要求）：
 *  - 图片**必须由用户主动选择**；
 *  - 选择后先弹确认框，写清"上传到哪个第三方""上传目的是什么""配额还剩多少"；
 *  - **只有用户点「同意并上传」才发起 POST**；取消则什么都不发；
 *  - 不做任何后台/自动上传，不缓存图片，不把图片写到磁盘。
 */
public final class AnimeSearchTool {

    private AnimeSearchTool() {
    }

    public static boolean supports(String id) {
        return "113".equals(id);
    }

    public static String limitation(String id) {
        return "113".equals(id)
                ? "使用 trace.moe 的公开接口，图片只在用户逐次确认后上传（不做任何自动上传）。该服务每天匿名配额约 100 次，用尽后会失败；识别范围以日本动画截图为主，真人照片、非动画截图或模糊画面通常匹配不到。结果由第三方数据库提供，不代表本应用的判断。"
                : "";
    }

    /** 单张图片上传上限，避免把巨大相册原图直接推给第三方。 */
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

    private static final String ENDPOINT = "https://api.trace.moe/search?anilistInfo";

    public static void build(Ui ui) {
        ui.note("选一张动画截图，本工具会把它上传到第三方服务 trace.moe 做画面检索。");
        ui.note("⚠ 图片会上传到第三方：trace.moe（api.trace.moe）。本工具不会自动上传任何图片——你选择图片后会先看到确认框，只有点「同意并上传」才会发送。");
        ui.note("⚠ 匿名配额约 100 次/天，全设备共用；用尽后会失败并提示。请勿上传含隐私的画面（人脸、证件、屏幕上的私人内容）。");
        ui.note("识别范围以日本动画截图为主；真人照片、游戏画面、模糊或加了字幕边框的截图通常匹配不到。");

        final android.widget.TextView quotaLabel = (android.widget.TextView)
                ui.note("配额：尚未查询（可先点「查询剩余配额」，这一步不发送任何图片）");

        final android.widget.TextView pickedLabel = (android.widget.TextView)
                ui.note("已选图片：尚未选择");

        final Uri[] picked = {null};
        final String[] pickedName = {null};

        ui.button("查询剩余配额（不发送图片）", () -> ui.background(() -> {
            Http.Response response = Http.get("https://api.trace.moe/me", "application/json");
            String text;
            if (!response.ok()) {
                text = "查询配额失败。\n" + WebQueryTools.failureText(response);
            } else {
                try {
                    JSONObject json = new JSONObject(response.body);
                    int quota = json.optInt("quota", -1);
                    int used = json.optInt("quotaUsed", -1);
                    text = String.format(Locale.US, "配额：每天 %d 次，已用 %d 次，剩余 %d 次。",
                            quota, used, Math.max(0, quota - used));
                } catch (Exception ex) {
                    text = "返回内容无法解析：" + response.body;
                }
            }
            final String result = text;
            ui.activity().runOnUiThread(() -> quotaLabel.setText(result.replace('\n', ' ')));
        }));

        ui.button("选择图片", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ui.requestActivityResult(pick, (resultCode, data) -> {
                if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
                    ui.note("已取消选择。");
                    return;
                }
                Uri uri = data.getData();
                String name = displayName(ui.activity().getContentResolver(), uri);
                picked[0] = uri;
                pickedName[0] = name;
                pickedLabel.setText("已选图片：" + name + "\n（尚未上传。点下面的按钮才会弹确认框）");
                ui.note("已选中图片，但还没有上传。按「上传并搜索」才会出现确认框。");
            });
        });

        ui.button("上传并搜索", () -> {
            if (picked[0] == null) {
                ui.fail(new IllegalStateException("请先选择一张图片。"));
                return;
            }
            // 先读字节与大小，用于在确认框里如实告知要上传多少数据。
            ui.background(() -> {
                final byte[] bytes;
                final String error;
                try {
                    bytes = readBytes(ui.activity().getContentResolver(), picked[0]);
                    error = null;
                } catch (Exception ex) {
                    ui.activity().runOnUiThread(() -> ui.fail(new IllegalStateException(
                            "读取图片失败：" + ex.getMessage())));
                    return;
                }
                if (error == null && bytes.length > MAX_UPLOAD_BYTES) {
                    ui.activity().runOnUiThread(() -> ui.fail(new IllegalStateException(String.format(
                            Locale.US, "图片太大（%s），超过单次上传上限 %s。请先裁剪或压缩后再试。",
                            cn.xianhua.pocketkit.core.SizeFmt.bytes(bytes.length),
                            cn.xianhua.pocketkit.core.SizeFmt.bytes(MAX_UPLOAD_BYTES)))));
                    return;
                }
                ui.activity().runOnUiThread(() -> confirmAndUpload(ui, bytes, pickedName[0]));
            });
        });

        ui.show("先选图片，再按「上传并搜索」；确认框里点同意才会真正上传。");
    }

    /** 上传前的确认框。这是唯一的触发 POST 的入口。 */
    private static void confirmAndUpload(Ui ui, byte[] bytes, String name) {
        String message = "即将把这张图片上传到第三方服务器，用于画面检索（以图搜番）。\n\n"
                + "上传目标：trace.moe（api.trace.moe），第三方服务，非本应用服务器\n"
                + "上传内容：图片本身（" + cn.xianhua.pocketkit.core.SizeFmt.bytes(bytes.length) + "）\n"
                + "文件： " + (name == null ? "（未取到文件名）" : name) + "\n"
                + "用途：仅用于本次画面检索，本应用不保存你的图片，也不会自动上传\n"
                + "配额：该服务每天约 100 次匿名额度，本次会占用 1 次\n\n"
                + "请确认这张图片不含你不希望外传的内容（人脸、证件、私人聊天记录等）。";

        new android.app.AlertDialog.Builder(ui.activity())
                .setTitle("确认上传到第三方？")
                .setMessage(message)
                .setNegativeButton("取消（不上传）", (dialog, which) ->
                        ui.show("已取消，图片没有上传。"))
                .setPositiveButton("同意并上传", (dialog, which) -> upload(ui, bytes))
                .show();
    }

    private static void upload(Ui ui, byte[] bytes) {
        ui.background(() -> {
            Http.Response response = postImage(bytes);
            ui.activity().runOnUiThread(() -> ui.show(render(response)));
        });
    }

    /** POST 原始图片字节。trace.moe 接受 body 直接为图片内容。 */
    static Http.Response postImage(byte[] bytes) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(60_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", Http.USER_AGENT);
            connection.setRequestProperty("Content-Type", "image/jpeg");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (in != null) {
                byte[] chunk = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = in.read(chunk)) > 0 && total < 2 * 1024 * 1024) {
                    buffer.write(chunk, 0, read);
                    total += read;
                }
                in.close();
            }
            return new Http.Response(status, new String(buffer.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8), connection.getContentType(), null);
        } catch (java.net.SocketTimeoutException ex) {
            return new Http.Response(0, "", null, "上传超时。图片较大或网络较慢，请换更小的图片或稍后重试。");
        } catch (Exception ex) {
            return new Http.Response(0, "", null, "上传失败：" + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 渲染搜索结果。 */
    static String render(Http.Response response) {
        if (!response.ok()) {
            String extra = response.status == 429
                    ? "\n（该服务有每天约 100 次的匿名配额，可能已用尽）" : "";
            return "搜索失败。\n" + WebQueryTools.failureText(response) + extra;
        }
        try {
            JSONObject json = new JSONObject(response.body);
            String error = json.optString("error");
            if (!error.isEmpty()) {
                return "服务端返回错误：" + error
                        + "\n\n常见原因：图片不是动画截图、画面被裁得太狠、或加了大量字幕。";
            }
            JSONArray results = json.optJSONArray("result");
            if (results == null || results.length() == 0) {
                return "没有匹配到动画画面。\n\n可能原因：这不是日本动画截图；图片太模糊、被裁剪或被字幕遮挡；"
                        + "或该作品不在其数据库中。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "匹配到 %d 个候选（按相似度排序）：\n", results.length()));
            int shown = Math.min(results.length(), 5);
            for (int i = 0; i < shown; i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                double similarity = item.optDouble("similarity", -1);
                sb.append(String.format(Locale.US, "\n%d. 相似度 %.1f%%\n", i + 1,
                        similarity < 0 ? 0 : similarity * 100));

                JSONObject anilist = item.optJSONObject("anilist");
                if (anilist != null) {
                    JSONObject title = anilist.optJSONObject("title");
                    if (title != null) {
                        String nativeTitle = title.optString("native");
                        String romaji = title.optString("romaji");
                        String chinese = title.optString("chinese");
                        if (!chinese.isEmpty()) {
                            sb.append("   中文名：").append(chinese).append('\n');
                        }
                        if (!nativeTitle.isEmpty()) {
                            sb.append("   原名：").append(nativeTitle).append('\n');
                        }
                        if (!romaji.isEmpty() && !romaji.equals(nativeTitle)) {
                            sb.append("   罗马字：").append(romaji).append('\n');
                        }
                    }
                    int idMal = anilist.optInt("idMal", 0);
                    if (idMal > 0) {
                        sb.append("   MyAnimeList ID：").append(idMal).append('\n');
                    }
                }

                int episode = item.optInt("episode", 0);
                if (episode > 0) {
                    sb.append("   集数：第 ").append(episode).append(" 集\n");
                }
                double from = item.optDouble("from", -1);
                if (from >= 0) {
                    sb.append("   时间点：").append(formatTime(from)).append('\n');
                }
                String filename = item.optString("filename");
                if (!filename.isEmpty()) {
                    sb.append("   来源文件名：").append(filename).append('\n');
                }
                String preview = item.optString("image");
                if (!preview.isEmpty()) {
                    sb.append("   预览图：").append(preview).append('\n');
                }
            }
            if (results.length() > shown) {
                sb.append("\n…另有 ").append(results.length() - shown).append(" 个候选未显示。");
            }
            sb.append("\n\n数据来源：trace.moe（第三方）。相似度是算法评分，不保证正确；");
            sb.append("请结合片名与集数自行核对。预览图链接指向第三方，点击会连接该服务。");
            return sb.toString();
        } catch (Exception ex) {
            return "返回内容无法解析为 JSON。\n" + WebQueryTools.failureText(response)
                    + "\n解析错误：" + ex.getMessage();
        }
    }

    static String formatTime(double seconds) {
        int total = (int) seconds;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private static byte[] readBytes(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new java.io.IOException("无法打开所选图片");
            }
            byte[] chunk = new byte[64 * 1024];
            int read;
            int total = 0;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                // 超限时立刻停止读取，不把整个大图读进内存。
                if (total > MAX_UPLOAD_BYTES) {
                    throw new java.io.IOException("图片超过上限");
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // 落到默认名
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return "（未知文件名）";
    }
}
