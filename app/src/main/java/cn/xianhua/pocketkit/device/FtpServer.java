package cn.xianhua.pocketkit.device;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 081 局域网 FTP 共享：最小可用的 FTP 服务端（RFC 959 子集）。
 *
 * 只有 java.* 依赖，因此可以在普通 JVM 上跑起来用真实 FTP 客户端验证。
 *
 * 安全设计（对应任务要求）：
 *  - 任何路径都先做 canonical 化，并校验仍位于分享根目录之内，杜绝 ../ 穿越与符号链接逃逸；
 *  - 只允许「下载 + 上传」，不允许删除、重命名、改权限、SITE 等破坏性命令；
 *  - 会话凭证由调用方随机生成，一次一密。
 *
 * 已知限制（明确写出，不假装）：
 *  - FTP 是**明文**协议，用户名、密码、文件内容在局域网上都是明文，会被人抓包看到；
 *  - 只实现了 PASV 被动模式；部分老客户端只支持 PORT 主动模式，连不上；
 *  - 不支持 TLS（没有实现 FTPS），不支持断点续传（REST）。
 */
public final class FtpServer {

    /** 服务端事件回调，用于把状态显示到界面而不写日志文件。 */
    public interface Listener {
        void onLog(String message);

        void onClientCount(int count);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GBK = Charset.forName("GBK");

    /** 单条连接的登录失败上限：达到就回 421 并断开，不给「无限次试探」的免费通道。 */
    static final int MAX_AUTH_FAILURES = 5;

    private final File root;
    private final String username;
    private final String password;
    private final int port;
    private final Listener listener;
    /** 只绑定这一个地址，而不是 0.0.0.0：避免在蜂窝网 / 全局 IPv6 上意外暴露服务。 */
    private final InetAddress bindAddress;
    private final List<Socket> openSockets = Collections.synchronizedList(new ArrayList<>());

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private int clients;

    public FtpServer(File root, String username, String password, int port,
                     InetAddress bindAddress, Listener listener) {
        this.root = root;
        this.username = username;
        this.password = password;
        this.port = port;
        this.bindAddress = bindAddress;
        this.listener = listener;
    }

    /** 不带地址的构造器只在测试里用，绑定回环地址，绝不默认监听所有网卡。 */
    public FtpServer(File root, String username, String password, int port, Listener listener) {
        this(root, username, password, port, InetAddress.getLoopbackAddress(), listener);
    }

    /**
     * 实际监听端口。
     * 端口传 0 时由系统分配，所以必须回读绑定后的真实端口，否则界面会显示 0。
     */
    public int port() {
        ServerSocket socket = serverSocket;
        return socket == null ? port : socket.getLocalPort();
    }

    public String username() {
        return username;
    }

    public boolean isRunning() {
        return running;
    }

    /** 同步启动：绑定失败会立刻抛出，界面才能显示真正的原因（端口占用等）。 */
    public void start() throws IOException {
        if (running) {
            return;
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("分享目录无法创建：" + root.getAbsolutePath());
        }
        if (bindAddress == null) {
            throw new IOException("没有可用的局域网地址，无法共享。请先连接到 Wi-Fi。");
        }
        serverSocket = new ServerSocket(port, 8, bindAddress);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ftp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("已开始监听 " + bindAddress.getHostAddress() + ":" + port
                + "，分享目录：" + root.getAbsolutePath());
    }

    public void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        synchronized (openSockets) {
            for (Socket s : openSockets) {
                closeQuietly(s);
            }
            openSockets.clear();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        log("已停止。");
    }

    /**
     * 接受连接。
     *
     * 只有 stop() 把 running 置 false 才结束循环。早先是「任何 IOException 都 return」，
     * 于是一次瞬时错误（系统回收端口、并发 accept 抖动）之后就再没有线程在 accept：
     * isRunning() 仍是 true、界面仍显示运行中，但新连接全部失败 —— 服务假活。
     *
     * serverSocket 被 stop() 关掉时 accept 会抛错，靠 running 判断正常退出；
     * 其它错误记一次日志后继续，但连续失败时退避，避免变成 IO 异常热循环。
     */
    private void acceptLoop() {
        int consecutiveFailures = 0;
        while (running) {
            // 取局部引用：stop() 会把字段置 null，直接字段调用会 NPE 打死线程。
            ServerSocket listener = serverSocket;
            if (listener == null) {
                return;
            }
            try {
                Socket socket = listener.accept();
                openSockets.add(socket);
                consecutiveFailures = 0;
                Thread t = new Thread(() -> handleClient(socket), "ftp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException ex) {
                if (!running) {
                    return;
                }
                consecutiveFailures++;
                if (consecutiveFailures == 1 || consecutiveFailures % 50 == 0) {
                    log("接受连接失败（连续 " + consecutiveFailures + " 次）：" + ex.getMessage());
                }
                // 瞬时错误退避（最多 1s），否则套接字半坏时会变成 IO 异常热循环烧 CPU。
                try {
                    Thread.sleep(Math.min(50L * consecutiveFailures, 1000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (listener.isClosed()) {
                    // 监听套接字确实不可用了：走正常停止流程，把 running、客户端连接一起清干净，
                    // 否则界面会一直显示「运行中」而实际上没有线程在 accept。
                    stop();
                    return;
                }
            }
        }
    }

    private void handleClient(Socket control) {
        clients++;
        listener.onClientCount(clients);
        Session session = new Session(control);
        try {
            session.run();
        } catch (IOException ex) {
            log("会话结束：" + ex.getMessage());
        } finally {
            session.close();
            openSockets.remove(control);
            clients--;
            listener.onClientCount(clients);
        }
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理。
            }
        }
    }

    /** 单个控制连接的状态机。 */
    private final class Session {

        private final Socket control;
        private BufferedReader in;
        private OutputStream out;
        private boolean authenticated;
        private int authFailures;
        private String pendingUser;
        private File cwd;
        private boolean binary = true;
        private ServerSocket passive;
        private Socket data;

        Session(Socket control) {
            this.control = control;
            this.cwd = root;
        }

        void run() throws IOException {
            control.setSoTimeout(300_000);
            in = new BufferedReader(new InputStreamReader(control.getInputStream(), UTF8));
            out = control.getOutputStream();
            reply(220, "PocketKit FTP 就绪（明文传输，仅限本机局域网内临时使用）");

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int space = line.indexOf(' ');
                String command = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.US);
                String argument = space < 0 ? "" : line.substring(space + 1);
                if (!dispatch(command, argument)) {
                    return;
                }
            }
        }

        /** 返回 false 表示结束会话。 */
        private boolean dispatch(String command, String argument) throws IOException {
            switch (command) {
                case "USER":
                    pendingUser = argument;
                    reply(331, "需要密码");
                    return true;
                case "PASS":
                    // 用户名与密码都用常量时间比较，避免时序差异泄漏凭证。
                    if (constantTimeEquals(pendingUser, username) && constantTimeEquals(argument, password)) {
                        authenticated = true;
                        reply(230, "登录成功");
                        return true;
                    }
                    authenticated = false;
                    // 口令是随机的、本身够强，缺的是摩擦成本：不限次数时抓到一次握手就能持续重放。
                    if (++authFailures >= MAX_AUTH_FAILURES) {
                        reply(421, "尝试次数过多，连接关闭");
                        return false;
                    }
                    reply(530, "用户名或密码错误");
                    return true;
                case "QUIT":
                    reply(221, "再见");
                    return false;
                case "NOOP":
                    reply(200, "OK");
                    return true;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    return true;
                case "FEAT":
                    // 客户端据此决定是否用 UTF-8 路径。
                    multiReply("211-Features:", " UTF8", " PASV", " SIZE", "211 End");
                    return true;
                case "OPTS":
                    reply(200, "OK");
                    return true;
                case "TYPE":
                    binary = !argument.toUpperCase(Locale.US).startsWith("A");
                    reply(200, binary ? "切换到二进制模式" : "切换到 ASCII 模式");
                    return true;
                case "PWD":
                case "XPWD":
                    if (!requireAuth()) {
                        return true;
                    }
                    reply(257, "\"" + displayPath() + "\" 是当前目录");
                    return true;
                case "CWD":
                case "XCWD":
                    if (!requireAuth()) {
                        return true;
                    }
                    File target = resolve(argument);
                    if (target == null || !target.isDirectory()) {
                        reply(550, "目录不存在或不可访问");
                    } else {
                        cwd = target;
                        reply(250, "已切换到 " + displayPath());
                    }
                    return true;
                case "CDUP":
                    if (!requireAuth()) {
                        return true;
                    }
                    cwd = cwd.getParentFile() != null && isInsideRoot(cwd.getParentFile())
                            ? cwd.getParentFile() : root;
                    reply(250, "已切换到 " + displayPath());
                    return true;
                case "PASV":
                    if (!requireAuth()) {
                        return true;
                    }
                    return openPassive();
                case "LIST":
                case "NLST":
                    if (!requireAuth()) {
                        return true;
                    }
                    return sendListing(command.equals("NLST"), argument);
                case "SIZE":
                    if (!requireAuth()) {
                        return true;
                    }
                    respondSize(argument);
                    return true;
                case "RETR":
                    if (!requireAuth()) {
                        return true;
                    }
                    return sendFile(argument);
                case "STOR":
                    if (!requireAuth()) {
                        return true;
                    }
                    return receiveFile(argument);
                case "DELE":
                case "RMD":
                case "XRMD":
                case "RNFR":
                case "RNTO":
                case "MKD":
                case "XMKD":
                case "SITE":
                case "CHMOD":
                    // 明确拒绝破坏性操作：共享是只读加新增，不改动既有内容。
                    reply(502, "本服务不提供删除、改名、新建目录等操作");
                    return true;
                case "PORT":
                case "EPRT":
                    // 主动模式需要服务端回连客户端，在手机上既不可靠也不安全。
                    reply(502, "只支持被动模式（PASV），请把客户端设为被动模式");
                    return true;
                default:
                    reply(502, "未实现的命令：" + command);
                    return true;
            }
        }

        private boolean requireAuth() throws IOException {
            if (!authenticated) {
                reply(530, "请先登录");
                return false;
            }
            return true;
        }

        private boolean openPassive() throws IOException {
            closePassive();
            try {
                // 绑定本机与客户端通信所用的网卡地址，多网卡时客户端才能连回来。
                passive = new ServerSocket(0, 1, control.getLocalAddress());
            } catch (IOException ex) {
                reply(425, "无法开启被动端口：" + ex.getMessage());
                return true;
            }
            InetAddress address = control.getLocalAddress();
            String host = address.getHostAddress().replace('.', ',');
            reply(227, "进入被动模式 (" + host + "," + (passive.getLocalPort() / 256)
                    + "," + (passive.getLocalPort() % 256) + ")");
            return true;
        }

        /** 数据连接只接受一次，超时即放弃，避免半开连接把线程挂住。 */
        private Socket acceptData() throws IOException {
            if (passive == null) {
                reply(425, "请先发送 PASV");
                return null;
            }
            try {
                passive.setSoTimeout(30_000);
                data = passive.accept();
                openSockets.add(data);
                return data;
            } catch (IOException ex) {
                reply(425, "数据连接失败：" + ex.getMessage());
                return null;
            } finally {
                closePassive();
            }
        }

        private boolean sendListing(boolean namesOnly, String argument) throws IOException {
            File dir = argument.isEmpty() ? cwd : resolve(argument);
            if (dir == null || !dir.isDirectory()) {
                reply(550, "目录不存在或不可访问");
                return true;
            }
            reply(150, "开始传输目录列表");
            Socket socket = acceptData();
            if (socket == null) {
                return true;
            }
            try (BufferedOutputStream dataOut = new BufferedOutputStream(socket.getOutputStream())) {
                SimpleDateFormat format = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        String name = child.getName();
                        if (namesOnly) {
                            dataOut.write((name + "\r\n").getBytes(UTF8));
                        } else {
                            String line = String.format(Locale.US, "%srw-r--r-- 1 pocketkit pocketkit %13d %s %s\r\n",
                                    child.isDirectory() ? "d" : "-",
                                    child.length(),
                                    format.format(new Date(child.lastModified())),
                                    name);
                            dataOut.write(line.getBytes(UTF8));
                        }
                    }
                }
                dataOut.flush();
            } finally {
                closeData();
            }
            reply(226, "传输完成");
            return true;
        }

        private void respondSize(String argument) throws IOException {
            File file = resolve(argument);
            if (file == null || !file.isFile()) {
                reply(550, "文件不存在");
            } else {
                reply(213, String.valueOf(file.length()));
            }
        }

        private boolean sendFile(String argument) throws IOException {
            File file = resolve(argument);
            if (file == null || !file.isFile()) {
                reply(550, "文件不存在或不可读");
                return true;
            }
            reply(150, "开始传输 " + file.length() + " 字节");
            Socket socket = acceptData();
            if (socket == null) {
                return true;
            }
            long sent = 0;
            try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file));
                 BufferedOutputStream dataOut = new BufferedOutputStream(socket.getOutputStream())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = fileIn.read(buffer)) > 0) {
                    dataOut.write(buffer, 0, read);
                    sent += read;
                }
                dataOut.flush();
            } catch (IOException ex) {
                reply(426, "传输中断：" + ex.getMessage());
                closeData();
                return true;
            } finally {
                closeData();
            }
            reply(226, "传输完成，共 " + sent + " 字节");
            log("下载 " + file.getName() + "（" + sent + " 字节）");
            return true;
        }

        private boolean receiveFile(String argument) throws IOException {
            File file = resolve(argument);
            if (file == null) {
                reply(550, "路径不可用");
                return true;
            }
            File parent = file.getParentFile();
            if (parent == null || !parent.isDirectory()) {
                reply(550, "目标目录不存在");
                return true;
            }
            reply(150, "可以开始上传");
            Socket socket = acceptData();
            if (socket == null) {
                return true;
            }
            // 关键：先写到同目录下的临时文件，**传完再整体替换**目标文件。
            // 直接写目标文件的话，上传中途断线 / 切页面 / 进程被杀都会把原文件截断，
            // 用户原来那份就毁了。同目录保证 rename 在同一文件系统上，才能原子替换。
            File temp = new File(parent, ".pk-upload-" + System.nanoTime() + ".part");
            long received = 0;
            IOException failure = null;
            try (InputStream dataIn = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = dataIn.read(buffer)) > 0) {
                    fileOut.write(buffer, 0, read);
                    received += read;
                }
                fileOut.flush();
            } catch (IOException ex) {
                failure = ex;
            } finally {
                closeData();
            }

            if (failure != null) {
                // 上传失败：删掉本次临时文件，**原文件保持不动**。
                deleteQuietly(temp);
                reply(426, "上传中断：" + failure.getMessage() + "（原文件未改动）");
                return true;
            }

            // 完整接收后才提交。提交是原子的：要么换成新内容，要么原样保留，
            // 不存在"旧的被删了、新的又没写进去"的中间状态。
            String commitError = commitUpload(temp, file);
            if (commitError != null) {
                deleteQuietly(temp);
                reply(550, commitError);
                return true;
            }
            reply(226, "上传完成，共 " + received + " 字节");
            log("上传 " + file.getName() + "（" + received + " 字节）");
            return true;
        }

        private void deleteQuietly(File file) {
            if (file != null && file.exists() && !file.delete()) {
                log("临时文件删除失败，请手动清理：" + file.getName());
            }
        }
        private String displayPath() {
            String canonicalRoot = canonical(root);
            String canonicalCwd = canonical(cwd);
            if (canonicalRoot == null || canonicalCwd == null || canonicalCwd.length() <= canonicalRoot.length()) {
                return "/";
            }
            String relative = canonicalCwd.substring(canonicalRoot.length()).replace(File.separatorChar, '/');
            return relative.isEmpty() ? "/" : relative;
        }

        /**
         * 把客户端给的路径解析成真实文件，并确保它没有逃出分享根目录。
         * 这是路径穿越防护的唯一入口——所有文件操作都必须经过它。
         */
        private File resolve(String argument) {
            String raw = argument.trim();
            // 去掉客户端可能带的引号
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            raw = decodeName(raw);
            File target;
            if (raw.isEmpty() || "/".equals(raw)) {
                target = cwd;
            } else if (raw.startsWith("/")) {
                target = new File(root, raw.substring(1));
            } else {
                target = new File(cwd, raw);
            }
            String canonicalTarget = canonical(target);
            return isInsideRoot(target) && canonicalTarget != null ? target : null;
        }

        private boolean isInsideRoot(File candidate) {
            String canonicalRoot = canonical(root);
            String canonicalCandidate = canonical(candidate);
            if (canonicalRoot == null || canonicalCandidate == null) {
                return false;
            }
            // 必须加分隔符，否则 /data/share-evil 会被误判为在 /data/share 之内。
            return canonicalCandidate.equals(canonicalRoot)
                    || canonicalCandidate.startsWith(canonicalRoot + File.separator);
        }

        private void closePassive() {
            closeQuietly(passive);
            passive = null;
        }

        private void closeData() {
            if (data != null) {
                openSockets.remove(data);
                closeQuietly(data);
                data = null;
            }
        }

        private void close() {
            closeData();
            closePassive();
            closeQuietly(control);
        }

        private void reply(int code, String message) throws IOException {
            write(code + " " + message + "\r\n");
        }

        private void multiReply(String... lines) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append("\r\n");
            }
            write(sb.toString());
        }

        private void write(String text) throws IOException {
            if (out == null) {
                return;
            }
            synchronized (control) {
                out.write(text.getBytes(UTF8));
                out.flush();
            }
        }
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * 把上传好的临时文件**原子地**提交为目标文件。
     *
     * 为什么必须原子：早先的写法是「先 delete 目标，再 rename，失败则退回 copy」。
     * 那样只要 rename 和 copy 都失败，**旧文件已经被删掉了**，用户原来那份就没了——
     * 这不是"任何异常都保留原文件"。
     *
     * 现在用 Files.move(REPLACE_EXISTING, ATOMIC_MOVE)：同目录内的原子替换，
     * 要么换成新内容，要么原样保留，不存在中间状态。
     * 如果文件系统不支持原子移动，就直接失败（保留旧目标），**不做退而复制的降级**——
     * 降级正是当初丢数据的来源。
     *
     * 说明：java.nio.file 在 API 26（本项目 minSdk）即可用，不需要新依赖。
     *
     * 这个方法是 static 且不依赖会话状态，便于自检直接构造"提交失败"场景。
     *
     * @return 失败原因；成功返回 null
     */
    public static String commitUpload(File temp, File target) {
        if (temp == null || !temp.isFile()) {
            return "临时文件不存在，未改动目标文件";
        }
        try {
            java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return null;
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            return "该文件系统不支持原子替换，已放弃本次提交以保留原文件";
        } catch (IOException ex) {
            return "替换失败（" + ex.getClass().getSimpleName() + "），原文件已保留";
        } catch (UnsupportedOperationException ex) {
            return "该文件系统不支持原子替换，已放弃本次提交以保留原文件";
        } catch (SecurityException ex) {
            return "无权限替换目标文件，原文件已保留";
        }
    }

    /** 部分客户端在 LIST 里发送 Latin-1 编码的中文路径，这里做一次回退解码。 */
    private static String decodeName(String raw) {
        try {
            byte[] bytes = raw.getBytes("ISO-8859-1");
            String utf8 = new String(bytes, UTF8);
            String gbk = new String(bytes, GBK);
            // 若按 UTF-8 解出的字符串再编码能还原，说明原串确实是 UTF-8 被误读。
            if (new String(utf8.getBytes(UTF8), "ISO-8859-1").equals(raw)) {
                return utf8;
            }
            if (new String(gbk.getBytes(GBK), "ISO-8859-1").equals(raw)) {
                return gbk;
            }
        } catch (UnsupportedEncodingException ignored) {
            // 两个字符集都是 JVM 必备，不可能到这里。
        }
        return raw;
    }

    /** 常量时间比较，避免逐字符比较泄漏凭证长度与内容。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(UTF8);
        byte[] y = b.getBytes(UTF8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length && i < y.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}
