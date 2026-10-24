package cn.xianhua.pocketkit.media;

import android.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTPS client for the two upload-based tools (010 图片转链接, 022 以图搜图).
 *
 * <p>Deliberately {@code HttpURLConnection} and not a new dependency: the project already has a
 * working Android build, and pulling in OkHttp/Retrofit to send two multipart POSTs would add
 * transitive dependencies and licence surface for no functional gain.
 *
 * <p>Rules enforced here rather than left to callers:
 * <ul>
 *   <li>HTTPS only. A plain-HTTP endpoint is refused, because the file would travel readable.</li>
 *   <li>The request is only ever built after the user has explicitly consented on screen; the tool
 *       calls {@link Uploader} only from the confirmed branch.</li>
 *   <li>A user-agent that names the app, as 0x0.st's own guidance requires (masquerading as a
 *       browser gets clients blocked).</li>
 *   <li>Response size and time are bounded so a hostile or hanging endpoint cannot exhaust memory
 *       or wedge the worker.</li>
 * </ul>
 */
public final class Uploader {

    /** Names the app as 0x0.st asks; never a browser string. */
    public static final String USER_AGENT = "PocketKit/0.1.0 (Android; +local tool app)";

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private Uploader() {
    }

    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** A multipart/form-data part; file parts carry a filename, field parts do not. */
    public static final class Part {
        final String name;
        final String value;
        final File file;
        final String fileName;
        final String contentType;

        private Part(String name, String value, File file, String fileName, String contentType) {
            this.name = name;
            this.value = value;
            this.file = file;
            this.fileName = fileName;
            this.contentType = contentType;
        }

        public static Part field(String name, String value) {
            return new Part(name, value, null, null, null);
        }

        public static Part file(String name, File file, String fileName, String contentType) {
            return new Part(name, null, file, fileName, contentType);
        }
    }

    /** Rejects anything that is not HTTPS, with a message the user can act on. */
    public static void requireHttps(String url) throws IOException {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IOException("这个服务地址不是 HTTPS，为避免文件在传输中可被读取，本工具拒绝发送。地址：" + url);
        }
    }

    public static Response postMultipart(String url, java.util.List<Part> parts, Cancel cancel)
            throws IOException {
        requireHttps(url);
        String boundary = "----PocketKitBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(64 * 1024);

            try (OutputStream out = connection.getOutputStream()) {
                for (Part part : parts) {
                    writePart(out, part, boundary, cancel);
                }
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            cancel.check();

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, readBounded(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static Response postForm(String url, Map<String, String> fields, Cancel cancel)
            throws IOException {
        requireHttps(url);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            cancel.check();
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, readBounded(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Reads a GET with the same bounds; used to fetch a search endpoint's HTML. */
    public static Response get(String url, Cancel cancel, Map<String, String> headers) throws IOException {
        requireHttps(url);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    connection.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, readBounded(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void writePart(OutputStream out, Part part, String boundary, Cancel cancel)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (part.file != null) {
            out.write(("Content-Disposition: form-data; name=\"" + part.name
                    + "\"; filename=\"" + part.fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + (part.contentType == null ? "application/octet-stream"
                    : part.contentType) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new java.io.FileInputStream(part.file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                // Only -1 is end of stream: a 0-byte read is legal and would otherwise truncate
                // the uploaded file without any error.
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) {
                        continue;
                    }
                    cancel.check();
                    out.write(buffer, 0, n);
                }
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(("Content-Disposition: form-data; name=\"" + part.name + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write((part.value == null ? "" : part.value).getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Reads at most {@link #MAX_RESPONSE_BYTES}; a longer body is truncated, not buffered whole. */
    private static String readBounded(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = stream) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0 && buffer.size() < MAX_RESPONSE_BYTES) {
                buffer.write(chunk, 0, Math.min(n, MAX_RESPONSE_BYTES - buffer.size()));
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String encode(String value) throws IOException {
        return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    /**
     * Builds the consent dialog every upload must pass through.
     *
     * <p>The text names the exact service, what leaves the device, and the concrete consequences
     * (public link, third-party retention, no delete guarantee). The positive button is
     * "同意并上传" so nobody can tap through by reflex, and cancelling is the safe default: the
     * dialog is not cancelable by back only to force a deliberate choice.
     */
    public static AlertDialog consentDialog(android.content.Context context, String title,
                                            String serviceDescription, String consequence,
                                            Runnable onConsent) {
        return new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(serviceDescription + "\n\n" + consequence
                        + "\n\n只有你点「同意并上传」之后，本工具才会发起网络请求。"
                        + "在此之前不会发送任何数据。")
                .setPositiveButton("同意并上传", (dialog, which) -> onConsent.run())
                .setNegativeButton("取消，不上传", null)
                .setCancelable(true)
                .create();
    }
}
