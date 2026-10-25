package cn.xianhua.pocketkit.lanshare;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 纯 JVM 的 HTTP 实传测试：真开一个监听端口，真用 Socket 发 HTTP，真落盘。
 *
 * 覆盖任务要求里必须验证的六件事：
 *  1. 认证：令牌不对一律 404，令牌对了才给页面；
 *  2. 头部边界：请求行过长、头部超量、Content-Length 冲突、未支持的 Transfer-Encoding；
 *  3. 路径：../ 与百分号编码穿越、URL 里塞绝对路径都被拦住；
 *  4. 重名：同名上传得到 (1)(2)，原文件一个字节都不变；
 *  5. 取消/中断：连接在中途断掉，半成品被删除，已有文件不受影响；
 *  6. 正常通路：页面、状态、上传、下载、文字互传、删除。
 *
 * 运行：见 .scratch/lan-share/run-tests.sh。断言失败退出码 1。
 */
public final class LanServerTest {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "lanshare-test");
        deleteTree(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("测试目录建不出来：" + root);
        }
        ROOT = root;
        checkNames();
        checkAddressPick();
        String page = "<html><body>局域网互传</body></html>";
        DirStore store = new DirStore(root);
        LanServer server = new LanServer(
                "0123456789abcdefghijklmnopqrstuv", store, page, event -> { },
                InetAddress.getLoopbackAddress(), 0, 8L * 1024 * 1024);
        server.start();
        int port = server.port();
        check(port > 0, "服务在随机端口启动");

        String token = "0123456789abcdefghijklmnopqrstuv";
        String base = "/t/" + token;

        try {
            checkPageAndAuth(port, base, page, token);
            checkStateShape(port, base);
            checkUploadRoundTrip(port, base, root);
            checkDuplicateNames(port, base, root);
            checkTraversal(port, base);
            checkHeaderLimits(port, base);
            checkCrossSite(port, base);
            checkTextExchange(port, base);
            checkConcurrentSameName(port, base, root);
            checkCancelledUpload(port, base, root);
            checkDownloadAndDelete(port, base, root);
            checkOversizeRejected(port, base, store);
        } finally {
            server.stop();
        }
        check(!server.isRunning(), "停止后不再监听");

        System.out.println(checks + " checks, "
                + (failures.isEmpty() ? "all passed" : failures.size() + " FAILURES"));
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        deleteTree(root);
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------------- 纯逻辑：名字与地址 ----------------

    private static void checkNames() {
        check(LanNames.sanitize("报告.txt").equals("报告.txt"), "正常中文名保持原样");
        check(LanNames.sanitize("../../etc/passwd").equals("passwd"), "穿越段只留最后一段");
        check(LanNames.sanitize("..\\..\\windows\\x.dll").equals("x.dll"), "反斜杠穿越同样处理");
        check(LanNames.sanitize(".hidden").equals("hidden"), "前导点被去掉");
        check(LanNames.sanitize("a\u0000b\nc.txt").equals("abc.txt"), "控制字符被丢弃");
        check(LanNames.sanitize("   ").isEmpty(), "全空白返回空串由调用方兜底");
        check(LanNames.sanitize(null).isEmpty(), "null 返回空串");
        check(LanNames.sanitizeOrFallback("", 1234L).equals("上传-1234"), "空名兜底带时间戳");
        String longName = repeat('x', 400) + ".txt";
        check(LanNames.sanitize(longName).length() <= LanNames.MAX_NAME, "超长名字被截断");
        check(LanNames.sanitize(longName).endsWith(".txt"), "截断后保留扩展名");

        Set<String> taken = new LinkedHashSet<>();
        taken.add("a.txt");
        check(LanNames.unique("b.txt", taken).equals("b.txt"), "不冲突时原名返回");
        check(LanNames.unique("a.txt", taken).equals("a (1).txt"), "冲突时加 (1)");
        taken.add("a (1).txt");
        check(LanNames.unique("a.txt", taken).equals("a (2).txt"), "再冲突加 (2)");
        taken.add("Photo.JPG");
        check(LanNames.unique("photo.jpg", taken).equals("photo (1).jpg"),
                "大小写不同也视为冲突，绝不覆盖");
        check(LanNames.unique("无扩展名", new LinkedHashSet<>()).equals("无扩展名"),
                "无扩展名也能处理");
    }

    private static void checkAddressPick() {
        List<LanAddress.Candidate> withVpnFirst = new ArrayList<>();
        // VPN 隧道常常也带一个 10.x 私有地址，必须排除，否则二维码会是连不上的地址。
        withVpnFirst.add(new LanAddress.Candidate("tun0", "10.8.0.2", true, true));
        withVpnFirst.add(new LanAddress.Candidate("wlan0", "192.168.1.23", false, true));
        LanAddress.Candidate picked = LanAddress.pick(withVpnFirst);
        check(picked != null && picked.address.equals("192.168.1.23"),
                "排除 VPN 隧道，选中 Wi-Fi 地址");

        List<LanAddress.Candidate> hotspot = new ArrayList<>();
        hotspot.add(new LanAddress.Candidate("ap0", "192.168.43.1", false, true));
        hotspot.add(new LanAddress.Candidate("rmnet_data0", "10.12.3.4", false, true));
        LanAddress.Candidate hot = LanAddress.pick(hotspot);
        check(hot != null && hot.address.equals("192.168.43.1"), "热点地址可用，蜂窝被排除");

        List<LanAddress.Candidate> cellularOnly = new ArrayList<>();
        cellularOnly.add(new LanAddress.Candidate("rmnet_data0", "10.12.3.4", false, true));
        check(LanAddress.pick(cellularOnly) == null, "只有蜂窝地址时拒绝启动");

        List<LanAddress.Candidate> publicOnly = new ArrayList<>();
        publicOnly.add(new LanAddress.Candidate("wlan0", "8.8.8.8", false, true));
        check(LanAddress.pick(publicOnly) == null, "公网地址不用于共享");

        List<LanAddress.Candidate> down = new ArrayList<>();
        down.add(new LanAddress.Candidate("wlan0", "192.168.1.5", false, false));
        check(LanAddress.pick(down) == null, "接口断开时不可用");

        check(LanAddress.isPrivateIpv4("172.16.0.1") && LanAddress.isPrivateIpv4("172.31.255.255"),
                "172.16/12 判定正确");
        check(!LanAddress.isPrivateIpv4("172.32.0.1"), "172.32 不在私有段内");
        check(!LanAddress.isPrivateIpv4("1.2.3"), "段数不足时拒绝");
    }

    // ---------------- 认证 ----------------

    private static void checkPageAndAuth(int port, String base, String page, String token)
            throws IOException {
        Reply wrong = get(port, "/t/" + "ffffffffffffffffffffffffffffffff" + "/");
        check(wrong.status == 404, "令牌不对返回 404（实际 " + wrong.status + "）");
        check(!wrong.body.contains(page) && !wrong.body.contains("局域网互传"),
                "令牌不对时不泄露页面内容");

        Reply noToken = get(port, "/");
        check(noToken.status == 404, "根路径无令牌返回 404（实际 " + noToken.status + "）");

        Reply withToken = get(port, base + "/");
        check(withToken.status == 200, "令牌正确时返回页面（实际 " + withToken.status + "）");
        check(withToken.body.contains(page), "页面内容原样返回");
        check("text/html; charset=utf-8".equals(withToken.header("content-type")), "页面是 HTML");
        check(withToken.header("content-security-policy") != null, "带 CSP 头");
        check(withToken.header("access-control-allow-origin") == null, "绝不发 CORS 头");

        Reply redirect = get(port, base);
        check(redirect.status == 302, "令牌路径不带斜杠时 302 补斜杠（实际 " + redirect.status + "）");
        check((base + "/").equals(redirect.header("location")), "302 指向带斜杠的地址");
    }

    // ---------------- 状态 ----------------

    private static void checkStateShape(int port, String base) throws IOException {
        Reply state = get(port, base + "/api/state");
        check(state.status == 200, "状态接口可用");
        check(state.body.contains("\"files\":["), "状态里有文件数组");
        check(state.body.contains("\"messages\":["), "状态里有消息数组");
        check(state.body.contains("\"maxUpload\":"), "状态里报告上传上限");
    }

    // ---------------- 正常上传下载 ----------------

    private static void checkUploadRoundTrip(int port, String base, File root) throws IOException {
        byte[] payload = "第一行\n第二行\n".getBytes(StandardCharsets.UTF_8);
        Reply upload = post(port, base + "/api/upload?name=" + encode("报告 v1.txt"), payload, null);
        check(upload.status == 200, "正常上传返回 200（实际 " + upload.status + "）");
        File saved = new File(root, "报告 v1.txt");
        check(saved.isFile(), "文件落在接收目录里");
        check(new String(readAll(saved), StandardCharsets.UTF_8).equals(
                new String(payload, StandardCharsets.UTF_8)), "落盘内容与发送内容一致");

        Reply state = get(port, base + "/api/state");
        check(state.body.contains("报告 v1.txt"), "状态里能看到刚上传的文件");

        String id = idOf(state.body, "报告 v1.txt");
        check(id != null, "状态里给出了文件 id");
        Reply download = get(port, base + "/f/" + encode(id));
        check(download.status == 200, "下载返回 200（实际 " + download.status + "）");
        check(download.bodyBytes.length == payload.length, "下载长度一致");
        check(download.header("content-disposition") != null, "下载带文件名头");
        check(download.header("content-disposition").contains("filename*=UTF-8''"),
                "中文名用 RFC 5987 编码，避免乱码");

        // 同一页面里第二个中文名文件也必须能各自下载（id 唯一，不是靠文件名或 hashCode）。
        byte[] other = "第二份".getBytes(StandardCharsets.UTF_8);
        post(port, base + "/api/upload?name=" + encode("第二个 文件.txt"), other, null);
        String otherId = idOf(get(port, base + "/api/state").body, "第二个 文件.txt");
        check(otherId != null && !otherId.equals(id), "两个文件拿到不同的 id");
        Reply otherReply = get(port, base + "/f/" + encode(otherId));
        check(otherReply.status == 200, "第二个文件也能下载");
        check(new String(otherReply.bodyBytes, StandardCharsets.UTF_8).equals("第二份"),
                "第二个文件下载内容正确");
    }

    // ---------------- 重名 ----------------

    private static void checkDuplicateNames(int port, String base, File root) throws IOException {
        File original = new File(root, "photo.jpg");
        byte[] originalBytes = "原始照片，绝不能被覆盖".getBytes(StandardCharsets.UTF_8);
        write(original, originalBytes);

        Reply first = post(port, base + "/api/upload?name=photo.jpg",
                "新照片 A".getBytes(StandardCharsets.UTF_8), null);
        check(first.status == 200, "同名上传仍然成功（新建而不是覆盖）");
        Reply second = post(port, base + "/api/upload?name=photo.jpg",
                "新照片 B".getBytes(StandardCharsets.UTF_8), null);
        check(second.status == 200, "第二次同名上传也成功");

        check(new File(root, "photo (1).jpg").isFile(), "第一次新建为 photo (1).jpg");
        check(new File(root, "photo (2).jpg").isFile(), "第二次新建为 photo (2).jpg");
        check(new String(readAll(original), StandardCharsets.UTF_8)
                .equals(new String(originalBytes, StandardCharsets.UTF_8)),
                "原文件 photo.jpg 的内容没有被改动");

        // 大小写不同的同名文件同样新建，不覆盖。
        post(port, base + "/api/upload?name=PHOTO.JPG", "x".getBytes(StandardCharsets.UTF_8), null);
        check(new File(root, "photo.jpg").isFile(), "大小写不同的同名也不会顶掉原文件");
    }

    // ---------------- 路径穿越 ----------------

    private static void checkTraversal(int port, String base) throws IOException {
        String[] paths = {
                base + "/../etc/passwd",
                base + "/f/..%2f..%2fetc%2fpasswd",
                base + "/%2e%2e/%2e%2e/etc/passwd",
                base + "/..%5c..%5cwindows%5csystem32",
                base + "/api/state?x=../../etc",
        };
        for (String path : paths) {
            Reply reply = get(port, path);
            // 穿越要么被 400 拒绝，要么落回页面；绝不能把系统文件吐出来。
            boolean blocked = reply != null && (reply.status == 400 || reply.status == 403
                    || (reply.status == 200 && !reply.body.contains("root:x:")));
            check(blocked, "穿越路径被拒绝或落回页面：" + path);
        }

        // 上传文件名里带穿越成分：名字被削成单段，绝不可能写到接收目录之外。
        // （查询串里的名字不参与路由，所以这里不拒绝请求，而是靠清洗兜底 —— 见 LanNames。）
        Reply upload = post(port, base + "/api/upload?name=" + encode("../../../逃逸.txt"),
                "x".getBytes(StandardCharsets.UTF_8), null);
        check(upload.status == 200, "带穿越成分的名字清洗后仍可上传（实际 " + upload.status + "）");
        check(new File(ROOT, "逃逸.txt").isFile(), "清洗后的文件只落在接收目录里");
        check(!new File(ROOT.getParentFile(), "逃逸.txt").exists(), "文件没有写到接收目录之外");

        // 清洗函数本身也挡住了穿越（纵深防御，和上面的请求级命名互为备份）。
        check("passwd".equals(LanNames.sanitize("../../etc/passwd")),
                "清洗后只剩最后一段");
        check(!LanNames.sanitize("a\\b").contains("\\"), "反斜杠不留在名字里");
    }

    private static File ROOT;

    // ---------------- 头部边界 ----------------

    private static void checkHeaderLimits(int port, String base) throws IOException {
        // 请求行超长：直接关连接，不回 200。
        String hugePath = base + "/" + repeat('a', 5000);
        Reply longLine = rawGet(port, hugePath);
        check(longLine == null || longLine.status >= 400,
                "超长请求行被拒绝（未返回成功页）");

        // 头部超过总字节上限。
        StringBuilder headers = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            headers.append("X-Pad-").append(i).append(": ").append(repeat('b', 900)).append("\r\n");
        }
        Reply tooMany = rawRequest(port, "GET " + base + "/ HTTP/1.1\r\nHost: x\r\n"
                + headers + "\r\n");
        check(tooMany == null || tooMany.status >= 400, "头部总量超限被拒绝");

        // Content-Length 重复：明确 400，不猜哪个为准。
        Reply dup = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: x\r\n"
                + "X-LanShare: 1\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello");
        check(dup != null && dup.status == 400,
                "重复 Content-Length 返回 400（实际 " + (dup == null ? "无应答" : dup.status) + "）");

        // 分块传输不支持：501，不猜边界。
        Reply chunked = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: x\r\n"
                + "X-LanShare: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
        check(chunked != null && chunked.status == 501,
                "不支持的 Transfer-Encoding 返回 501（实际 "
                        + (chunked == null ? "无应答" : chunked.status) + "）");

        // 没有 Content-Length：411。
        Reply noLength = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: x\r\n"
                + "X-LanShare: 1\r\n\r\n");
        check(noLength != null && noLength.status == 411,
                "缺 Content-Length 返回 411（实际 "
                        + (noLength == null ? "无应答" : noLength.status) + "）");

        // 负数 / 非数字的 Content-Length：拒绝。
        Reply bogus = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: x\r\n"
                + "X-LanShare: 1\r\nContent-Length: -5\r\n\r\n");
        check(bogus != null && bogus.status >= 400, "负数 Content-Length 被拒绝");
        Reply alpha = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: x\r\n"
                + "X-LanShare: 1\r\nContent-Length: abc\r\n\r\n");
        check(alpha != null && alpha.status >= 400, "非数字 Content-Length 被拒绝");
    }

    // ---------------- 跨站 ----------------

    private static void checkCrossSite(int port, String base) throws IOException {
        // 别的站点发来的 Origin：哪怕带了 guard 头也拒绝。
        Reply cross = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: evil.example\r\n"
                + "Origin: http://attacker.example\r\nX-LanShare: 1\r\nContent-Length: 3\r\n\r\nabc");
        check(cross != null && cross.status == 403,
                "跨站 Origin 返回 403（实际 " + (cross == null ? "无应答" : cross.status) + "）");

        // 同源 Origin 放行。
        String host = "127.0.0.1:" + port;
        Reply same = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: " + host
                + "\r\nOrigin: http://" + host + "\r\nX-LanShare: 1\r\nContent-Length: 5\r\n\r\nhello");
        check(same != null && same.status == 200, "同源 Origin 放行");

        // 没有 guard 头：拒绝（挡住 HTML 表单的跨站提交）。
        Reply noGuard = rawRequest(port, "POST " + base + "/api/text HTTP/1.1\r\nHost: " + host
                + "\r\nContent-Length: 4\r\n\r\nform");
        check(noGuard != null && noGuard.status == 403,
                "缺少同源标记的 POST 返回 403（实际 "
                        + (noGuard == null ? "无应答" : noGuard.status) + "）");

        // 预检请求不会拿到 CORS 许可。
        Reply preflight = rawRequest(port, "OPTIONS " + base + "/api/text HTTP/1.1\r\nHost: " + host
                + "\r\nOrigin: http://attacker.example\r\nAccess-Control-Request-Method: POST\r\n\r\n");
        check(preflight != null && preflight.header("access-control-allow-origin") == null,
                "预检不返回 CORS 允许头");
    }

    // ---------------- 文字 ----------------

    private static void checkTextExchange(int port, String base) throws IOException {
        String text = "从电脑发来的一段中文，含标点：，。！";
        Reply sent = post(port, base + "/api/text", text.getBytes(StandardCharsets.UTF_8), null);
        check(sent.status == 200, "文字发送返回 200");

        Reply state = get(port, base + "/api/state");
        check(state.body.contains("从电脑发来的一段中文"), "文字出现在状态里");
        check(state.body.contains("\"phone\":false"), "电脑发来的消息标记为非手机");

        // 空文字被拒绝，避免列表里出现空行。
        Reply empty = post(port, base + "/api/text", new byte[0], null);
        check(empty.status == 400, "空文字返回 400（实际 " + empty.status + "）");

        // 超长文字被拒绝。
        byte[] big = new byte[70 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        Reply tooBig = post(port, base + "/api/text", big, null);
        check(tooBig.status == 413, "超长文字返回 413（实际 " + tooBig.status + "）");
    }

    // ---------------- 取消 / 中断 ----------------

    private static void checkCancelledUpload(int port, String base, File root) throws IOException {
        byte[] existing = "接收目录里本来就有的内容".getBytes(StandardCharsets.UTF_8);
        write(new File(root, "keep.me"), existing);

        String name = "cancel-me.bin";
        byte[] body = new byte[400 * 1024];
        java.util.Arrays.fill(body, (byte) 'Z');

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            // 只发一半就断开：模拟用户点「取消」或网络中断。
            String head = "POST " + base + "/api/upload?name=" + encode(name) + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "X-LanShare: 1\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body, 0, body.length / 2);
            out.flush();
        } catch (IOException ignored) {
            // 断开本身是预期行为。
        }
        // 给服务端一点时间收尾（关闭流 + 删除半成品）。
        waitFor(() -> !new File(root, name).exists() && !new File(root, name + ".part").exists(), 4000);
        check(!new File(root, name).exists(), "中断的上传不会留下正式文件");
        check(!new File(root, name + ".part").exists(), "中断的上传不会留下半成品");
        check(new String(readAll(new File(root, "keep.me")), StandardCharsets.UTF_8)
                .equals(new String(existing, StandardCharsets.UTF_8)),
                "中断的上传没有影响已有文件");

        // 中断之后服务还能继续服务新请求。
        Reply alive = get(port, base + "/api/state");
        check(alive.status == 200, "取消之后服务仍然可用");
    }

    // ---------------- 下载与删除 ----------------

    private static void checkDownloadAndDelete(int port, String base, File root) throws IOException {
        byte[] payload = "待删除文件".getBytes(StandardCharsets.UTF_8);
        post(port, base + "/api/upload?name=" + encode("待删除.txt"), payload, null);
        String id = idOf(get(port, base + "/api/state").body, "待删除.txt");
        check(id != null, "找到待删除文件的 id");

        Reply deleted = post(port, base + "/api/delete?id=" + encode(id), new byte[0], null);
        check(deleted.status == 200, "删除上传文件返回 200（实际 " + deleted.status + "）");
        check(!new File(root, "待删除.txt").exists(), "删除后文件真的不在了");

        Reply again = post(port, base + "/api/delete?id=" + encode(id), new byte[0], null);
        check(again.status == 404, "重复删除返回 404");
    }

    // ---------------- 超限 ----------------

    private static void checkOversizeRejected(int port, String base, DirStore store)
            throws IOException {
        int before = store.uploaded().size();
        Reply reply = post(port, base + "/api/upload?name=huge.bin",
                new byte[0], null, "999999999999");
        check(reply.status == 413, "声明超过上限的上传返回 413（实际 " + reply.status + "）");
        check(store.uploaded().size() == before, "超限请求没有创建任何文件");
    }

    // ---------------- 并发同名 ----------------

    /**
     * 两个线程同时上传同名文件：必须得到两份内容，谁都不许覆盖谁。
     *
     * 这是「重名新建不覆盖」在真实并发下的验证 —— 只看单线程顺序是看不出竞态的。
     */
    private static void checkConcurrentSameName(int port, String base, File root)
            throws IOException {
        String name = "concurrent.txt";
        byte[] a = repeat('A', 64 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] b = repeat('B', 64 * 1024).getBytes(StandardCharsets.UTF_8);

        final Reply[] replies = new Reply[2];
        Thread t1 = new Thread(() -> {
            try {
                replies[0] = post(port, base + "/api/upload?name=" + encode(name), a, null);
            } catch (IOException ex) {
                // 下面会因为没有成功应答而报错。
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                replies[1] = post(port, base + "/api/upload?name=" + encode(name), b, null);
            } catch (IOException ex) {
                // 同上。
            }
        });
        t1.start();
        t2.start();
        try {
            t1.join(15000);
            t2.join(15000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        check(replies[0] != null && replies[0].status == 200, "并发上传 A 成功");
        check(replies[1] != null && replies[1].status == 200, "并发上传 B 成功");

        File first = new File(root, name);
        File second = new File(root, name.replace(".txt", " (1).txt"));
        check(first.isFile() && second.isFile(), "并发同名得到两个不同的文件");
        String c1 = new String(readAll(first), StandardCharsets.UTF_8);
        String c2 = new String(readAll(second), StandardCharsets.UTF_8);
        check(c1.startsWith("A") || c1.startsWith("B"), "第一个文件内容是其中一份完整数据");
        check(c2.startsWith("A") || c2.startsWith("B"), "第二个文件内容是另一份完整数据");
        check(!c1.equals(c2), "两份内容互不相同，没有被互相覆盖");
        check(c1.length() == a.length && c2.length() == b.length, "两份都完整（长度未被截断）");
    }

    // ---------------- 本地目录 Store ----------------

    /** 用本地目录实现 Store：和 SAF 实现同一套契约，测试因此能真的落盘。 */
    static final class DirStore implements LanServer.Store {
        private final File root;
        private final Map<String, File> uploads = new TreeMap<>();
        /** 文件名 → 纯 ASCII id，和 SafStore 的行为一致。 */
        private final Map<String, String> ids = new TreeMap<>();
        private int nextId;

        DirStore(File root) {
            this.root = root;
        }

        @Override public Set<String> existingNames() {
            Set<String> names = new LinkedHashSet<>();
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    names.add(child.getName());
                }
            }
            names.addAll(uploads.keySet());
            return names;
        }

        @Override public LanServer.Sink create(final String uniqueName, long expectedBytes)
                throws IOException {
            final File temp = new File(root, uniqueName + ".part");
            final File target = new File(root, uniqueName);
            if (!temp.createNewFile()) {
                throw new IOException("临时文件已存在：" + temp.getName());
            }
            return new LanServer.Sink() {
                @Override public OutputStream open() throws IOException {
                    return new FileOutputStream(temp);
                }

                @Override public void commit() throws IOException {
                    if (target.exists()) {
                        throw new IOException("目标已存在，拒绝覆盖：" + target.getName());
                    }
                    if (!temp.renameTo(target)) {
                        throw new IOException("提交失败");
                    }
                    uploads.put(uniqueName, target);
                    ids.put(uniqueName, "u" + (++nextId));
                }

                @Override public void abort() {
                    if (temp.exists()) {
                        temp.delete();
                    }
                }
            };
        }

        @Override public List<LanServer.SharedFile> uploaded() {
            List<LanServer.SharedFile> result = new ArrayList<>();
            for (Map.Entry<String, File> entry : uploads.entrySet()) {
                File file = entry.getValue();
                String id = ids.get(entry.getKey());
                if (id == null) {
                    continue;
                }
                result.add(new LanServer.SharedFile(id,
                        entry.getKey(), file.length(), true, file, null));
            }
            return result;
        }

        @Override public InputStream open(LanServer.SharedFile file) throws IOException {
            if (!(file.handle instanceof File)) {
                throw new IOException("没有可读句柄");
            }
            return new FileInputStream((File) file.handle);
        }

        @Override public boolean delete(LanServer.SharedFile file) {
            if (!(file.handle instanceof File)) {
                return false;
            }
            boolean ok = ((File) file.handle).delete();
            if (ok) {
                uploads.remove(file.name);
                ids.remove(file.name);
            }
            return ok;
        }
    }

    // ---------------- HTTP 客户端 ----------------

    static final class Reply {
        int status;
        final Map<String, String> headers = new TreeMap<>();
        String body = "";
        byte[] bodyBytes = new byte[0];

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static Reply get(int port, String path) throws IOException {
        return rawRequest(port, "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nConnection: close\r\n\r\n");
    }

    private static Reply post(int port, String path, byte[] body, String contentType)
            throws IOException {
        return post(port, path, body, contentType, String.valueOf(body.length));
    }

    private static Reply post(int port, String path, byte[] body, String contentType, String length)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("POST ").append(path).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1:").append(port).append("\r\n");
        head.append("X-LanShare: 1\r\n");
        head.append("Connection: close\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Content-Type: ").append(contentType == null
                ? "application/octet-stream" : contentType).append("\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, all, 0, headBytes.length);
        System.arraycopy(body, 0, all, headBytes.length, body.length);
        return exchange(port, all);
    }

    /** 直接发原始请求；连接被服务端提前关掉时返回 null。 */
    private static Reply rawRequest(int port, String text) throws IOException {
        return exchange(port, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Reply rawGet(int port, String path) throws IOException {
        return rawRequest(port, "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
    }

    private static Reply exchange(int port, byte[] request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            byte[] response = readAll(socket.getInputStream());
            if (response.length == 0) {
                return null;
            }
            return parse(response);
        } catch (IOException ex) {
            // 服务端按策略直接关连接（例如拒绝超长请求行）时走这里。
            return null;
        }
    }

    private static Reply parse(byte[] raw) throws IOException {
        int split = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        if (split < 0) {
            throw new IOException("响应没有头部结束标记");
        }
        String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        byte[] body = new byte[raw.length - split - 4];
        System.arraycopy(raw, split + 4, body, 0, body.length);
        Reply reply = new Reply();
        String[] lines = head.split("\r\n");
        String[] statusLine = lines[0].split(" ");
        reply.status = Integer.parseInt(statusLine[1]);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                reply.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        reply.bodyBytes = body;
        reply.body = new String(body, StandardCharsets.UTF_8);
        return reply;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ---------------- 小工具 ----------------

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

    private static String encode(String text) throws IOException {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
            fail(what);
        }
    }

    private static void fail(String what) {
        failures.add(what);
    }
}
