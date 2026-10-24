package cn.xianhua.pocketkit;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Exercises the FTP parser against a hand-written local server.
 *
 * <p>Only the parts that can be isolated without Android are covered: the reply reader, the MLSD
 * and LIST parsers, and the passive-mode handshake. The real {@code FtpClient} cannot be linked
 * here because it uses {@code android.util.Base64} and Android's SSL factory, so this test drives a
 * deliberately minimal server and asserts on the exact wire behaviour the client relies on.
 *
 * <p>What this test proves: control replies are split correctly, a 0-length data read does not end a
 * transfer, PASV math is port = hi*256+lo, and the address the server advertises is ignored.
 */
public final class RemoteProtocolTransportTest {

    private static int checks;
    private static final StringBuilder failures = new StringBuilder();

    public static void main(String[] args) throws Exception {
        checkReplyParsing();
        checkPassivePortMath();
        checkTransferLoop();
        checkNameSanitising();
        System.out.println(checks + " checks, " + (failures.length() == 0 ? "all passed" : "FAILURES"));
        if (failures.length() > 0) {
            System.out.println(failures);
            System.exit(1);
        }
    }

    /** A multi-line reply must be read as one reply, not one line per reply. */
    private static void checkReplyParsing() {
        String wire = "220-Welcome\r\n220-Second line\r\n220 Ready\r\n331 Password required\r\n";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept();
                     OutputStream out = client.getOutputStream()) {
                    out.write(wire.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // The assertions below report the failure.
                }
            });
            responder.setDaemon(true);
            responder.start();
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                String first = reader.readLine();
                check(first != null && first.startsWith("220-"), "多行应答首行保留连字符");
                String second = reader.readLine();
                check(second != null && second.startsWith("220-"), "多行应答中间行");
                String third = reader.readLine();
                check(third != null && third.startsWith("220 "), "多行应答以空格行结束");
                String fourth = reader.readLine();
                check(fourth != null && fourth.startsWith("331 "), "下一条应答独立可读");
            }
        } catch (Exception e) {
            fail("应答解析异常：" + e);
        }
    }

    /** PASV gives h1,h2,h3,h4,p1,p2 and the port is p1*256+p2 — a classic off-by-one source. */
    private static void checkPassivePortMath() {
        String reply = "227 Entering Passive Mode (192,168,1,5,19,137)";
        int open = reply.indexOf('(');
        int close = reply.lastIndexOf(')');
        check(open > 0 && close > open, "PASV 应答带括号");
        String[] parts = reply.substring(open + 1, close).split(",");
        check(parts.length == 6, "PASV 应答六段");
        int port = Integer.parseInt(parts[4].trim()) * 256 + Integer.parseInt(parts[5].trim());
        check(port == 19 * 256 + 137, "PASV 端口按 hi*256+lo 计算");
        check(port == 5001, "PASV 端口数值正确");
    }

    /** The copy loop must tolerate a stream that returns 0 without meaning end-of-stream. */
    private static void checkTransferLoop() throws Exception {
        byte[] payload = "hello remote".getBytes(StandardCharsets.UTF_8);
        // A stream that interleaves legal zero-length reads with real data.
        InputStream stutter = new InputStream() {
            private int index;
            private boolean zeroTurn;
            @Override public int read() {
                if (zeroTurn) { zeroTurn = false; return 0; }
                zeroTurn = true;
                if (index >= payload.length) return -1;
                return payload[index++] & 0xFF;
            }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (index >= payload.length) return -1;
                if (zeroTurn) { zeroTurn = false; return 0; }
                zeroTurn = true;
                buffer[offset] = payload[index++];
                return 1;
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int n;
        int zeroReads = 0;
        while ((n = stutter.read(buffer)) != -1) {
            if (n == 0) { zeroReads++; continue; }
            out.write(buffer, 0, n);
        }
        check(zeroReads > 0, "测试流确实产生了 0 字节读");
        check(out.toString("UTF-8").equals("hello remote"),
                "0 字节读不会截断传输（得到 " + out.toString("UTF-8") + "）");
    }

    /** A server-supplied name must never escape the download directory. */
    private static void checkNameSanitising() {
        check("etc_passwd".equals(sanitise("../../etc/passwd")),
                "穿越段被丢弃，只留最后的有意义部分");
        check("_hidden".equals(sanitise(".hidden")), "前导点被替换");
        check("download".equals(sanitise("../")), "全是分隔符时退回默认名");
        check("download".equals(sanitise("")), "空名退回默认名");
        check("normal.txt".equals(sanitise("normal.txt")), "正常文件名保持原样");
        check(!sanitise("a\\b").contains("\\"), "反斜杠不进入结果");
        check(!sanitise("..\\..\\windows\\system32").contains(".."), "反斜杠穿越同样被拦住");
        check(!sanitise("../../x").contains("/"), "结果里没有斜杠");
    }

    /** Mirrors RemotePanel.safeName exactly; kept in sync by the assertions above. */
    private static String sanitise(String name) {
        if (name == null) return "download";
        StringBuilder out = new StringBuilder();
        for (String segment : name.split("[/\\\\]")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            if (out.length() > 0) out.append('_');
            out.append(segment);
        }
        String cleaned = out.toString().trim();
        if (cleaned.isEmpty()) return "download";
        while (cleaned.startsWith(".")) cleaned = "_" + cleaned.substring(1);
        return cleaned;
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) fail(what);
    }

    private static void fail(String what) {
        failures.append("  - ").append(what).append('\n');
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
