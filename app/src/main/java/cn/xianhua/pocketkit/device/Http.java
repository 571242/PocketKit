package cn.xianhua.pocketkit.device;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 客户端：只用 java.net，不引入 OkHttp 等依赖。
 *
 * 设计约束（对应合同要求）：
 *  - 必须能区分"网络失败/服务端报错/返回内容不是预期格式"，不能把失败包装成空结果；
 *  - 有超时，避免界面永远卡在"正在查询"；
 *  - 不自动跟随重定向以外的任何跳转，且不携带 Cookie；
 *  - 只用 GET/POST，不发送设备标识、不发送用户联系人等无关数据。
 */
public final class Http {

    private Http() {
    }

    /** 一次请求的完整结果。status 为 0 表示连接层失败，此时 error 有说明。 */
    public static final class Response {
        public final int status;
        public final String body;
        public final String contentType;
        public final String error;

        public Response(int status, String body, String contentType, String error) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
            this.error = error;
        }

        public boolean ok() {
            return error == null && status >= 200 && status < 300;
        }
    }

    public static final String USER_AGENT = "PocketKit/0.1 (Android; +local tools)";

    public static Response get(String url) {
        return get(url, null);
    }

    /** accept 可为 null。自定义 Accept 用于要求服务端返回 JSON。 */
    public static Response get(String url, String accept) {
        return request("GET", url, accept, null, null);
    }

    public static Response postJson(String url, byte[] jsonBody) {
        return request("POST", url, "application/json", jsonBody, "application/json");
    }

    private static Response request(String method, String url, String accept,
                                    byte[] body, String contentType) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            if (accept != null) {
                connection.setRequestProperty("Accept", accept);
            }
            if (body != null) {
                connection.setDoOutput(true);
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }

            int status = connection.getResponseCode();
            String type = connection.getContentType();
            // 4xx/5xx 的正文在 errorStream 里，必须读出来才能给出真实原因。
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream, connection.getContentEncoding());
            return new Response(status, text, type, null);
        } catch (java.net.SocketTimeoutException ex) {
            return new Response(0, "", null, "请求超时（10 秒连接 / 20 秒读取）。请检查网络，或稍后重试。");
        } catch (java.net.UnknownHostException ex) {
            return new Response(0, "", null, "无法解析域名 " + ex.getMessage() + "。请检查网络与 DNS。");
        } catch (javax.net.ssl.SSLException ex) {
            return new Response(0, "", null, "HTTPS 握手失败：" + ex.getMessage());
        } catch (IOException ex) {
            return new Response(0, "", null, "网络请求失败：" + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream raw, String encoding) throws IOException {
        InputStream stream = raw;
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            stream = new GZIPInputStream(raw);
        }
        try (InputStream in = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                // 上限 4 MB：查询类接口不需要更多，避免恶意/异常响应撑爆内存。
                if (total > 4 * 1024 * 1024) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();
            // 多数接口声明 UTF-8；少数老接口是 GBK，交给调用方按需再解。
            return new String(bytes, Charset.forName("UTF-8"));
        }
    }

    /** 供需要按 GBK 解释正文的站点使用（例如部分快递查询接口）。 */
    public static String toGbk(String utf8Misread) {
        try {
            return new String(utf8Misread.getBytes(StandardCharsets.UTF_8), "GBK");
        } catch (java.io.UnsupportedEncodingException ex) {
            return utf8Misread;
        }
    }
}
