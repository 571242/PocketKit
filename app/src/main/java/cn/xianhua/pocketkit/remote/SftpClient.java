package cn.xianhua.pocketkit.remote;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * SFTP through jsch.
 *
 * <p>Password authentication only. Key files are not supported yet, and the connect screen says so
 * rather than failing with a protocol error.
 */
public final class SftpClient implements RemoteClient {

    private static final int TIMEOUT_MS = 20_000;

    private final RemoteSession session;
    private Session ssh;
    private ChannelSftp channel;
    private volatile boolean closed;

    public SftpClient(RemoteSession session, String password) throws IOException {
        this.session = session;
        connect(password == null ? "" : password);
    }

    private void connect(String password) throws IOException {
        String host = session.host == null ? "" : session.host.trim();
        if (host.isEmpty()) throw new IOException("没有填写服务器地址");
        SftpHostKeys hostKeys = new SftpHostKeys(host, session.port > 0 ? session.port : 22, session.hostKey);
        try {
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(hostKeys);
            ssh = jsch.getSession(session.username == null ? "" : session.username.trim(), host,
                    session.port > 0 ? session.port : 22);
            ssh.setPassword(password);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "yes");
            ssh.setConfig(config);
            ssh.setTimeout(TIMEOUT_MS);
            ssh.connect(TIMEOUT_MS);
            channel = (ChannelSftp) ssh.openChannel("sftp");
            channel.connect(TIMEOUT_MS);
        } catch (JSchException e) {
            close();
            SftpHostKeys.Rejected rejected = hostKeys.rejection();
            if (rejected != null) throw rejected;
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("Auth fail") || message.contains("auth cancel")) {
                throw new IOException("SFTP 认证失败：用户名、密码不对，或服务器只接受密钥登录");
            }
            if (message.contains("UnknownHost")) throw new IOException("SFTP 找不到主机：" + host);
            if (message.contains("Connection refused")) throw new IOException("SFTP 拒绝连接：" + host);
            if (message.contains("timeout") || message.contains("Timed out")) {
                throw new IOException("SFTP 连接超时：" + host);
            }
            throw new IOException("SFTP 连接失败：" + message, e);
        }
    }

    @Override public String label() { return "SFTP"; }

    @Override public String describe() {
        return "SFTP " + session.endpoint() + "\n起始路径：" + session.rootPath();
    }

    @Override public List<Entry> list(String path) throws IOException {
        List<Entry> out = new ArrayList<>();
        try {
            Vector<?> items = channel.ls(resolve(path));
            for (Object item : items) {
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) item;
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                SftpATTRS attrs = entry.getAttrs();
                out.add(new Entry(name, join(path, name), attrs.isDir(), attrs.getSize(),
                        attrs.getMTime() * 1000L));
            }
        } catch (SftpException e) {
            throw new IOException(failure("列目录", path, e), e);
        }
        out.sort((a, b) -> a.directory == b.directory
                ? a.name.compareToIgnoreCase(b.name)
                : (a.directory ? -1 : 1));
        return out;
    }

    @Override public InputStream open(String path) throws IOException {
        try {
            return channel.get(resolve(path));
        } catch (SftpException e) {
            throw new IOException(failure("下载", path, e), e);
        }
    }

    @Override public OutputStream create(String path) throws IOException {
        try {
            return channel.put(resolve(path));
        } catch (SftpException e) {
            throw new IOException(failure("上传", path, e), e);
        }
    }

    @Override public void delete(String path, boolean directory) throws IOException {
        try {
            if (directory) channel.rmdir(resolve(path));
            else channel.rm(resolve(path));
        } catch (SftpException e) {
            throw new IOException(failure("删除", path, e), e);
        }
    }

    @Override public void mkdir(String path) throws IOException {
        try {
            channel.mkdir(resolve(path));
        } catch (SftpException e) {
            throw new IOException(failure("新建目录", path, e), e);
        }
    }

    @Override public void rename(String path, String newName) throws IOException {
        try {
            channel.rename(resolve(path), resolve(join(parentOf(path), newName)));
        } catch (SftpException e) {
            throw new IOException(failure("重命名", path, e), e);
        }
    }

    @Override public boolean alive() {
        // Channels do not expose liveness; the transport does.
        return !closed && ssh != null && ssh.isConnected() && channel != null && channel.isConnected();
    }

    @Override public void close() {
        closed = true;
        if (channel != null) {
            try {
                channel.disconnect();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            channel = null;
        }
        if (ssh != null) {
            try {
                ssh.disconnect();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            ssh = null;
        }
    }

    private String resolve(String path) {
        if (path == null || path.isEmpty()) return session.rootPath();
        return path;
    }

    private static String failure(String action, String path, SftpException e) {
        switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE:
                return "SFTP " + action + "失败：路径不存在 " + path;
            case ChannelSftp.SSH_FX_PERMISSION_DENIED:
                return "SFTP " + action + "失败：服务器拒绝权限 " + path;
            case ChannelSftp.SSH_FX_FAILURE:
                return "SFTP " + action + "失败：" + e.getMessage();
            default:
                return "SFTP " + action + "失败（" + e.id + "）：" + e.getMessage();
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
}
