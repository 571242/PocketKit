package cn.xianhua.pocketkit.lanshare;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网互传的最小 HTTP/1.1 服务端。
 *
 * 只依赖 java.*：可以在普通 JVM 上真的发 HTTP 请求来验证认证、头部边界、路径、重名与取消，
 * 不需要模拟器。Android 侧只负责把 SAF 目录和用户选的文件接进来。
 *
 * 安全模型（刻意做得窄）：
 *  - 会话令牌在路径里：/t/&lt;token&gt;/…；比较用定长比较，令牌不对一律 404，不泄露"存在但没权限"；
 *  - 不监听 0.0.0.0，由调用方传入具体的局域网地址；
 *  - 请求路径只用于路由，从不拼成文件系统路径 —— 落盘全部走调用方的 Store（SAF）；
 *  - 拒绝路径穿越（含百分号编码形式）、拒绝跨站（Origin 不符 / POST 缺自定义头）、不发任何 CORS 头；
 *  - 请求行、头部总字节、头部数量、上传体积、文字体积全部有上限；
 *  - 同时只允许有限个连接，连接上有读超时，闲置即断开。
 *
 * 明确不做：TLS、断点续传、分块传输（Transfer-Encoding: chunked 一律拒绝）、keep-alive。
 */
public final class LanServer {

    /** 落盘接口。Android 用 SAF 实现，测试用本地目录实现。 */
    public interface Store {
        /** 接收目录里已有的名字，用于重名时新建 (1)(2)… 而不是覆盖。 */
        Set<String> existingNames() throws IOException;

        /** 以给定的唯一名字新建一个接收目标；失败或取消时必须删除半成品。 */
        Sink create(String uniqueName, long expectedBytes) throws IOException;

        /** 已上传的文件（供浏览器再次下载），按时间从新到旧。 */
        List<SharedFile> uploaded();

        InputStream open(SharedFile file) throws IOException;

        /** 删除一个已上传文件；原件（用户手机上的文件）永远不在这里。 */
        boolean delete(SharedFile file) throws IOException;
    }

    /** 一次上传的目标。commit 之后才算成功。 */
    public interface Sink {
        OutputStream open() throws IOException;

        /** 传输完成：把内容提交为正式文件。 */
        void commit() throws IOException;

        /** 失败或取消：删除半成品，绝不碰已有文件。 */
        void abort();
    }

    /** 用户明确选中用于发送的文件的内容来源。 */
    public interface Source {
        InputStream open() throws IOException;
    }

    /** 服务端事件，交给界面显示；不写日志。 */
    public interface Listener {
        void onEvent(Event event);
    }

    /** 事件类型固定几种，避免界面去解析字符串。 */
    public static final class Event {
        public enum Kind { STARTED, STOPPED, TEXT, UPLOAD_START, UPLOAD_PROGRESS, UPLOAD_DONE, UPLOAD_FAILED, DOWNLOAD, REJECTED, NOTE }

        public final Kind kind;
        public final String name;
        public final long bytes;
        public final long total;
        public final String detail;

        Event(Kind kind, String name, long bytes, long total, String detail) {
            this.kind = kind;
            this.name = name;
            this.bytes = bytes;
            this.total = total;
            this.detail = detail;
        }

        static Event of(Kind kind, String detail) {
            return new Event(kind, "", 0, 0, detail);
        }
    }

    /** 共享文件（手机挑的文件 / 浏览器上传的文件）。 */
    public static final class SharedFile {
        public final String id;
        public final String name;
        public final long size;
        public final boolean uploaded;
        /** 上传文件的 SAF 句柄；挑来的文件为 null。 */
        public final Object handle;
        /** 挑来的文件的内容来源；上传文件为 null。 */
        public final Source source;

        SharedFile(String id, String name, long size, boolean uploaded, Object handle, Source source) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.uploaded = uploaded;
            this.handle = handle;
            this.source = source;
        }
    }

    /** 进行中的传输，供界面显示进度条。 */
    public static final class Transfer {
        public final String name;
        public final long done;
        public final long total;

        Transfer(String name, long done, long total) {
            this.name = name;
            this.done = done;
            this.total = total;
        }
    }

    /** 一条文字消息。 */
    public static final class Message {
        public final String text;
        public final long time;
        public final boolean fromPhone;

        Message(String text, long time, boolean fromPhone) {
            this.text = text;
            this.time = time;
            this.fromPhone = fromPhone;
        }
    }

    // ---- 上限 ----

    static final int MAX_REQUEST_LINE = 4096;
    static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int MAX_HEADERS = 48;
    static final long MAX_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 64 * 1024;
    static final int MAX_MESSAGES = 200;
    static final int MAX_CONNECTIONS = 12;
    static final int SOCKET_TIMEOUT_MS = 30_000;

    private final String token;
    private final Store store;
    private final String page;
    private final Listener listener;
    private final InetAddress bindAddress;
    private final int requestedPort;
    private final long maxUploadBytes;

    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, SharedFile> picked = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<Socket> sockets = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger ids = new AtomicInteger();

    /** 进行中的上传：唯一名 → {已收字节, 总字节}。用于界面进度。 */
    private final Map<String, long[]> inflight = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 「选名 + 建文档」的互斥锁，以及正在上传中的名字，防止并发同名互相覆盖。 */
    private final Object nameLock = new Object();
    private final Set<String> reserved = new LinkedHashSet<>();

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile long lastActivity = System.currentTimeMillis();
    private Thread acceptThread;

    public LanServer(String token, Store store, String page, Listener listener,
                     InetAddress bindAddress, int port) {
        this(token, store, page, listener, bindAddress, port, MAX_UPLOAD_BYTES);
    }

    public LanServer(String token, Store store, String page, Listener listener,
                     InetAddress bindAddress, int port, long maxUploadBytes) {
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("会话令牌太弱");
        }
        this.token = token;
        this.store = store;
        this.page = page == null ? "" : page;
        this.listener = listener == null ? event -> { } : listener;
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.maxUploadBytes = Math.min(maxUploadBytes, MAX_UPLOAD_BYTES);
    }

    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null ? requestedPort : socket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    /** 同步启动：绑定失败立刻抛出，界面才能显示真正原因（端口占用等）。 */
    public void start() throws IOException {
        if (running) {
            return;
        }
        if (bindAddress == null) {
            throw new IOException("没有可用的局域网地址，请先连接 Wi-Fi 或开热点。");
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindAddress, requestedPort), 8);
        serverSocket = socket;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "lanshare-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        listener.onEvent(Event.of(Event.Kind.STARTED,
                bindAddress.getHostAddress() + ":" + socket.getLocalPort()));
    }

    public void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        // 关掉所有连接：进行中的上传会收到 IOException，从而删除半成品。
        synchronized (sockets) {
            for (Socket socket : sockets) {
                closeQuietly(socket);
            }
            sockets.clear();
        }
        Thread accept = acceptThread;
        acceptThread = null;
        if (accept != null) {
            accept.interrupt();
        }
        listener.onEvent(Event.of(Event.Kind.STOPPED, "已停止，端口已释放"));
    }

    /** 加入一个用户明确选中的文件。只共享这一个文件，不公开任何目录。 */
    public String share(String displayName, long size, Source source) {
        String name = LanNames.sanitize(displayName);
        if (name.isEmpty()) {
            name = LanNames.FALLBACK;
        }
        String id = "p" + ids.incrementAndGet();
        picked.put(id, new SharedFile(id, name, size, false, null, source));
        return id;
    }

    /** 撤下用户挑的一个文件（只撤链接，原件不动）。 */
    public boolean unshare(String id) {
        return picked.remove(id) != null;
    }

    /** 手机侧发一条文字（浏览器会在轮询里看到）。 */
    public void sendText(String text) {
        addMessage(text, true);
    }

    public List<Message> messages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    /** 当前进行中的上传；没有则为空列表。 */
    public List<Transfer> transfers() {
        List<Transfer> result = new ArrayList<>();
        synchronized (inflight) {
            for (Map.Entry<String, long[]> entry : inflight.entrySet()) {
                long[] value = entry.getValue();
                result.add(new Transfer(entry.getKey(), value[0], value[1]));
            }
        }
        return result;
    }

    /** 最后一次有请求活动的时间；闲置超时据此释放端口。 */
    public long lastActivity() {
        return lastActivity;
    }

    /**
     * 闲置判定。抽成静态纯函数，这样「闲置到底会不会停」可以被直接断言，
     * 而不是靠在测试里真的等十分钟。
     *
     * 正在传输时不算闲置：一个上传可能持续好几分钟，而活动时间只在请求头到达时更新。
     */
    public static boolean idleExpired(long lastActivityMs, long nowMs, boolean transferring,
            long timeoutMs) {
        if (transferring || lastActivityMs <= 0 || timeoutMs <= 0) {
            return false;
        }
        return nowMs - lastActivityMs > timeoutMs;
    }

    /** 所有可下载文件（手机挑的 + 浏览器上传的）。 */
    public List<SharedFile> files() {
        return allFiles();
    }

    /** 下载 URL 里的路径片段，供界面展示。 */
    public String urlPath() {
        return "/t/" + token + "/";
    }

    private void addMessage(String text, boolean fromPhone) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String trimmed = text.length() > 4000 ? text.substring(0, 4000) : text;
        messages.add(new Message(trimmed, System.currentTimeMillis(), fromPhone));
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    // ---------------- 连接循环 ----------------

    private void acceptLoop() {
        int failures = 0;
        while (running) {
            ServerSocket listenerSocket = serverSocket;
            if (listenerSocket == null) {
                return;
            }
            try {
                Socket socket = listenerSocket.accept();
                if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                    connections.decrementAndGet();
                    // 超限时直接关掉，不做任何应答：不给对方留着连接慢慢耗。
                    closeQuietly(socket);
                    continue;
                }
                sockets.add(socket);
                Thread worker = new Thread(() -> serve(socket), "lanshare-conn");
                worker.setDaemon(true);
                worker.start();
                failures = 0;
            } catch (IOException ex) {
                if (!running) {
                    return;
                }
                failures++;
                try {
                    Thread.sleep(Math.min(50L * failures, 1000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            Head head = readHead(in);
            if (head == null) {
                return;
            }
            lastActivity = System.currentTimeMillis();
            route(head, in, out);
            out.flush();
        } catch (IOException | RuntimeException ex) {
            // 客户端断开、取消上传都走这里：静默结束，不给对方回错误页。
        } finally {
            sockets.remove(socket);
            connections.decrementAndGet();
            closeQuietly(socket);
        }
    }

    // ---------------- 请求头解析 ----------------

    static final class Head {
        String method = "";
        String target = "";
        String path = "";
        String query = "";
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        long contentLength = -1;

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        int count(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? 0 : values.size();
        }

        Map<String, String> queryParams() {
            Map<String, String> result = new LinkedHashMap<>();
            if (query == null || query.isEmpty()) {
                return result;
            }
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                result.put(decode(key), decode(value));
            }
            return result;
        }

        private static String decode(String text) {
            try {
                return URLDecoder.decode(text, StandardCharsets.UTF_8.name());
            } catch (Exception ex) {
                return "";
            }
        }
    }

    static Head readHead(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        List<String> lines = new ArrayList<>();
        int total = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            total++;
            if (total > MAX_HEADER_BYTES) {
                throw new IOException("请求头超出上限");
            }
            if (b == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1.name());
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                if (text.isEmpty()) {
                    break;
                }
                lines.add(text);
                line.reset();
                if (lines.size() > MAX_HEADERS + 1) {
                    throw new IOException("请求头行数超出上限");
                }
            } else {
                if (line.size() > MAX_REQUEST_LINE) {
                    throw new IOException("请求行过长");
                }
                line.write(b);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        Head head = new Head();
        String[] parts = lines.get(0).split(" ");
        if (parts.length != 3) {
            throw new IOException("请求行格式不正确");
        }
        head.method = parts[0].toUpperCase(Locale.ROOT);
        head.target = parts[1];
        String target = head.target;
        int question = target.indexOf('?');
        head.path = question < 0 ? target : target.substring(0, question);
        head.query = question < 0 ? "" : target.substring(question + 1);
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i);
            int colon = text.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = text.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = text.substring(colon + 1).trim();
            head.headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        String length = head.header("content-length");
        if (length != null) {
            try {
                head.contentLength = Long.parseLong(length.trim());
            } catch (NumberFormatException ex) {
                head.contentLength = -2;
            }
            if (head.contentLength < 0) {
                head.contentLength = -2;
            }
        }
        return head;
    }

    // ---------------- 路由 ----------------

    private void route(Head head, InputStream in, OutputStream out) throws IOException {
        String prefix = "/t/" + token;
        if (!authorised(head.path)) {
            // 令牌不对（或没有令牌）：404，不告诉对方这是不是一个服务。
            notFound(out);
            return;
        }
        // 路径里的穿越直接拒绝：路由片段不该含任何目录语义。
        // 查询串里的文件名不用拒绝，因为它从不拼成路径 —— 名字由 LanNames.sanitize 削成单段，
        // 真正的落盘位置由 SAF 决定。拒绝反而会把「拖一个文件夹进来」变成一堆 400。
        if (hitTraversal(head.path)) {
            fail(out, 400, "路径不合法");
            return;
        }
        if (!sameOrigin(head)) {
            listener.onEvent(Event.of(Event.Kind.REJECTED, "跨站请求被拒绝"));
            fail(out, 403, "拒绝跨站请求");
            return;
        }
        String rest = head.path.substring(prefix.length());
        // /t/<token> 不带斜杠时补一个：页面里的相对路径才能正确解析。
        if (!rest.startsWith("/")) {
            redirect(out, prefix + "/");
            return;
        }
        String body = rest.substring(1);
        if (body.isEmpty()) {
            page(out);
            return;
        }
        String[] segments = body.split("/", -1);
        String first = segments[0];
        if (first.equals("api") && segments.length >= 2) {
            api(head, segments[1], in, out);
            return;
        }
        if (first.equals("f") && segments.length == 2 && !segments[1].isEmpty()) {
            // 浏览器可能把 id 做百分号编码；解码一次再比对，不多解（防二次编码绕过）。
            download(decodeSegment(segments[1]), out);
            return;
        }
        // 其余路径一律回到页面：这是个单页应用，没有别的资源可以访问。
        page(out);
    }

    /** 只解一次百分号编码；解不开就原样返回，让路由按未命中处理。 */
    private static String decodeSegment(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            return text;
        }
    }

    private void api(Head head, String action, InputStream in, OutputStream out) throws IOException {
        switch (action) {
            case "state":
                if (!head.method.equals("GET")) {
                    fail(out, 405, "只支持 GET");
                    return;
                }
                state(out);
                return;
            case "text":
                if (!head.method.equals("POST")) {
                    fail(out, 405, "只支持 POST");
                    return;
                }
                if (!requirePostGuard(head, out)) {
                    return;
                }
                text(head, in, out);
                return;
            case "upload":
                if (!head.method.equals("POST")) {
                    fail(out, 405, "只支持 POST");
                    return;
                }
                if (!requirePostGuard(head, out)) {
                    return;
                }
                upload(head, in, out);
                return;
            case "delete":
                if (!head.method.equals("POST")) {
                    fail(out, 405, "只支持 POST");
                    return;
                }
                if (!requirePostGuard(head, out)) {
                    return;
                }
                deleteUploaded(head, in, out);
                return;
            default:
                notFound(out);
        }
    }

    /**
     * 状态变更请求的守卫。
     *
     * 自定义头 X-LanShare: 1 会让浏览器先发预检，而我们从不回 CORS 头，
     * 于是别的站点发来的跨源请求在浏览器侧就发不出去；再加上 Origin 校验，
     * 简单表单跨站提交也进不来。
     */
    private boolean requirePostGuard(Head head, OutputStream out) throws IOException {
        if (!"1".equals(head.header("x-lanshare"))) {
            listener.onEvent(Event.of(Event.Kind.REJECTED, "缺少同源标记，已拒绝"));
            fail(out, 403, "缺少同源标记");
            return false;
        }
        String origin = head.header("origin");
        if (origin != null) {
            String host = head.header("host");
            if (host == null || !origin.equalsIgnoreCase("http://" + host)) {
                listener.onEvent(Event.of(Event.Kind.REJECTED, "Origin 不符，已拒绝"));
                fail(out, 403, "拒绝跨站请求");
                return false;
            }
        }
        if (head.count("content-length") > 1) {
            fail(out, 400, "Content-Length 重复");
            return false;
        }
        if (head.header("transfer-encoding") != null) {
            // 不实现分块传输：宁可明确拒绝，也不猜边界。
            fail(out, 501, "不支持分块传输，请带 Content-Length");
            return false;
        }
        if (head.contentLength < 0) {
            fail(out, 411, "缺少 Content-Length");
            return false;
        }
        return true;
    }

    /**
     * 令牌校验：按前缀逐字符做定长比较，不去用 String.startsWith。
     *
     * 这里不是防本机计时攻击（局域网里意义有限），而是为了不因为「路径前缀刚好匹配」
     * 就把 /t/<正确令牌>extra 也当成合法请求 —— 前缀必须是完整的一段。
     */
    private boolean authorised(String path) {
        String prefix = "/t/" + token;
        if (path.length() < prefix.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < prefix.length(); i++) {
            diff |= prefix.charAt(i) ^ path.charAt(i);
        }
        if (diff != 0) {
            return false;
        }
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    private boolean sameOrigin(Head head) {
        String origin = head.header("origin");
        if (origin == null) {
            return true;
        }
        String host = head.header("host");
        return host != null && origin.equalsIgnoreCase("http://" + host);
    }

    static boolean hitTraversal(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("../") || lower.contains("/..") || lower.endsWith("/..")
                || lower.contains("%2e%2e") || lower.contains("..%2f") || lower.contains("%2f..")
                || lower.contains("\\..") || lower.contains("..\\");
    }

    // ---------------- 各接口 ----------------

    private void page(OutputStream out) throws IOException {
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        head(out, "200 OK", "text/html; charset=utf-8", bytes.length);
        out.write(bytes);
    }

    private void state(OutputStream out) throws IOException {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\"files\":[");
        boolean first = true;
        List<SharedFile> all = allFiles();
        for (SharedFile file : all) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":\"").append(json(file.id))
                    .append("\",\"name\":\"").append(json(file.name))
                    .append("\",\"size\":").append(file.size)
                    .append(",\"uploaded\":").append(file.uploaded)
                    .append('}');
        }
        json.append("],\"messages\":[");
        first = true;
        synchronized (messages) {
            for (Message message : messages) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"text\":\"").append(json(message.text))
                        .append("\",\"time\":").append(message.time)
                        .append(",\"phone\":").append(message.fromPhone)
                        .append('}');
            }
        }
        json.append("],\"maxUpload\":").append(maxUploadBytes).append('}');
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        head(out, "200 OK", "application/json; charset=utf-8", bytes.length);
        out.write(bytes);
    }

    private void text(Head head, InputStream in, OutputStream out) throws IOException {
        if (head.contentLength > MAX_TEXT_BYTES) {
            fail(out, 413, "文字太长");
            return;
        }
        byte[] body = readBounded(in, (int) Math.max(0, head.contentLength));
        if (body == null) {
            fail(out, 400, "内容不完整");
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            fail(out, 400, "内容为空");
            return;
        }
        addMessage(text, false);
        listener.onEvent(new Event(Event.Kind.TEXT, "电脑", 0, 0, text));
        ok(out);
    }

    private void upload(Head head, InputStream in, OutputStream out) throws IOException {
        Map<String, String> query = head.queryParams();
        String requested = query.get("name");
        String name = LanNames.sanitize(requested);
        if (name.isEmpty()) {
            name = LanNames.sanitizeOrFallback(requested, System.currentTimeMillis());
        }
        if (head.contentLength > maxUploadBytes) {
            fail(out, 413, "文件超过本次上限");
            return;
        }
        // 名字选择与创建必须原子：两个并发上传同名文件时，各自 existingNames() 都看不到对方，
        // 会同时选中 "a.txt" 而互相覆盖。这里用一把锁只在「选名 + 建文档」这段持有，传输本身不上锁。
        String unique;
        Sink sink = null;
        synchronized (nameLock) {
            java.util.Set<String> taken = new java.util.LinkedHashSet<>(store.existingNames());
            taken.addAll(reserved);
            unique = LanNames.unique(name, taken);
            reserved.add(unique);
        }
        inflight.put(unique, new long[]{0, head.contentLength});
        listener.onEvent(new Event(Event.Kind.UPLOAD_START, unique, 0, head.contentLength, ""));
        OutputStream fileOut = null;
        boolean committed = false;
        try {
            synchronized (nameLock) {
                sink = store.create(unique, head.contentLength);
            }
            fileOut = sink.open();
            long written = copyBounded(in, fileOut, head.contentLength, (bytes) -> {
                synchronized (inflight) {
                    long[] slot = inflight.get(unique);
                    if (slot != null) {
                        slot[0] = bytes;
                    }
                }
                listener.onEvent(new Event(Event.Kind.UPLOAD_PROGRESS, unique,
                        bytes, head.contentLength, ""));
            });
            fileOut.flush();
            fileOut.close();
            fileOut = null;
            if (written != head.contentLength) {
                throw new IOException("连接提前断开（收到 " + written + "/" + head.contentLength + " 字节）");
            }
            sink.commit();
            committed = true;
            listener.onEvent(new Event(Event.Kind.UPLOAD_DONE, unique, head.contentLength,
                    head.contentLength, ""));
            ok(out);
        } catch (IOException | RuntimeException ex) {
            // 失败/取消：删掉这次新建的半成品。上面的 unique 名是新的，所以绝不会误删已有文件。
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            listener.onEvent(new Event(Event.Kind.UPLOAD_FAILED, unique, 0, head.contentLength, reason));
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex);
        } finally {
            inflight.remove(unique);
            synchronized (nameLock) {
                reserved.remove(unique);
            }
            if (fileOut != null) {
                closeQuietly(fileOut);
            }
            if (sink != null && !committed) {
                try {
                    sink.abort();
                } catch (RuntimeException ignored) {
                    // abort 失败不该盖掉原始错误。
                }
            }
        }
    }

    private void deleteUploaded(Head head, InputStream in, OutputStream out) throws IOException {
        Map<String, String> query = head.queryParams();
        String id = query.get("id");
        SharedFile found = null;
        for (SharedFile file : store.uploaded()) {
            if (file.id.equals(id)) {
                found = file;
                break;
            }
        }
        if (found == null) {
            fail(out, 404, "没有这个文件");
            return;
        }
        readBounded(in, (int) Math.max(0, head.contentLength));
        if (!store.delete(found)) {
            fail(out, 500, "删除失败");
            return;
        }
        ok(out);
    }

    private void download(String id, OutputStream out) throws IOException {
        SharedFile file = null;
        for (SharedFile candidate : allFiles()) {
            if (candidate.id.equals(id)) {
                file = candidate;
                break;
            }
        }
        if (file == null) {
            notFound(out);
            return;
        }
        InputStream source;
        try {
            source = file.uploaded ? store.open(file) : file.source.open();
        } catch (IOException ex) {
            fail(out, 500, "文件打不开了（可能已被移动或删除）");
            return;
        }
        if (source == null) {
            fail(out, 500, "文件打不开了（可能已被移动或删除）");
            return;
        }
        try (InputStream stream = source) {
            // 大小未知时不发 Content-Length，由关闭连接表示结束（浏览器接受这种结尾）。
            long size = file.size >= 0 ? file.size : -1;
            downloadHead(out, contentType(file.name), size, file.name);
            long sent = 0;
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                sent += read;
            }
            out.flush();
            listener.onEvent(new Event(Event.Kind.DOWNLOAD, file.name, sent, size, ""));
        }
    }

    private List<SharedFile> allFiles() {
        List<SharedFile> result = new ArrayList<>();
        try {
            result.addAll(store.uploaded());
        } catch (RuntimeException ignored) {
            // 列目录失败不该让整页报错；至少挑来的文件还能下载。
        }
        synchronized (picked) {
            result.addAll(picked.values());
        }
        return result;
    }

    // ---------------- 读写小工具 ----------------

    private static byte[] readBounded(InputStream in, int length) throws IOException {
        if (length < 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }
        return buffer;
    }

    private interface Progress {
        void at(long bytes);
    }

    private static long copyBounded(InputStream in, OutputStream out, long length, Progress progress)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long written = 0;
        long nextReport = 256 * 1024;
        while (written < length) {
            int want = (int) Math.min(buffer.length, length - written);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            written += read;
            if (written >= nextReport || written == length) {
                nextReport = written + 256 * 1024;
                progress.at(written);
            }
        }
        return written;
    }

    private static void head(OutputStream out, String status, String type, long length) throws IOException {
        StringBuilder text = new StringBuilder(320);
        text.append("HTTP/1.1 ").append(status).append("\r\n");
        text.append("Content-Type: ").append(type).append("\r\n");
        // 长度未知时干脆不发这个头：发 0 会让浏览器以为没有内容。
        if (length >= 0) {
            text.append("Content-Length: ").append(length).append("\r\n");
        }
        text.append("Cache-Control: no-store\r\n");
        text.append("X-Content-Type-Options: nosniff\r\n");
        text.append("X-Frame-Options: DENY\r\n");
        text.append("Referrer-Policy: no-referrer\r\n");
        // 不用外网资源：样式和脚本都在页面里内联，所以这里必须放行内联，其余一律禁止。
        text.append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; "
                + "script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:\r\n");
        text.append("Connection: close\r\n\r\n");
        out.write(text.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void downloadHead(OutputStream out, String type, long length, String name) throws IOException {
        byte[] bytes = type.getBytes(StandardCharsets.ISO_8859_1);
        String encoded = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20");
        StringBuilder text = new StringBuilder(320);
        text.append("HTTP/1.1 200 OK\r\n");
        text.append("Content-Type: ").append(new String(bytes, StandardCharsets.ISO_8859_1)).append("\r\n");
        if (length >= 0) {
            text.append("Content-Length: ").append(length).append("\r\n");
        }
        text.append("Content-Disposition: attachment; filename*=UTF-8''").append(encoded).append("\r\n");
        text.append("Cache-Control: no-store\r\n");
        text.append("X-Content-Type-Options: nosniff\r\n");
        text.append("Connection: close\r\n\r\n");
        out.write(text.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void ok(OutputStream out) throws IOException {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        head(out, "200 OK", "application/json; charset=utf-8", body.length);
        out.write(body);
    }

    private static void fail(OutputStream out, int code, String message) throws IOException {
        String reason;
        switch (code) {
            case 400: reason = "Bad Request"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            case 411: reason = "Length Required"; break;
            case 413: reason = "Payload Too Large"; break;
            case 501: reason = "Not Implemented"; break;
            default: reason = "Error"; break;
        }
        String text = "{\"ok\":false,\"error\":\"" + json(message) + "\"}";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        head(out, code + " " + reason, "application/json; charset=utf-8", bytes.length);
        out.write(bytes);
    }

    private static void notFound(OutputStream out) throws IOException {
        fail(out, 404, "没有这个资源");
    }

    private static void redirect(OutputStream out, String location) throws IOException {
        byte[] body = ("<p>跳转到 <a href=\"" + location + "\">" + location + "</a></p>")
                .getBytes(StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder(200);
        text.append("HTTP/1.1 302 Found\r\n");
        text.append("Location: ").append(location).append("\r\n");
        text.append("Content-Type: text/html; charset=utf-8\r\n");
        text.append("Content-Length: ").append(body.length).append("\r\n");
        text.append("Cache-Control: no-store\r\n");
        text.append("Connection: close\r\n\r\n");
        out.write(text.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        switch (extension) {
            case "txt": case "md": case "log": case "csv": return "text/plain; charset=utf-8";
            case "html": case "htm": return "text/html; charset=utf-8";
            case "json": return "application/json; charset=utf-8";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "pdf": return "application/pdf";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "zip": return "application/zip";
            default: return "application/octet-stream";
        }
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f || c == '<' || c == '>' || c == '&') {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException ignored) {
            // 关闭失败没有可做的补救动作。
        }
    }
}
