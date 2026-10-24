import cn.xianhua.pocketkit.device.FtpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP 服务端真实行为测试：起一个真实服务端，用真实套接字按 FTP 协议说话。
 *
 * 重点验证安全性质（路径穿越必须失败）与基本可用性（列目录/下载/上传）。
 * 纯 Java + java.net，可无 Android 运行。
 */
public final class FtpServerTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pocketkit-ftp-root");
        Path share = root.resolve("share");
        Files.createDirectories(share.resolve("sub"));
        Files.writeString(share.resolve("hello.txt"), "你好，PocketKit\n");
        Files.writeString(share.resolve("sub").resolve("inner.txt"), "inner\n");

        // 分享目录之外的"机密"文件：任何穿越尝试都不应该读到它。
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-OUTSIDE-SHARE\n");

        String user = "pk7f3a91";
        String pass = "Zq4mX8vT2p";

        FtpServer server = new FtpServer(share.toFile(), user, pass, 0, new FtpServer.Listener() {
            @Override public void onLog(String message) {
                // 测试期间不打印，避免噪声
            }

            @Override public void onClientCount(int count) {
            }
        });
        server.start();
        int port = server.port();
        System.out.println("FTP 服务端已启动，端口 " + port + "，分享目录 " + share);

        // 1. 未登录不能列目录
        try (Client c = new Client(port)) {
            c.read();
            String r = c.command("PWD");
            check("未登录 PWD 被拒绝(530)", r.startsWith("530"), r);
        }

        // 2. 错误密码被拒绝
        try (Client c = new Client(port)) {
            c.read();
            c.command("USER " + user);
            String r = c.command("PASS wrong-password");
            check("错误密码被拒绝(530)", r.startsWith("530"), r);
        }

        // 3. 正确凭证可登录
        try (Client c = new Client(port)) {
            c.read();
            c.command("USER " + user);
            String r = c.command("PASS " + pass);
            check("正确凭证登录成功(230)", r.startsWith("230"), r);
            String pwd = c.command("PWD");
            check("登录后 PWD 返回根(257)", pwd.startsWith("257"), pwd);
        }

        // 4. LIST 能看到文件（通过 PASV 数据连接真实取数据）
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String listing = c.list("LIST");
            check("LIST 包含 hello.txt", listing.contains("hello.txt"), listing);
            check("LIST 包含 sub 目录", listing.contains("sub"), listing);
            check("LIST 不包含分享目录之外的文件", !listing.contains("secret.txt"), listing);
        }

        // 5. 下载文件内容正确
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String body = c.download("hello.txt");
            check("RETR 内容正确", body.equals("你好，PocketKit\n"), body);
        }

        // 6. 子目录下载
        try (Client c = new Client(port)) {
            c.login(user, pass);
            c.command("CWD sub");
            String body = c.download("inner.txt");
            check("子目录 RETR 正确", body.equals("inner\n"), body);
        }

        // 7. 路径穿越：../ 必须被拦住
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.command("CWD ..");
            // CDUP/CWD .. 允许回到根，但不能再往上
            String r2 = c.command("CWD ..");
            String r3 = c.command("SIZE ../secret.txt");
            check("CWD .. 从根再往上被限制或停在根", r2.startsWith("550") || r2.startsWith("250"), r2);
            check("SIZE ../secret.txt 被拒绝(550)", r3.startsWith("550"), r3);
        }

        // 8. 路径穿越：绝对路径指向分享外
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.command("SIZE " + secret.toAbsolutePath());
            check("绝对路径指向分享外被拒绝(550)", r.startsWith("550"), r);
        }

        // 9. 路径穿越：多层 ../ 尝试读取
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.command("RETR ../../../../../../etc/passwd");
            check("深层 ../ 读取 /etc/passwd 被拒绝", r.startsWith("550") || r.startsWith("501") || r.startsWith("502"), r);
        }

        // 10. 路径穿越：RETR 分享外的具体文件
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.command("RETR ../secret.txt");
            check("RETR ../secret.txt 被拒绝(550)", r.startsWith("550"), r);
        }

        // 11. 上传可用
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.upload("uploaded.bin", "UPLOAD-CONTENT-1234");
            check("STOR 上传返回完成(226)", r.startsWith("226"), r);
        }
        Path uploaded = share.resolve("uploaded.bin");
        check("上传文件真的落盘", Files.exists(uploaded), String.valueOf(uploaded));
        if (Files.exists(uploaded)) {
            check("上传内容正确", Files.readString(uploaded).equals("UPLOAD-CONTENT-1234"),
                    Files.readString(uploaded));
        }

        // 11b. 【数据安全】上传中断不得损坏已有文件
        //      先把一个"原始文件"放到分享目录，然后发起一次中途断开的上传覆盖它，
        //      原文件内容必须完好无损，且不能残留临时文件。
        Path victim = share.resolve("victim.txt");
        Files.writeString(victim, "ORIGINAL-CONTENT-MUST-SURVIVE");
        try (Client c = new Client(port)) {
            c.login(user, pass);
            c.abortUpload("victim.txt", "PARTIAL-UPLOAD-THAT-GETS-CUT");
        }
        Thread.sleep(300);
        String afterAbort = Files.exists(victim) ? Files.readString(victim) : "<文件不存在>";
        check("中断的上传没有截断原文件", "ORIGINAL-CONTENT-MUST-SURVIVE".equals(afterAbort),
                "实际内容：" + afterAbort);

        // 也不能留下 .part 临时文件
        boolean leftover = false;
        try (java.util.stream.Stream<Path> list = Files.list(share)) {
            leftover = list.anyMatch(p -> p.getFileName().toString().startsWith(".pk-upload-"));
        } catch (Exception ex) {
            leftover = true;
        }
        check("中断的上传不残留临时文件", !leftover, "分享目录里还有 .pk-upload-* 文件");

        // 11c. 完整上传仍然能正常替换旧文件（确认修复没有把正常路径弄坏）
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.upload("victim.txt", "REPLACED-OK");
            check("完整上传可替换已有文件(226)", r.startsWith("226"), r);
        }
        Thread.sleep(150);
        check("替换后内容为新内容", "REPLACED-OK".equals(Files.readString(victim)),
                Files.readString(victim));

        // 12. 上传穿越：写到分享目录之外
        try (Client c = new Client(port)) {
            c.login(user, pass);
            String r = c.command("STOR ../escaped.txt");
            check("STOR ../escaped.txt 被拒绝(550)", r.startsWith("550"), r);
        }        check("分享外没有被写入 escaped.txt", !Files.exists(root.resolve("escaped.txt")), "");

        // 13. 破坏性命令一律拒绝
        try (Client c = new Client(port)) {
            c.login(user, pass);
            check("DELE 被拒绝", c.command("DELE hello.txt").startsWith("502"), "");
            check("RMD 被拒绝", c.command("RMD sub").startsWith("502"), "");
            check("MKD 被拒绝", c.command("MKD newdir").startsWith("502"), "");
            check("RNFR 被拒绝", c.command("RNFR hello.txt").startsWith("502"), "");
            check("SITE 被拒绝", c.command("SITE CHMOD 777 hello.txt").startsWith("502"), "");
            check("PORT 主动模式被拒绝", c.command("PORT 127,0,0,1,10,10").startsWith("502"), "");
        }
        check("hello.txt 仍然存在（未被删除命令影响）", Files.exists(share.resolve("hello.txt")), "");

        // 14. 大写小写命令都能识别
        try (Client c = new Client(port)) {
            c.login(user, pass);
            check("小写命令可用", c.command("pwd").startsWith("257"), "");
        }

        // 14c. 【认证失败限次】同一连接连续错密码，第 5 次必须收到 421 并断开
        //      原来的实现只回 530、不限次数，抓到一次握手就能持续重放试探。
        try (Client c = new Client(port)) {
            c.read();
            for (int i = 1; i <= 4; i++) {
                c.command("USER " + user);
                String r = c.command("PASS wrong-" + i);
                check("第 " + i + " 次错密码仍是 530（未到上限）", r.startsWith("530"), r);
            }
            c.command("USER " + user);
            String limited = c.command("PASS wrong-5");
            check("第 5 次错密码回 421 并断开", limited.startsWith("421"), limited);
            // 服务端应主动关闭连接：再读应得到 EOF。
            // 读超时（连接其实还开着）也只是「没断开」，不能让它把整轮测试打断。
            c.setReadTimeout(2000);
            String after;
            try {
                after = c.read();
            } catch (Exception ex) {
                after = "<连接仍然打开：" + ex.getClass().getSimpleName() + ">";
            }
            check("限次后连接被关闭", after.isEmpty(), after);
        }

        // 14d. 失败计数按连接重置：新连接仍可正常登录，不能把服务锁死
        try (Client c = new Client(port)) {
            c.login(user, pass);
            check("新连接不受上一条连接失败次数影响", c.command("PWD").startsWith("257"), "");
        }

        // 14e. 同一条连接里失败几次后改对密码，仍应登录成功（限次不该误伤）
        try (Client c = new Client(port)) {
            c.read();
            c.command("USER " + user);
            check("第一次错密码 530", c.command("PASS nope").startsWith("530"), "");
            c.command("USER " + user);
            check("失败后改对密码可登录 230", c.command("PASS " + pass).startsWith("230"), "");
        }

        // 15. 停止后端口应关闭
        server.stop();
        Thread.sleep(150);
        boolean refused;
        try (Socket ignored = new Socket("127.0.0.1", port)) {
            refused = false;
        } catch (Exception ex) {
            refused = true;
        }
        check("停止后不再接受连接", refused, "");

        // 16. 同名文件去重（FtpShareTool.uniqueTarget）：导入绝不覆盖已有文件
        dedupTests();

        // 17. 【提交阶段失败】也必须保留原文件（RST 测试覆盖不到提交阶段）
        commitFailureTests();

        // 18. 【服务假活】accept 线程遇异常后，状态必须与事实一致
        acceptLoopSurvivalTests();
        acceptLoopStateTests();

        // 19. 连续建连/断开 20 次后仍可服务（accept 循环没被抖死）
        churnTest();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        // 清理临时目录
        deleteRecursively(root.toFile());
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * 导入去重：重复导入同一个文件名时不能覆盖已有文件。
     * 这里直接复用生产代码的 FtpShareTool.uniqueTarget 规则（同一算法，避免两处跑偏）。
     */
    private static void dedupTests() throws Exception {
        Path dir = Files.createTempDirectory("pocketkit-dedup");
        File target1 = cn.xianhua.pocketkit.device.FileRules.uniqueTarget(dir.toFile(), "photo.jpg");
        check("首次导入使用原名", "photo.jpg".equals(target1.getName()), target1.getName());
        Files.writeString(target1.toPath(), "first");

        File target2 = cn.xianhua.pocketkit.device.FileRules.uniqueTarget(dir.toFile(), "photo.jpg");
        check("同名再导入不覆盖，改名加 (1)", "photo(1).jpg".equals(target2.getName()), target2.getName());
        Files.writeString(target2.toPath(), "second");

        File target3 = cn.xianhua.pocketkit.device.FileRules.uniqueTarget(dir.toFile(), "photo.jpg");
        check("第三次导入改名加 (2)", "photo(2).jpg".equals(target3.getName()), target3.getName());

        // 关键断言：原文件内容没有被改写。
        check("首次导入的文件内容未被覆盖", "first".equals(Files.readString(target1.toPath())),
                Files.readString(target1.toPath()));
        check("第二次导入的文件内容独立", "second".equals(Files.readString(target2.toPath())),
                Files.readString(target2.toPath()));

        // 无扩展名文件也要正确去重，且不能把整个名字当成 base 后丢掉。
        File noExt1 = cn.xianhua.pocketkit.device.FileRules.uniqueTarget(dir.toFile(), "README");
        Files.writeString(noExt1.toPath(), "x");
        File noExt2 = cn.xianhua.pocketkit.device.FileRules.uniqueTarget(dir.toFile(), "README");
        check("无扩展名文件去重", "README(1)".equals(noExt2.getName()), noExt2.getName());

        deleteRecursively(dir.toFile());
    }

    /**
     * 提交阶段失败必须保留原文件。
     *
     * 为什么单列这组：之前用 TCP RST 测的是**传输中断**，那条路径不会走到提交；
     * 而真正危险的写法是「先 delete 目标，再 rename，失败退回 copy」——
     * 只要 rename 与 copy 都失败，旧文件就已经被删了。
     * 这里直接调 commitUpload 构造提交失败，断言目标文件一字未动。
     */
    private static void commitFailureTests() throws Exception {
        Path dir = Files.createTempDirectory("pocketkit-commit");

        // 场景 1：临时文件不存在（模拟临时文件丢失/被清理）——目标必须原样保留
        Path target = dir.resolve("keep.txt");
        Files.writeString(target, "OLD-CONTENT");
        String error = FtpServer.commitUpload(dir.resolve("no-such-temp.part").toFile(), target.toFile());
        check("临时文件不存在时提交失败并返回原因", error != null, String.valueOf(error));
        check("提交失败后目标文件仍然存在", Files.exists(target), "文件不见了");
        check("提交失败后目标内容未被改动",
                Files.exists(target) && "OLD-CONTENT".equals(Files.readString(target)),
                Files.exists(target) ? Files.readString(target) : "<不存在>");

        // 场景 2：临时文件是目录（无法作为文件移动）——目标必须原样保留
        Path dirAsTemp = dir.resolve("tempIsDir.part");
        Files.createDirectory(dirAsTemp);
        String error2 = FtpServer.commitUpload(dirAsTemp.toFile(), target.toFile());
        check("临时路径是目录时提交失败", error2 != null, String.valueOf(error2));
        check("第二种失败后目标内容仍未被改动",
                "OLD-CONTENT".equals(Files.readString(target)), Files.readString(target));

        // 场景 3：正常提交确实替换成功（确认没有把正常路径改坏）
        Path goodTemp = dir.resolve("good.part");
        Files.writeString(goodTemp, "NEW-CONTENT");
        String error3 = FtpServer.commitUpload(goodTemp.toFile(), target.toFile());
        check("正常提交返回成功", error3 == null, String.valueOf(error3));
        check("正常提交后内容已替换", "NEW-CONTENT".equals(Files.readString(target)),
                Files.readString(target));
        check("正常提交后临时文件已消失", !Files.exists(goodTemp), "临时文件残留");

        deleteRecursively(dir.toFile());
    }

    /**
     * 【服务假活 #4】accept 线程遇到瞬时 IOException 之后必须继续监听。
     *
     * 直接测 acceptLoop：用反射把 serverSocket 换成一个「前 N 次 accept 抛 IOException」的
     * 包装套接字，再在独立线程里跑 acceptLoop。修好之前这里会 return，端口从此不再有人接受连接，
     * 但 isRunning() 仍是 true。
     */
    private static void acceptLoopSurvivalTests() throws Exception {
        Path dir = Files.createTempDirectory("pocketkit-accept-survive");
        FtpServer server = new FtpServer(dir.toFile(), "u", "p", 0, new FtpServer.Listener() {
            @Override public void onLog(String message) {
            }

            @Override public void onClientCount(int count) {
            }
        });
        java.net.ServerSocket real = new java.net.ServerSocket(0, 8,
                java.net.InetAddress.getLoopbackAddress());
        FaultyServerSocket faulty = new FaultyServerSocket(real, 2);
        int port = real.getLocalPort();

        set(server, "serverSocket", faulty);
        set(server, "running", true);
        Thread loop = new Thread(() -> invokeAcceptLoop(server), "ftp-accept-test");
        loop.setDaemon(true);
        loop.start();

        // 前两次 accept 抛异常，随后必须恢复：真实连上去应收到 220 问候。
        // 探测必须带短读超时：服务已死时 TCP 仍能连上（内核 backlog），只有读应答才看得出没人管。
        String greeting = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (greeting == null && System.currentTimeMillis() < deadline) {
            try (Client c = new Client(port)) {
                c.setReadTimeout(300);
                String line = c.read();
                greeting = line.isEmpty() ? null : line;
            } catch (Exception ignored) {
                // 还没恢复（或读超时），稍后重试
            }
            if (greeting == null) {
                Thread.sleep(100);
            }
        }
        check("瞬时 accept 异常后仍能接受新连接（不再假活）",
                greeting != null && greeting.startsWith("220"), String.valueOf(greeting));
        check("瞬时异常没有把 running 误置为 false", server.isRunning(), "");

        // 收尾：让循环退出
        faultClose(faulty);
        loop.join(3000);
        check("监听套接字关闭后 accept 循环退出", !loop.isAlive(), "线程仍在跑");
        deleteRecursively(dir.toFile());
    }

    /**
     * 【服务假活 #4】监听套接字真的不可用时，状态必须跟着变成「已停止」。
     *
     * 修好之前：accept 抛错后 return，running 仍是 true → 界面显示运行中，实际已死。
     */
    private static void acceptLoopStateTests() throws Exception {
        Path dir = Files.createTempDirectory("pocketkit-accept-state");
        FtpServer server = new FtpServer(dir.toFile(), "u", "p", 0, new FtpServer.Listener() {
            @Override public void onLog(String message) {
            }

            @Override public void onClientCount(int count) {
            }
        });
        java.net.ServerSocket real = new java.net.ServerSocket(0, 8,
                java.net.InetAddress.getLoopbackAddress());
        FaultyServerSocket faulty = new FaultyServerSocket(real, 0);

        set(server, "serverSocket", faulty);
        set(server, "running", true);
        Thread loop = new Thread(() -> invokeAcceptLoop(server), "ftp-accept-state-test");
        loop.setDaemon(true);
        loop.start();
        Thread.sleep(200);
        check("监听中 running 为 true", server.isRunning(), "");

        // 模拟「套接字被外部关闭」：accept 立刻抛错且 isClosed() 为 true
        faultClose(faulty);
        loop.join(3000);
        check("套接字关闭后 accept 循环退出", !loop.isAlive(), "线程仍在跑");
        check("套接字关闭后 running 变为 false（状态与事实一致）", !server.isRunning(), "");
        check("套接字关闭后 isRunning() 为 false", !server.isRunning(), "");
        deleteRecursively(dir.toFile());
    }

    /** 连续 20 次建连/断开后服务仍可用：accept 循环不能被抖动打死。 */
    private static void churnTest() throws Exception {
        Path dir = Files.createTempDirectory("pocketkit-churn");
        Files.writeString(dir.resolve("probe.txt"), "still-alive\n");
        FtpServer server = new FtpServer(dir.toFile(), "u", "p", 0, new FtpServer.Listener() {
            @Override public void onLog(String message) {
            }

            @Override public void onClientCount(int count) {
            }
        });
        server.start();
        int port = server.port();
        try {
            for (int i = 0; i < 20; i++) {
                try (Client c = new Client(port)) {
                    c.read();
                }
            }
            // 再来一次正常会话，确认 churn 之后服务仍然完整可用
            try (Client c = new Client(port)) {
                c.login("u", "p");
                check("20 次建连/断开后仍可登录", c.command("PWD").startsWith("257"), "");
                String body = c.download("probe.txt");
                check("20 次建连/断开后仍可下载", "still-alive\n".equals(body), body);
            }
            check("churn 之后 running 仍为 true", server.isRunning(), "");
        } finally {
            server.stop();
        }
        deleteRecursively(dir.toFile());
    }

    private static void set(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeAcceptLoop(FtpServer server) {
        try {
            java.lang.reflect.Method m = FtpServer.class.getDeclaredMethod("acceptLoop");
            m.setAccessible(true);
            m.invoke(server);
        } catch (Exception ex) {
            // 反射失败会让上面的断言看见线程已死，不必再抛
        }
    }

    private static void faultClose(FaultyServerSocket socket) throws Exception {
        socket.close();
    }

    /**
     * 可控失败的 ServerSocket 包装：前 N 次 accept 抛 IOException，之后转发给真实套接字。
     * 关掉真实套接字后，后面的 accept 会抛「Socket closed」，isClosed() 也变 true ——
     * 正是生产代码里要区分「瞬时错误」和「真的该停」的两个场景。
     */
    private static final class FaultyServerSocket extends java.net.ServerSocket {
        private final java.net.ServerSocket real;
        private int remainingFaults;

        FaultyServerSocket(java.net.ServerSocket real, int faults) throws java.io.IOException {
            super();
            this.real = real;
            this.remainingFaults = faults;
        }

        @Override public Socket accept() throws java.io.IOException {
            if (remainingFaults > 0) {
                remainingFaults--;
                throw new java.io.IOException("模拟瞬时 accept 失败");
            }
            return real.accept();
        }

        @Override public void close() throws java.io.IOException {
            real.close();
        }

        @Override public boolean isClosed() {
            return real.isClosed();
        }

        @Override public int getLocalPort() {
            return real.getLocalPort();
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            String line = name + (detail == null || detail.isEmpty() ? "" : "  → " + detail);
            failures.add(line);
            System.out.println("  ✗ " + line);
        }
    }

    /** 极简 FTP 控制连接客户端，用于按协议真说话。 */
    private static final class Client implements AutoCloseable {

        private final Socket socket;
        private final java.io.BufferedReader in;
        private final OutputStream out;

        Client(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        String read() throws Exception {
            String line = in.readLine();
            return line == null ? "" : line;
        }

        void setReadTimeout(int millis) throws Exception {
            socket.setSoTimeout(millis);
        }

        /** 发送命令并读一行应答。 */
        String command(String cmd) throws Exception {
            out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return read();
        }

        void login(String user, String pass) throws Exception {
            read();
            command("USER " + user);
            command("PASS " + pass);
        }

        /** 走 PASV 开数据连接，取回目录列表。 */
        String list(String cmd) throws Exception {
            String pasv = command("PASV");
            Socket data = connectPassive(pasv);
            String r = command(cmd);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader dr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = dr.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            data.close();
            read(); // 226
            return sb.toString();
        }

        String download(String name) throws Exception {
            String pasv = command("PASV");
            Socket data = connectPassive(pasv);
            String r = command("RETR " + name);
            if (!r.startsWith("150")) {
                data.close();
                return "ERR:" + r;
            }
            byte[] bytes = readAll(data);
            data.close();
            read(); // 226
            return new String(bytes, StandardCharsets.UTF_8);
        }

        String upload(String name, String content) throws Exception {
            String pasv = command("PASV");
            Socket data = connectPassive(pasv);
            String r = command("STOR " + name);
            if (!r.startsWith("150")) {
                data.close();
                return "ERR:" + r;
            }
            data.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
            data.getOutputStream().flush();
            data.close();
            return read();
        }

        /**
         * 发起上传写到一半后直接粗暴断开数据连接（模拟断线 / 切页面 / 进程被杀）。
         * 服务端应当把这次上传视为失败，并保持目标文件原样。
         */
        void abortUpload(String name, String partialContent) throws Exception {
            String pasv = command("PASV");
            Socket data = connectPassive(pasv);
            String r = command("STOR " + name);
            if (!r.startsWith("150")) {
                data.close();
                throw new IllegalStateException("服务端没有接受 STOR：" + r);
            }
            // 只写一部分，然后不关闭流就复位连接（RST），让服务端读到出错。
            data.getOutputStream().write(partialContent.getBytes(StandardCharsets.UTF_8));
            data.getOutputStream().flush();
            data.setSoLinger(true, 0); // 触发 TCP RST 而非正常 FIN
            data.close();
        }

        private Socket connectPassive(String pasvReply) throws Exception {
            // 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
            int open = pasvReply.indexOf('(');
            int close = pasvReply.indexOf(')', open);
            if (open < 0 || close < 0) {
                throw new IllegalStateException("PASV 应答无法解析：" + pasvReply);
            }
            String[] parts = pasvReply.substring(open + 1, close).split(",");
            String host = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            int dataPort = Integer.parseInt(parts[4].trim()) * 256 + Integer.parseInt(parts[5].trim());
            Socket s = new Socket(host, dataPort);
            s.setSoTimeout(10_000);
            return s;
        }

        private static byte[] readAll(Socket s) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = s.getInputStream().read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }

        @Override public void close() throws Exception {
            try {
                command("QUIT");
            } catch (Exception ignored) {
                // 已经断开
            }
            socket.close();
        }
    }
}
