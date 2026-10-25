package cn.xianhua.pocketkit.lanshare;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 用 JDK 自带的 HttpURLConnection 走一遍真实浏览器会走的路径。
 *
 * 和 LanServerTest 的区别：那边手写 Socket 故意发畸形请求来测边界；
 * 这边用标准客户端，验证「正常用户操作」这条路上服务器和网页契约真的对得上 ——
 * 包括 assets 里的真实页面能被取到、状态 JSON 能被解析、上传下载内容一致。
 */
public final class LanEndToEndTest {

    private static int checks;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    private static final String TOKEN = "0123456789abcdefghijklmnopqrstuv";

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "lanshare-e2e");
        deleteTree(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("测试目录建不出来：" + root);
        }

        // 用仓库里真实的网页资源，而不是假 HTML：这样页面和服务器是配套验过的。
        String page = readAssetPage(args.length > 1 ? args[1] : null);
        check(page.contains("局域网互传"), "取到的是真实的局域网互传页面");
        check(page.contains("api/upload"), "页面里引用了上传接口");
        check(page.contains("X-LanShare"), "页面里带了跨站防护头");

        LanServer server = new LanServer(TOKEN, new LanServerTest.DirStore(root), page,
                event -> { }, InetAddress.getLoopbackAddress(), 0, 16L * 1024 * 1024);
        server.start();
        String base = "http://127.0.0.1:" + server.port() + "/t/" + TOKEN;

        try {
            // 1) 页面
            String html = fetch(base + "/");
            check(html.equals(page), "页面内容与 assets 完全一致");

            // 2) 状态 JSON（用浏览器同样的方式解析：只看结构，不引第三方库）
            String state = fetch(base + "/api/state");
            check(state.startsWith("{") && state.endsWith("}"), "状态是 JSON 对象");
            check(state.contains("\"files\":[]"), "初始没有文件");

            // 3) 上传一个二进制文件（含 0 字节，验证不是按文本处理）
            byte[] binary = new byte[300 * 1024];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) (i % 251);
            }
            binary[0] = 0;
            binary[1] = (byte) 0xFF;
            int status = upload(base + "/api/upload?name=" + enc("照片 原图.bin"), binary);
            check(status == 200, "二进制上传返回 200（实际 " + status + "）");
            File saved = new File(root, "照片 原图.bin");
            check(saved.isFile(), "文件落盘");
            check(saved.length() == binary.length, "落盘长度一致");
            byte[] back = readFile(saved);
            check(java.util.Arrays.equals(back, binary), "落盘字节完全一致（含 0x00 与 0xFF）");

            // 4) 状态里能找到它，并按 id 下载
            state = fetch(base + "/api/state");
            check(state.contains("照片 原图.bin"), "状态里出现该文件");
            String id = idOf(state, "照片 原图.bin");
            check(id != null, "拿到下载 id");
            byte[] downloaded = fetchBytes(base + "/f/" + enc(id));
            check(java.util.Arrays.equals(downloaded, binary), "下载回来的字节和上传的一致");

            // 5) 文字双向
            check(postText(base + "/api/text", "电脑发来的中文：你好！") == 200, "文字发送成功");
            server.sendText("手机回的中文：收到");
            state = fetch(base + "/api/state");
            check(state.contains("电脑发来的中文"), "电脑文字出现在状态里");
            check(state.contains("手机回的中文"), "手机文字也出现在状态里");

            // 6) 同名再传一次：新建，不覆盖
            byte[] second = "第二次上传".getBytes(StandardCharsets.UTF_8);
            check(upload(base + "/api/upload?name=" + enc("照片 原图.bin"), second) == 200,
                    "同名上传成功");
            check(new File(root, "照片 原图 (1).bin").isFile(), "同名新建为 (1)");
            check(java.util.Arrays.equals(readFile(saved), binary), "原文件字节未变");

            // 7) 令牌错误：拿不到页面
            try {
                String wrong = fetch("http://127.0.0.1:" + server.port()
                        + "/t/ffffffffffffffffffffffffffffffff/");
                check(false, "错误令牌竟然拿到了内容：" + wrong.length() + " 字节");
            } catch (IOException expected) {
                checks++;
            }
            // 8) 令牌前缀不能当成有效令牌
            try {
                fetch("http://127.0.0.1:" + server.port() + "/t/" + TOKEN + "extra/");
                check(false, "令牌前缀被当成了有效令牌");
            } catch (IOException expected) {
                checks++;
            }
        } finally {
            server.stop();
        }

        deleteTree(root);
        System.out.println(checks + " checks, "
                + (failures.isEmpty() ? "all passed" : failures.size() + " FAILURES"));
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------------- 浏览器等价的请求 ----------------

    private static String fetch(String url) throws IOException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("X-LanShare", "1");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        int code = connection.getResponseCode();
        if (code != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = connection.getInputStream()) {
            return readAll(in);
        } finally {
            connection.disconnect();
        }
    }

    private static int upload(String url, byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("X-LanShare", "1");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        return code;
    }

    private static int postText(String url, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("X-LanShare", "1");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        return code;
    }

    // ---------------- 小工具 ----------------

    private static String readAssetPage(String path) throws IOException {
        String location = path == null
                ? "F:/SPACE/PocketKit/app/src/main/assets/lanshare/index.html"
                : path;
        return new String(readFile(new File(location)), StandardCharsets.UTF_8);
    }

    private static String idOf(String stateJson, String name) {
        int at = stateJson.indexOf("\"name\":\"" + name + "\"");
        if (at < 0) {
            return null;
        }
        int idAt = stateJson.lastIndexOf("\"id\":\"", at);
        if (idAt < 0) {
            return null;
        }
        int start = idAt + 6;
        int end = stateJson.indexOf('"', start);
        return end < 0 ? null : stateJson.substring(start, end);
    }

    private static String enc(String text) throws IOException {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            failures.add(what);
        }
    }
}
