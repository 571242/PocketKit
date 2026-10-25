package cn.xianhua.pocketkit.remote;

import java.util.UUID;

/** One saved remote connection. Credentials live in {@link RemoteStore}, not here. */
public final class RemoteSession {

    public enum Protocol {
        SFTP("SFTP", 22),
        SMB("SMB", 445),
        FTP("FTP", 21),
        WEBDAV("WebDAV", 443);

        public final String label;
        public final int defaultPort;

        Protocol(String label, int defaultPort) {
            this.label = label;
            this.defaultPort = defaultPort;
        }

        public static Protocol of(String name) {
            for (Protocol p : values()) {
                if (p.name().equals(name)) return p;
            }
            return SFTP;
        }
    }

    public String id;
    public String name;
    public Protocol protocol;
    /** Host or IP, no scheme, no port. */
    public String host;
    public int port;
    public String username;
    /** Path opened first, in the protocol's own form. Empty means the natural root. */
    public String startPath;
    /** SMB only: the share name. */
    public String share;
    /** WebDAV only: base URL path, kept separate so the URL can be rebuilt. */
    public String basePath;
    /** FTP and WebDAV: use TLS. */
    public boolean secure;
    /** User-verified SSH public host key (Base64); never a private key or password. */
    public String hostKey = "";

    public RemoteSession() {
        this.id = UUID.randomUUID().toString();
        this.protocol = Protocol.SFTP;
    }

    public RemoteSession copy() {
        RemoteSession out = new RemoteSession();
        out.id = id;
        out.name = name;
        out.protocol = protocol;
        out.host = host;
        out.port = port;
        out.username = username;
        out.startPath = startPath;
        out.share = share;
        out.basePath = basePath;
        out.secure = secure;
        out.hostKey = hostKey;
        return out;
    }

    public boolean sameServer(RemoteSession other) {
        if (other == null || protocol != other.protocol) return false;
        String a = host == null ? "" : host.trim();
        String b = other.host == null ? "" : other.host.trim();
        return a.equalsIgnoreCase(b) && (port > 0 ? port : protocol.defaultPort)
                == (other.port > 0 ? other.port : other.protocol.defaultPort);
    }

    /** "user@host:port", never includes a secret. */
    public String endpoint() {
        StringBuilder sb = new StringBuilder();
        if (username != null && !username.isEmpty()) sb.append(username).append('@');
        sb.append(host == null ? "" : host);
        if (port > 0) sb.append(':').append(port);
        return sb.toString();
    }

    public String displayName() {
        String label = name == null || name.trim().isEmpty() ? host : name.trim();
        return protocol.label + " · " + (label == null ? "" : label);
    }

    /** Root the browser starts from; never null. */
    public String rootPath() {
        if (startPath != null && !startPath.trim().isEmpty()) return startPath.trim();
        switch (protocol) {
            case SMB: return "\\";
            case WEBDAV: return "/";
            default: return "/";
        }
    }
}
