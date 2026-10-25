package cn.xianhua.pocketkit.remote;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** A per-connection pinned key. Unknown keys abort SSH before password authentication. */
public final class SftpHostKeys implements HostKeyRepository {
    private final String endpoint;
    private final byte[] pinnedBytes;
    private final HostKey pinned;
    private HostKey presented;
    private byte[] presentedBytes;

    public SftpHostKeys(String host, int port, String encodedKey) throws IOException {
        endpoint = port == 22 ? host : "[" + host + "]:" + port;
        if (encodedKey == null || encodedKey.isEmpty()) {
            pinnedBytes = null;
            pinned = null;
        } else {
            try {
                pinnedBytes = Base64.getDecoder().decode(encodedKey);
                pinned = new HostKey(endpoint, pinnedBytes);
            } catch (IllegalArgumentException | JSchException e) {
                throw new IOException("保存的 SFTP 主机指纹已损坏，请重新建立并核对连接", e);
            }
        }
    }

    @Override public int check(String host, byte[] key) {
        presented = null;
        presentedBytes = null;
        if (!endpoint.equals(host) || key == null || key.length > 65536) return CHANGED;
        try {
            presented = new HostKey(endpoint, key);
            presentedBytes = key.clone();
        } catch (JSchException e) {
            presented = null;
            presentedBytes = null;
            return CHANGED;
        }
        if (pinnedBytes == null) return NOT_INCLUDED;
        return MessageDigest.isEqual(pinnedBytes, key) ? OK : CHANGED;
    }

    public Rejected rejection() {
        if (presented == null || (pinnedBytes != null && MessageDigest.isEqual(pinnedBytes, presentedBytes))) return null;
        return new Rejected(endpoint, presented.getType(), presented.getKey(),
                fingerprint(presentedBytes), pinnedBytes == null ? "" : fingerprint(pinnedBytes));
    }

    public static String fingerprint(byte[] key) {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(key));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // Trust changes only through the explicit application dialog, never through SSH callbacks.
    @Override public void add(HostKey hostkey, UserInfo ui) { }
    @Override public void remove(String host, String type) { }
    @Override public void remove(String host, String type, byte[] key) { }
    @Override public String getKnownHostsRepositoryID() { return "PocketKit pinned SFTP host"; }
    @Override public HostKey[] getHostKey() { return pinned == null ? new HostKey[0] : new HostKey[]{pinned}; }
    @Override public HostKey[] getHostKey(String host, String type) {
        return pinned != null && (host == null || endpoint.equals(host))
                && (type == null || pinned.getType().equals(type)) ? new HostKey[]{pinned} : new HostKey[0];
    }

    public static final class Rejected extends IOException {
        public final String endpoint, algorithm, encodedKey, fingerprint, previousFingerprint;
        public final boolean changed;
        private Rejected(String endpoint, String algorithm, String encodedKey, String fingerprint, String previous) {
            super(previous.isEmpty() ? "首次连接：请先核对 SFTP 主机指纹" : "SFTP 主机指纹发生变化，已中止连接");
            this.endpoint = endpoint;
            this.algorithm = algorithm;
            this.encodedKey = encodedKey;
            this.fingerprint = fingerprint;
            this.previousFingerprint = previous;
            this.changed = !previous.isEmpty();
        }
    }
}
