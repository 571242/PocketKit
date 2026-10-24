package cn.xianhua.pocketkit.remote;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * FTP and FTPS (explicit TLS) with passive transfers.
 *
 * <p>Hand-written rather than pulling in commons-net: the subset needed here is connect, login,
 * {@code MLSD} listing with an {@code LIST} fallback, retrieve, store, delete, rename, mkdir. Active
 * mode is deliberately not implemented — it cannot work behind a phone's NAT.
 *
 * <p>{@code MLSD} is preferred because {@code LIST} output is server-specific; the fallback parses
 * the common Unix format and refuses to guess when a line does not match.
 */
public final class FtpClient implements RemoteClient {

    private static final int TIMEOUT_MS = 20_000;

    private final RemoteSession session;
    private final String password;
    private Socket control;
    private BufferedReader reader;
    private OutputStream writer;
    private volatile boolean closed;

    public FtpClient(RemoteSession session, String password) throws IOException {
        this.session = session;
        this.password = password == null ? "" : password;
        connect();
    }

    private void connect() throws IOException {
        String host = session.host == null ? "" : session.host.trim();
        if (host.isEmpty()) throw new IOException("没有填写服务器地址");
        int port = session.port > 0 ? session.port : 21;
        try {
            control = new Socket();
            control.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            control.setSoTimeout(TIMEOUT_MS);
            reader = new BufferedReader(new InputStreamReader(control.getInputStream(),
                    StandardCharsets.UTF_8));
            writer = control.getOutputStream();
            expect("220", "服务器没有正常应答");
            if (session.secure) {
                upgradeTls(host);
            }
            String user = session.username == null || session.username.isEmpty() ? "anonymous" : session.username;
            Reply reply = command("USER " + user);
            if (reply.code == 331) {
                reply = command("PASS " + password);
            } else if (reply.code == 332) {
                throw new IOException("FTP 要求账号登录（ACCT），本客户端不支持");
            }
            if (reply.code >= 400) throw new IOException("FTP 登录失败：" + reply.text);
            // Binary mode is not optional: in ASCII mode the server rewrites line endings and every
            // non-text upload/download comes back corrupted, so a refusal is fatal rather than a
            // detail to ignore.
            Reply type = command("TYPE I");
            if (type.code >= 400) {
                throw new IOException("FTP 无法切换到二进制模式：" + type.text);
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void upgradeTls(String host) throws IOException {
        try {
            Reply reply = command("AUTH TLS");
            if (reply.code >= 400) throw new IOException("服务器不支持 FTPS（" + reply.text + "）");
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(control, host, control.getPort(), true);
            tls.setUseClientMode(true);
            // A bare SSLSocket validates the chain but not the host name, so a certificate issued
            // for another domain would be accepted. FTP has no URI to inherit this from, so it is
            // set explicitly, exactly as an HTTPS client would.
            javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            control = tls;
            control.setSoTimeout(TIMEOUT_MS);
            reader = new BufferedReader(new InputStreamReader(control.getInputStream(),
                    StandardCharsets.UTF_8));
            writer = control.getOutputStream();
            // Both of these must succeed; if PROT P is refused and the session continues, the data
            // channel silently falls back to clear text while the UI still says FTPS.
            Reply pbsz = command("PBSZ 0");
            if (pbsz.code >= 400) throw new IOException("FTPS 的 PBSZ 被拒绝：" + pbsz.text);
            Reply prot = command("PROT P");
            if (prot.code >= 400) {
                throw new IOException("服务器拒绝加密数据通道（PROT P），已中止以免明文传输：" + prot.text);
            }
        } catch (IOException e) {
            throw new IOException("FTPS 握手失败：" + e.getMessage(), e);
        }
    }

    @Override public String label() { return session.secure ? "FTPS" : "FTP"; }

    @Override public String describe() {
        return label() + " " + session.endpoint() + "\n起始路径：" + session.rootPath()
                + (session.secure ? "" : "\n注意：未加密连接，密码以明文传输");
    }

    @Override public List<Entry> list(String path) throws IOException {
        String target = resolve(path);
        List<Entry> out;
        try {
            out = mlsd(target, path);
        } catch (IOException unsupported) {
            out = list(target, path);
        }
        out.sort((a, b) -> a.directory == b.directory
                ? a.name.compareToIgnoreCase(b.name)
                : (a.directory ? -1 : 1));
        return out;
    }

    private List<Entry> mlsd(String target, String logicalPath) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (Socket data = passive()) {
            Reply reply = command("MLSD " + target);
            if (reply.code >= 400) throw new IOException(reply.text);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(data.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Entry entry = parseMlsd(line, logicalPath);
                    if (entry != null) out.add(entry);
                }
            }
        }
        expect("226", "列目录没有正常结束");
        return out;
    }

    private Entry parseMlsd(String line, String logicalPath) {
        int space = line.indexOf(' ');
        if (space <= 0) return null;
        String facts = line.substring(0, space);
        String name = line.substring(space + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return null;
        boolean directory = false;
        long size = -1;
        long modified = 0;
        for (String fact : facts.split(";")) {
            int eq = fact.indexOf('=');
            if (eq <= 0) continue;
            String key = fact.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = fact.substring(eq + 1);
            if (key.equals("type")) directory = value.equalsIgnoreCase("dir") || value.equalsIgnoreCase("cdir")
                    || value.equalsIgnoreCase("pdir");
            else if (key.equals("size")) {
                try {
                    size = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // Keep unknown.
                }
            } else if (key.equals("modify")) {
                modified = parseModify(value);
            }
        }
        return new Entry(name, join(logicalPath, name), directory, size, modified);
    }

    private List<Entry> list(String target, String logicalPath) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (Socket data = passive()) {
            Reply reply = command("LIST " + target);
            if (reply.code >= 400) throw new IOException(reply.text);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(data.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Entry entry = parseList(line, logicalPath);
                    if (entry != null) out.add(entry);
                }
            }
        }
        expect("226", "列目录没有正常结束");
        return out;
    }

    /** Parses the common Unix LIST format; anything else is skipped instead of guessed at. */
    private static Entry parseList(String line, String logicalPath) {
        String[] parts = line.split("\\s+", 9);
        if (parts.length < 9) return null;
        char type = parts[0].isEmpty() ? '?' : parts[0].charAt(0);
        if (type != '-' && type != 'd' && type != 'l') return null;
        if (type == 'l') return null; // A symlink would need its target resolved; skip it.
        long size = -1;
        try {
            size = Long.parseLong(parts[4]);
        } catch (NumberFormatException ignored) {
            // Keep unknown.
        }
        String name = parts[8];
        if (".".equals(name) || "..".equals(name)) return null;
        return new Entry(name, join(logicalPath, name), type == 'd', size, 0);
    }

    @Override public InputStream open(String path) throws IOException {
        final Socket data = passive();
        Reply reply = command("RETR " + resolve(path));
        if (reply.code >= 400) {
            closeQuietly(data);
            throw new IOException("FTP 下载失败：" + reply.text);
        }
        InputStream raw = data.getInputStream();
        return new FilterInputStream(raw) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closeQuietly(data);
                    expect("226", "下载没有正常结束");
                }
            }
        };
    }

    @Override public OutputStream create(String path) throws IOException {
        final Socket data = passive();
        Reply reply = command("STOR " + resolve(path));
        if (reply.code >= 400) {
            closeQuietly(data);
            throw new IOException("FTP 上传失败：" + reply.text);
        }
        OutputStream raw = data.getOutputStream();
        return new FilterOutputStream(raw) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closeQuietly(data);
                    // The server only reports success after the data connection closes.
                    expect("226", "上传没有正常结束");
                }
            }
        };
    }

    @Override public void delete(String path, boolean directory) throws IOException {
        Reply reply = command((directory ? "RMD " : "DELE ") + resolve(path));
        if (reply.code >= 400) throw new IOException("FTP 删除失败：" + reply.text);
    }

    @Override public void mkdir(String path) throws IOException {
        Reply reply = command("MKD " + resolve(path));
        if (reply.code >= 400) throw new IOException("FTP 新建目录失败：" + reply.text);
    }

    @Override public void rename(String path, String newName) throws IOException {
        String destination = join(parentOf(path), newName);
        Reply reply = command("RNFR " + resolve(path));
        if (reply.code >= 400) throw new IOException("FTP 重命名失败：" + reply.text);
        reply = command("RNTO " + resolve(destination));
        if (reply.code >= 400) throw new IOException("FTP 重命名失败：" + reply.text);
    }

    @Override public boolean alive() {
        if (closed || control == null || control.isClosed()) return false;
        try {
            return command("NOOP").code < 400;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public void close() {
        closed = true;
        if (control != null) {
            try {
                if (!control.isClosed()) {
                    try {
                        command("QUIT");
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                }
            } finally {
                closeQuietly(control);
                control = null;
            }
        }
    }

    /** Opens a passive data connection: EPSV first, PASV as the fallback. */
    private Socket passive() throws IOException {
        Reply epsv = command("EPSV");
        if (epsv.code == 229) {
            int open = epsv.text.indexOf('(');
            int close = epsv.text.lastIndexOf(')');
            if (open >= 0 && close > open) {
                String inner = epsv.text.substring(open + 1, close);
                // Format is |||port|
                String digits = inner.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    return openData(control.getInetAddress().getHostAddress(), Integer.parseInt(digits));
                }
            }
        }
        Reply pasv = command("PASV");
        if (pasv.code != 227) throw new IOException("FTP 被动模式失败：" + pasv.text);
        int open = pasv.text.indexOf('(');
        int close = pasv.text.lastIndexOf(')');
        if (open < 0 || close <= open) throw new IOException("FTP 被动模式返回无法解析");
        String[] parts = pasv.text.substring(open + 1, close).split(",");
        if (parts.length < 6) throw new IOException("FTP 被动模式返回无法解析");
        try {
            int port = Integer.parseInt(parts[4].trim()) * 256 + Integer.parseInt(parts[5].trim());
            // Ignore the address the server advertises: behind NAT it is usually a private one
            // that is unroutable from here, and the control connection's peer is the only address
            // already proven to work.
            return openData(control.getInetAddress().getHostAddress(), port);
        } catch (NumberFormatException e) {
            throw new IOException("FTP 被动模式端口无法解析", e);
        }
    }

    private Socket openData(String host, int port) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            plain.setSoTimeout(TIMEOUT_MS);
            if (!session.secure) return plain;
            // After PROT P the data channel is a separate TLS session. Leaving it in clear text
            // would encrypt only the commands while file contents travelled in the open.
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(plain, host, port, true);
            tls.setUseClientMode(true);
            javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(TIMEOUT_MS);
            tls.startHandshake();
            return tls;
        } catch (IOException e) {
            closeQuietly(plain);
            throw new IOException("FTPS 数据连接握手失败：" + e.getMessage(), e);
        }
    }

    private Reply command(String line) throws IOException {
        if (writer == null) throw new IOException("FTP 连接已关闭");
        try {
            writer.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            writer.flush();
        } catch (IOException e) {
            throw new IOException("FTP 发送命令失败：" + e.getMessage(), e);
        }
        return readReply();
    }

    private Reply readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) throw new IOException("FTP 服务器关闭了连接");
        if (line.length() < 3) throw new IOException("FTP 应答格式异常：" + line);
        int code;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("FTP 应答格式异常：" + line);
        }
        StringBuilder text = new StringBuilder(line);
        // Multi-line replies: "123-..." continues until "123 ".
        if (line.length() > 3 && line.charAt(3) == '-') {
            String terminator = line.substring(0, 3) + " ";
            while (true) {
                String next = reader.readLine();
                if (next == null) break;
                text.append('\n').append(next);
                if (next.startsWith(terminator)) break;
            }
        }
        return new Reply(code, text.toString());
    }

    private void expect(String code, String what) throws IOException {
        Reply reply = readReply();
        if (!String.valueOf(reply.code).equals(code)) {
            throw new IOException("FTP " + what + "：" + reply.text);
        }
    }

    private static final class Reply {
        final int code;
        final String text;

        Reply(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    private String resolve(String path) {
        if (path == null || path.isEmpty()) return session.rootPath();
        return path;
    }

    private static long parseModify(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(value).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    private static String parentOf(String path) {
        if (path == null || path.isEmpty()) return "/";
        String out = path;
        while (out.length() > 1 && out.endsWith("/")) out = out.substring(0, out.length() - 1);
        int slash = out.lastIndexOf('/');
        return slash <= 0 ? "/" : out.substring(0, slash);
    }

    private static String join(String parent, String child) {
        String base = parent == null || parent.isEmpty() ? "/" : parent;
        if (!base.endsWith("/")) base = base + "/";
        return base + child;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
