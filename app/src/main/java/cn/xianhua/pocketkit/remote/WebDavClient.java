package cn.xianhua.pocketkit.remote;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * WebDAV over OkHttp.
 *
 * <p>OkHttp rather than {@code HttpURLConnection}: Android's implementation keeps a whitelist of
 * request methods (GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE/PATCH) and throws
 * {@code ProtocolException} for anything else, which rules out {@code PROPFIND}, {@code MKCOL} and
 * {@code MOVE} — that is, listing, creating a directory and renaming would all fail. OkHttp sends
 * whatever method string it is given.
 *
 * <p>Supported: {@code PROPFIND} depth 1 for listing, {@code GET}/{@code PUT} for content,
 * {@code DELETE}, {@code MKCOL}, {@code MOVE} for rename. Basic auth only; a server that insists on
 * Digest will fail at login, and saying so is better than a login that half works.
 */
public final class WebDavClient implements RemoteClient {

    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>"
            + "</d:prop></d:propfind>";

    private final RemoteSession session;
    private final String rootUrl;
    /** Path component of {@link #rootUrl}, resolved once so per-entry parsing cannot throw. */
    private final String rootPath;
    private final OkHttpClient http;
    private volatile boolean closed;

    public WebDavClient(RemoteSession session, String password) throws IOException {
        this.session = session;
        String host = session.host == null ? "" : session.host.trim();
        if (host.isEmpty()) throw new IOException("没有填写服务器地址");
        String scheme = session.secure ? "https" : "http";
        if (host.startsWith("http://") || host.startsWith("https://")) {
            // Accept a pasted URL as-is; the scheme in it wins over the checkbox.
            this.rootUrl = trimSlash(host);
        } else {
            int port = session.port > 0 ? session.port : (session.secure ? 443 : 80);
            String base = session.basePath == null || session.basePath.isEmpty() ? "/" : session.basePath;
            if (!base.startsWith("/")) base = "/" + base;
            this.rootUrl = trimSlash(scheme + "://" + host + ":" + port + base);
        }
        this.rootPath = pathOfUrl(rootUrl);
        String secret = password == null ? "" : password;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (session.username != null && !session.username.isEmpty()) {
            final String header = Credentials.basic(session.username, secret);
            builder.addInterceptor(chain -> chain.proceed(
                    chain.request().newBuilder().header("Authorization", header).build()));
        }
        this.http = builder.build();
    }

    @Override public String label() { return "WebDAV"; }

    @Override public String describe() {
        return "WebDAV " + (session.secure ? "（TLS）" : "（明文）") + "\n" + rootUrl;
    }

    @Override public List<Entry> list(String path) throws IOException {
        Request request = new Request.Builder()
                .url(urlFor(path))
                .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
                .header("Depth", "1")
                .build();
        Response response = http.newCall(request).execute();
        try {
            int status = response.code();
            if (status == 401) throw new IOException("WebDAV 拒绝登录：用户名或密码不对");
            if (status == 404) throw new IOException("WebDAV 路径不存在：" + path);
            if (status >= 400) throw new IOException("WebDAV 列目录失败：HTTP " + status);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("WebDAV 列目录没有返回内容");
            return parse(body.byteStream(), path);
        } finally {
            closeResponse(response);
        }
    }

    private List<Entry> parse(InputStream in, String requestedPath) throws IOException {
        List<Entry> out = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // A remote server must not be able to make the parser fetch anything external.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(in);
            NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
            String selfPath = requestedPath == null ? "" : requestedPath;
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                String href = text(response, "href");
                if (href == null || href.isEmpty()) continue;
                String name = nameOf(href);
                if (name.isEmpty()) continue;
                String path = pathOf(href);
                // The server echoes the requested collection itself; skip it.
                if (normalize(path).equals(normalize(selfPath))) continue;
                boolean directory = response.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
                long size = parseLong(text(response, "getcontentlength"));
                long modified = parseDate(text(response, "getlastmodified"));
                out.add(new Entry(name, path, directory, size, modified));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("WebDAV 返回的目录信息无法解析", e);
        }
        out.sort((a, b) -> a.directory == b.directory
                ? a.name.compareToIgnoreCase(b.name)
                : (a.directory ? -1 : 1));
        return out;
    }

    @Override public InputStream open(String path) throws IOException {
        Request request = new Request.Builder().url(urlFor(path)).get().build();
        Response response = http.newCall(request).execute();
        int status = response.code();
        if (status == 401) {
            closeResponse(response);
            throw new IOException("WebDAV 拒绝登录：用户名或密码不对");
        }
        if (status >= 400) {
            closeResponse(response);
            throw new IOException("WebDAV 下载失败：HTTP " + status);
        }
        ResponseBody body = response.body();
        if (body == null) {
            closeResponse(response);
            throw new IOException("WebDAV 下载没有返回内容");
        }
        InputStream raw = body.byteStream();
        // Closing the body releases the connection; closing the socket alone would leak it.
        return new java.io.FilterInputStream(raw) {
            @Override public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closeResponse(response);
                }
            }
        };
    }

    /**
     * Buffers to a temporary file, then uploads on close.
     *
     * <p>A streaming {@code PUT} would need a request body that pulls from the caller, and a failed
     * upload would then leave a half-written remote file. Staging locally keeps the failure before
     * the server ever sees a partial body.
     */
    @Override public OutputStream create(String path) throws IOException {
        final File staged = File.createTempFile("pocketkit-upload", ".part");
        final FileOutputStream sink = new FileOutputStream(staged);
        final String target = urlFor(path);
        return new FilterOutputStream(sink) {
            @Override public void close() throws IOException {
                IOException failure = null;
                try {
                    super.close();
                } catch (IOException e) {
                    failure = e;
                }
                try {
                    if (failure == null) put(target, staged);
                } finally {
                    if (!staged.delete()) staged.deleteOnExit();
                }
                if (failure != null) throw failure;
            }
        };
    }

    private void put(String url, File file) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(OCTET_STREAM, file))
                .build();
        Response response = http.newCall(request).execute();
        try {
            int status = response.code();
            if (status == 401) throw new IOException("WebDAV 拒绝登录：用户名或密码不对");
            if (status == 409) throw new IOException("WebDAV 上传失败：上级目录不存在");
            if (status >= 400) throw new IOException("WebDAV 上传失败：HTTP " + status);
        } finally {
            closeResponse(response);
        }
    }

    @Override public void delete(String path, boolean directory) throws IOException {
        Request request = new Request.Builder().url(urlFor(path)).delete().build();
        execute(request, "WebDAV 删除失败");
    }

    @Override public void mkdir(String path) throws IOException {
        Request request = new Request.Builder().url(urlFor(path)).method("MKCOL", null).build();
        execute(request, "WebDAV 新建目录失败");
    }

    @Override public void rename(String path, String newName) throws IOException {
        String destination = urlFor(join(parentOf(path), newName));
        Request request = new Request.Builder()
                .url(urlFor(path))
                .method("MOVE", null)
                .header("Destination", destination)
                .header("Overwrite", "F")
                .build();
        Response response = http.newCall(request).execute();
        try {
            int status = response.code();
            if (status == 412) throw new IOException("WebDAV 目标已存在：" + newName);
            if (status >= 400) throw new IOException("WebDAV 重命名失败：HTTP " + status);
        } finally {
            closeResponse(response);
        }
    }

    @Override public boolean alive() {
        if (closed) return false;
        Request request = new Request.Builder()
                .url(rootUrl)
                .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
                .header("Depth", "0")
                .build();
        Response response = null;
        try {
            response = http.newCall(request).execute();
            return response.code() < 400;
        } catch (IOException e) {
            return false;
        } finally {
            closeResponse(response);
        }
    }

    @Override public void close() {
        closed = true;
        // OkHttp keeps an idle connection pool per client; shutting it down releases the sockets
        // immediately instead of waiting for the keep-alive timeout.
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    /** OkHttp 3.0.0 predates Response implementing Closeable, so closing is explicit. */
    private static void closeResponse(Response response) {
        if (response == null) return;
        try {
            ResponseBody body = response.body();
            if (body != null) body.close();
        } catch (RuntimeException ignored) {
            // The body may already be closed by the caller through its stream.
        }
    }

    private void execute(Request request, String failure) throws IOException {
        Response response = http.newCall(request).execute();
        try {
            int status = response.code();
            if (status == 404) throw new IOException(failure + "：路径不存在");
            if (status >= 400) throw new IOException(failure + "：HTTP " + status);
        } finally {
            closeResponse(response);
        }
    }

    /** Joins the session root with a server path into a full URL. */
    private String urlFor(String path) {
        String clean = path == null || path.isEmpty() ? "/" : path;
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean;
        if (!clean.startsWith("/")) clean = "/" + clean;
        StringBuilder sb = new StringBuilder(rootUrl);
        for (String segment : clean.split("/")) {
            if (segment.isEmpty()) continue;
            sb.append('/').append(encode(segment));
        }
        return sb.length() == 0 ? rootUrl : sb.toString();
    }

    private static String encode(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return segment;
        }
    }

    /** Converts an href the server returned back into a path this client accepts. */
    private String pathOf(String href) {
        String value = href;
        try {
            java.net.URI uri = new java.net.URI(value);
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) value = path;
        } catch (Exception ignored) {
            // Not absolute: use it as-is.
        }
        value = decode(value);
        if (rootPath != null && !rootPath.isEmpty() && !"/".equals(rootPath)
                && value.startsWith(rootPath)) {
            value = value.substring(rootPath.length());
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String pathOfUrl(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String nameOf(String href) {
        String value = decode(href);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String text(Element parent, String local) {
        NodeList nodes = parent.getElementsByTagNameNS("DAV:", local);
        if (nodes.getLength() == 0) return null;
        String value = nodes.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseDate(String value) {
        if (value == null || value.isEmpty()) return 0;
        for (String pattern : new String[]{"EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                return format.parse(value).getTime();
            } catch (ParseException ignored) {
                // Try the next pattern.
            }
        }
        return 0;
    }

    private static String trimSlash(String value) {
        String out = value;
        while (out.endsWith("/") && out.length() > 8) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String out = decode(value);
        while (out.endsWith("/") && out.length() > 1) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String parentOf(String path) {
        if (path == null) return "/";
        String out = path;
        while (out.endsWith("/") && out.length() > 1) out = out.substring(0, out.length() - 1);
        int slash = out.lastIndexOf('/');
        return slash <= 0 ? "/" : out.substring(0, slash);
    }

    private static String join(String parent, String child) {
        String base = parent == null || parent.isEmpty() ? "/" : parent;
        if (!base.endsWith("/")) base = base + "/";
        return base + child;
    }
}
