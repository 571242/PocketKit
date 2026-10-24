package cn.xianhua.pocketkit.remote;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * SMB2/3 through smbj.
 *
 * <p>Paths here are Windows paths: the session's root is {@code \} and children are joined with
 * {@code \}. The share is opened separately, so a session carries the share name in addition to the
 * host.
 */
public final class SmbClient implements RemoteClient {

    private final RemoteSession session;
    private SMBClient client;
    private Connection connection;
    private Session smbSession;
    private DiskShare share;
    private volatile boolean closed;

    public SmbClient(RemoteSession session, String password) throws IOException {
        this.session = session;
        connect(password == null ? "" : password);
    }

    private void connect(String password) throws IOException {
        String host = session.host == null ? "" : session.host.trim();
        if (host.isEmpty()) throw new IOException("没有填写服务器地址");
        String shareName = session.share == null ? "" : session.share.trim();
        if (shareName.isEmpty()) throw new IOException("没有填写共享名（例如 shared、Users）");
        try {
            client = new SMBClient();
            // Honour the port the user typed: servers behind port forwarding often do not listen
            // on 445, and silently falling back to the default would connect to the wrong place.
            connection = session.port > 0
                    ? client.connect(host, session.port)
                    : client.connect(host);
            String user = session.username == null ? "" : session.username.trim();
            // An empty user means guest; smbj requires the domain to be null for a local account,
            // and the password must be a char[] so it can be wiped after use.
            char[] secret = password.toCharArray();
            try {
                AuthenticationContext auth = user.isEmpty()
                        ? AuthenticationContext.guest()
                        : new AuthenticationContext(user, secret, null);
                smbSession = connection.authenticate(auth);
            } finally {
                java.util.Arrays.fill(secret, '\0');
            }
            share = (DiskShare) smbSession.connectShare(shareName);
        } catch (SMBApiException e) {
            close();
            throw new IOException(describeFailure(e), e);
        } catch (Exception e) {
            close();
            throw new IOException("SMB 连接失败：" + e.getMessage(), e);
        }
    }

    private String describeFailure(SMBApiException e) {
        NtStatus status = e.getStatus();
        if (status == NtStatus.STATUS_LOGON_FAILURE) return "SMB 登录失败：用户名或密码不对";
        if (status == NtStatus.STATUS_ACCESS_DENIED) return "SMB 拒绝访问：账号没有该共享的权限";
        if (status == NtStatus.STATUS_BAD_NETWORK_NAME) return "SMB 共享不存在：" + session.share;
        if (status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND) return "SMB 路径不存在";
        return "SMB 错误：" + (e.getMessage() == null ? status.name() : e.getMessage());
    }

    @Override public String label() { return "SMB"; }

    @Override public String describe() {
        return "SMB " + session.endpoint() + "\\" + (session.share == null ? "" : session.share)
                + "\n起始路径：" + session.rootPath();
    }

    @Override public List<Entry> list(String path) throws IOException {
        List<Entry> out = new ArrayList<>();
        String target = sharePath(path);
        try {
            for (FileIdBothDirectoryInformation info : share.list(target)) {
                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                Set<FileAttributes> attributes = EnumUtils.toEnumSet(
                        info.getFileAttributes(), FileAttributes.class);
                boolean directory = attributes.contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                out.add(new Entry(name, join(path, name), directory, info.getEndOfFile(),
                        info.getLastWriteTime().toEpochMillis()));
            }
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
        out.sort((a, b) -> a.directory == b.directory
                ? a.name.compareToIgnoreCase(b.name)
                : (a.directory ? -1 : 1));
        return out;
    }

    @Override public InputStream open(String path) throws IOException {
        File file;
        try {
            file = share.openFile(sharePath(path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
        InputStream stream;
        try {
            stream = file.getInputStream();
        } catch (RuntimeException e) {
            // Opening failed after the handle existed, so nothing else would ever release it.
            closeQuietly(file);
            throw new IOException("SMB 打开文件流失败：" + e.getMessage(), e);
        }
        // smbj's stream close only drops its own reference; the server-side handle lives in File.
        // Without this the server accumulates one leaked handle per transfer until it refuses opens.
        return new java.io.FilterInputStream(stream) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closeQuietly(file);
                }
            }
        };
    }

    @Override public OutputStream create(String path) throws IOException {
        File file;
        try {
            file = share.openFile(sharePath(path),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null);
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
        OutputStream stream;
        try {
            stream = file.getOutputStream();
        } catch (RuntimeException e) {
            closeQuietly(file);
            throw new IOException("SMB 打开写入流失败：" + e.getMessage(), e);
        }
        return new java.io.FilterOutputStream(stream) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closeQuietly(file);
                }
            }
        };
    }

    private static void closeQuietly(File file) {
        if (file == null) return;
        try {
            file.close();
        } catch (Exception ignored) {
            // Already closed, or the connection went away; nothing left to release.
        }
    }

    @Override public void delete(String path, boolean directory) throws IOException {
        try {
            String target = sharePath(path);
            // Non-recursive across all four protocols, so the confirmation text can honestly say an
            // emptied directory is required. Recursive deletion here would make SMB alone behave
            // differently from SFTP, FTP and WebDAV.
            if (directory) share.rmdir(target, false);
            else share.rm(target);
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
    }

    @Override public void mkdir(String path) throws IOException {
        try {
            share.mkdir(sharePath(path));
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
    }

    @Override public void rename(String path, String newName) throws IOException {
        // smbj exposes rename on the open entry rather than on the share, so the file (or the
        // directory) is opened with delete rights and renamed through that handle.
        String source = sharePath(path);
        String destination = sharePath(join(parentOf(path), newName));
        boolean directory;
        try {
            // folderExists is one cheap query and avoids depending on the shape of the attribute
            // record, which differs between smbj versions.
            directory = share.folderExists(source);
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
        EnumSet<SMB2CreateOptions> options = directory
                ? EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                : EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE);
        try {
            File handle = share.openFile(source,
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    options);
            try {
                handle.rename(destination, true);
            } finally {
                handle.close();
            }
        } catch (SMBApiException e) {
            throw new IOException(describeFailure(e), e);
        }
    }

    @Override public boolean alive() {
        return !closed && connection != null && connection.isConnected() && share != null
                && share.isConnected();
    }

    @Override public void close() {
        closed = true;
        if (share != null) {
            try {
                share.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            share = null;
        }
        if (smbSession != null) {
            try {
                smbSession.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            smbSession = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            connection = null;
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            client = null;
        }
    }

    /** smbj wants a share-relative path without a leading separator. */
    private String sharePath(String path) {
        String value = path == null || path.isEmpty() ? session.rootPath() : path;
        value = value.replace('/', '\\');
        while (value.startsWith("\\")) value = value.substring(1);
        while (value.endsWith("\\")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String parentOf(String path) {
        String value = path == null ? "" : path.replace('/', '\\');
        while (value.length() > 1 && value.endsWith("\\")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('\\');
        return slash <= 0 ? "\\" : value.substring(0, slash);
    }

    private static String join(String parent, String child) {
        String base = parent == null || parent.isEmpty() ? "\\" : parent.replace('/', '\\');
        if (!base.endsWith("\\")) base = base + "\\";
        return base + child;
    }
}
