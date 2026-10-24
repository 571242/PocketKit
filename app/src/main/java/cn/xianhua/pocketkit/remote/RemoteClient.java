package cn.xianhua.pocketkit.remote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The smallest surface every remote filesystem needs to look like a folder.
 *
 * <p>Deliberately not a {@code DocumentsProvider}: the four protocols disagree about what a path is
 * (SFTP and FTP use POSIX paths, SMB uses case-insensitive backslash paths, WebDAV uses URLs), and
 * forcing them into SAF's document-id model would mean inventing identities the servers do not
 * have. The manager browses a session through this interface instead.
 *
 * <p>Implementations are used from a worker thread only, never from the UI thread.
 */
public interface RemoteClient extends Closeable {

    /** Protocol label shown in the UI, for example {@code SFTP}. */
    String label();

    /** Human-readable session line, never including the password. */
    String describe();

    /** Lists one directory. {@code path} uses the protocol's own separator. */
    List<Entry> list(String path) throws IOException;

    /** Opens a file for reading. The caller closes it. */
    InputStream open(String path) throws IOException;

    /** Creates or truncates a file for writing. The caller closes it. */
    OutputStream create(String path) throws IOException;

    void delete(String path, boolean directory) throws IOException;

    void rename(String path, String newName) throws IOException;

    void mkdir(String path) throws IOException;

    /** True when the connection still works; a cheap request, not a full reconnect. */
    boolean alive();

    /** One listed item. */
    final class Entry {
        public final String name;
        /** Full path in the protocol's own form, ready to pass back to this client. */
        public final String path;
        public final boolean directory;
        public final long size;
        public final long modified;

        public Entry(String name, String path, boolean directory, long size, long modified) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }
    }
}
