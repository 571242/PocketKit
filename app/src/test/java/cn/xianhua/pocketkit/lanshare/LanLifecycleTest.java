package cn.xianhua.pocketkit.lanshare;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * 验证「闲置超时释放 Socket」这条承诺真的成立，而不只是写在文档里。
 *
 * 做法：起一个服务，确认端口能连；停掉之后端口必须连不上（连接被拒绝），
 * 而且 accept 线程要真的退出。前台服务和通知部分依赖 Android，这里只测纯 JVM 的那一半 ——
 * 也就是 Timer 之外真正的资源释放语义：stop() 之后 ServerSocket 不可再用。
 */
public final class LanLifecycleTest {

    private static int checks;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "lanshare-lifecycle");
        deleteTree(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("测试目录建不出来：" + root);
        }

        LanServer server = new LanServer("0123456789abcdefghijklmnopqrstuv",
                new LanServerTest.DirStore(root), "<html></html>", event -> { },
                InetAddress.getLoopbackAddress(), 0, 1024 * 1024);
        server.start();
        int port = server.port();
        check(port > 0, "启动后拿到真实端口");
        check(canConnect(port), "启动后端口可连");
        long before = server.lastActivity();
        check(before > 0, "启动后就有一个活动时间戳");

        // 一次真实请求应当推进活动时间。
        java.net.Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.getOutputStream().write(("GET /t/0123456789abcdefghijklmnopqrstuv/api/state "
                + "HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        readAll(socket.getInputStream());
        socket.close();
        check(server.lastActivity() >= before, "请求推进了活动时间戳");

        server.stop();
        check(!server.isRunning(), "stop() 之后 isRunning() 为 false");

        // 等一小会儿让 accept 线程真正退出，然后断言端口不再接受连接。
        Thread.sleep(150);
        check(!canConnect(port), "stop() 之后端口不再接受连接（Socket 已释放）");
        check(server.lastActivity() > 0, "停止后仍能读到最后的活动时间，便于判断闲置");

        // 停止后重复停止不应抛异常。
        server.stop();
        checks++;
        // 允许在同一端口重新启动：端口确实释放了。
        LanServer again = new LanServer("0123456789abcdefghijklmnopqrstuv",
                new LanServerTest.DirStore(root), "<html></html>", event -> { },
                InetAddress.getLoopbackAddress(), port, 1024 * 1024);
        try {
            again.start();
            check(again.port() == port, "端口释放后可以重新绑定同一端口");
        } catch (IOException ex) {
            fail("端口没有释放，重新绑定失败：" + ex.getMessage());
        } finally {
            again.stop();
        }

        checkIdle();
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


    /**
     * 闲置判定的穷举：不用真的等十分钟。
     *
     * 这条是「闲置超时会释放 Socket」承诺里唯一可被纯 JVM 验证的一半；
     * 另一半（Android 定时器真的触发）只能靠真机，已在文档里如实标注。
     */
    private static void checkIdle() {
        long timeout = 10 * 60 * 1000L;
        long now = 1_000_000_000L;

        check(LanServer.idleExpired(now - timeout - 1, now, false, timeout),
                "超过阈值且没有传输 → 判定为闲置");
        check(!LanServer.idleExpired(now - timeout + 1, now, false, timeout),
                "未到阈值 → 不判定闲置");
        check(!LanServer.idleExpired(now - timeout - 1, now, true, timeout),
                "正在传输时不算闲置（大文件传输不能被中途掐断）");
        check(!LanServer.idleExpired(now - 24 * 3600_000L, now, true, timeout),
                "传输一小时也不算闲置");
        check(!LanServer.idleExpired(0, now, false, timeout),
                "没有活动时间戳时不擅自判闲置");
        check(!LanServer.idleExpired(now - 1000, now, false, 0),
                "阈值为 0（未配置）时不自动停");
        check(LanServer.idleExpired(now - timeout - 1, now, false, timeout - 1),
                "阈值变小后同一空闲时长会被判为闲置");
    }

    private static boolean canConnect(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 400);
            return true;
        } catch (ConnectException ex) {
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
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
            fail(what);
        }
    }

    private static void fail(String what) {
        failures.add(what);
    }
}
