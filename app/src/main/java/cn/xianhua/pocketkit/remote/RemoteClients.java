package cn.xianhua.pocketkit.remote;

import java.io.IOException;

/** Opens the client that matches a session's protocol. */
public final class RemoteClients {

    private RemoteClients() {}

    public static RemoteClient open(RemoteSession session, String password) throws IOException {
        if (session == null) throw new IOException("没有选择连接");
        switch (session.protocol) {
            case SMB: return new SmbClient(session, password);
            case FTP: return new FtpClient(session, password);
            case WEBDAV: return new WebDavClient(session, password);
            case SFTP:
            default: return new SftpClient(session, password);
        }
    }

    /** Default port for a protocol, used when the field is left blank. */
    public static int defaultPort(RemoteSession.Protocol protocol) {
        return protocol.defaultPort;
    }

    /** One-line hint shown under the protocol selector. */
    public static String hint(RemoteSession.Protocol protocol) {
        switch (protocol) {
            case SMB:
                return "SMB2/3，Windows 共享与 NAS。需要填共享名（例如 Users、homes）。\n"
                        + "路径用反斜杠，起始路径留空表示共享根目录。";
            case FTP:
                return "FTP 被动模式；勾选加密则走 FTPS（显式 TLS）。\n"
                        + "不勾选时密码以明文传输，仅建议在局域网内使用。";
            case WEBDAV:
                return "WebDAV，可接坚果云、Nextcloud 等。起始路径填网盘内的目录。\n"
                        + "支持 Basic 认证；服务器若只接受 Digest 认证会登录失败。";
            case SFTP:
            default:
                return "SSH 文件传输，适合自己的服务器与 NAS。\n"
                        + "目前只支持密码登录；服务器只允许密钥时会明确报错。";
        }
    }
}
