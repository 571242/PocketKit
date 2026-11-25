package cn.xianhua.pocketkit.reading;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Bounded HTTP client for untrusted reading-source URLs.
 *
 * <p>Host safety is enforced at connect time, not before it: the client uses a custom {@link Dns}
 * that validates every address and hands those very addresses back to OkHttp, so a second DNS
 * lookup cannot swap in a private address between the check and the connect (no TOCTOU window).
 * Redirects are followed hop by hop by {@link SafeRedirect} which refuses non-HTTP(S) targets,
 * blocks HTTPS→HTTP downgrades, and drops sensitive headers when the origin changes.
 */
public final class SourceHttp {
    static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    // A cold TLS handshake to a slow regional host can take longer than a browser-like 12s on
    // first contact; the call timeout still caps the whole request at 30s.
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int CALL_TIMEOUT_MS = 35_000;
    private static final int MAX_REDIRECTS = 5;
    private static final Semaphore SLOTS = new Semaphore(4, true);
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final OkHttpClient CLIENT = buildClient(false, false, 12_000);
    private static final OkHttpClient CLIENT_H1 = buildClient(false, true, CALL_TIMEOUT_MS);
    private static final OkHttpClient MEDIA_CLIENT = buildClient(true, false, CALL_TIMEOUT_MS);

    private SourceHttp() {}

    public static String get(String url) throws IOException {
        return execute(Request.get(url), DEFAULT_MAX_BYTES).body;
    }

    /** Downloads bounded binary bytes (e.g. manga images) with custom headers such as Referer. */
    public static BinaryResponse fetchBytes(String url, Map<String, String> headers, int maxBytes) throws IOException {
        if (url == null || url.trim().isEmpty()) throw new IOException("URL 为空");
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IOException("响应体积上限无效");
        rejectAndroidMainThread();
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) throw new IOException("URL 格式无效");
        checkTransport(parsed);
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(parsed);
        builder.header("User-Agent", USER_AGENT);
        builder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (safeRequestHeader(h.getKey()) && h.getValue() != null && !h.getValue().isBlank()) {
                    builder.header(h.getKey(), h.getValue());
                }
            }
        }
        try (okhttp3.Response response = MEDIA_CLIENT.newCall(builder.build()).execute()) {
            if (response.code() < 200 || response.code() >= 300) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) return new BinaryResponse(new byte[0], "image/jpeg");
            InputStream raw = body.byteStream();
            String encoding = lower(response.header("Content-Encoding"));
            if (encoding.contains("gzip")) raw = new GZIPInputStream(raw);
            else if (encoding.contains("deflate")) raw = new InflaterInputStream(raw);
            try (InputStream in = raw; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > maxBytes) throw new IOException("图片超过体积上限");
                    out.write(buf, 0, n);
                }
                MediaType ct = body.contentType();
                String mime = ct != null ? (ct.type() + "/" + ct.subtype()) : "image/jpeg";
                return new BinaryResponse(out.toByteArray(), mime);
            }
        }
    }

    public static final class BinaryResponse {
        public final byte[] bytes;
        public final String mimeType;
        public BinaryResponse(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }

    /** Shared, hardened client for Media3's OkHttpDataSource: DNS-pinned and redirect-safe. */
    public static OkHttpClient mediaClient() {
        return MEDIA_CLIENT;
    }

    static Response execute(Request request, int maxBytes) throws IOException {
        if (request == null) throw new IOException("请求不能为空");
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IOException("响应体积上限无效");
        rejectAndroidMainThread();
        boolean acquired = false;
        try {
            acquired = SLOTS.tryAcquire(20, TimeUnit.SECONDS);
            if (!acquired) throw new IOException("阅读网络请求过多，请稍后重试");
            return executeLocked(request, maxBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("阅读网络请求已取消", e);
        } finally {
            if (acquired) SLOTS.release();
        }
    }

    private static Response executeLocked(Request original, int maxBytes) throws IOException {
        HttpUrl url = HttpUrl.parse(original.url == null ? null : original.url.trim());
        if (url == null) throw new IOException("URL 格式无效");
        checkTransport(url);

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        builder.header("User-Agent", USER_AGENT);
        builder.header("Accept", "*/*");
        for (Map.Entry<String, String> header : original.headers.entrySet()) {
            if (safeRequestHeader(header.getKey())) builder.header(header.getKey(), header.getValue());
        }
        if (original.body != null && permitsBody(original.method)) {
            MediaType type = MediaType.parse(header(original.headers, "Content-Type"));
            builder.method(original.method, RequestBody.create(original.body,
                    type != null ? type : MediaType.parse("application/octet-stream")));
        } else {
            builder.method(original.method, null);
        }

        Call call = CLIENT.newCall(builder.build());
        if (Thread.currentThread().isInterrupted()) {
            call.cancel();
            throw new IOException("阅读网络请求已取消");
        }
        long started = System.nanoTime();
        try (okhttp3.Response response = call.execute()) {
            return readResponse(response, maxBytes);
        } catch (java.io.InterruptedIOException stalled) {
            // HTTP/2 through some middleboxes stalls in the read phase; HTTP/1.1 to the same host
            // usually answers in a second. Retry once on 1.1 rather than failing the whole source.
            if (System.nanoTime() - started > 18_000_000_000L) throw stalled;
            Call retry = CLIENT_H1.newCall(builder.build());
            if (Thread.currentThread().isInterrupted()) {
                retry.cancel();
                throw new IOException("阅读网络请求已取消");
            }
            try (okhttp3.Response response = retry.execute()) {
                return readResponse(response, maxBytes);
            }
        }
    }

    private static Response readResponse(okhttp3.Response response, int maxBytes) throws IOException {
        int status = response.code();
        if (status < 200 || status >= 300) {
            String detail = peek(response, Math.min(maxBytes, 64 * 1024));
            detail = detail.replaceAll("\\s+", " ").trim();
            if (detail.length() > 240) detail = detail.substring(0, 240) + "…";
            throw new IOException("HTTP " + status + (detail.isEmpty() ? "" : "：" + detail));
        }
        String text = peek(response, maxBytes);
        return new Response(response.request().url().toString(), text, copyHeaders(response));
    }

    /** Reads the body through a byte budget, decoding Content-Encoding if it survived negotiation. */
    private static String peek(okhttp3.Response response, int maxBytes) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return "";
        String declaredLength = response.header("Content-Length");
        if (declaredLength != null) {
            try {
                if (Long.parseLong(declaredLength.trim()) > maxBytes) {
                    throw new IOException("响应超过 " + maxBytes + " 字节上限");
                }
            } catch (NumberFormatException ignored) { /* chunked or bogus; the stream budget still applies */ }
        }
        InputStream raw = body.byteStream();
        String encoding = lower(response.header("Content-Encoding"));
        if (encoding.contains("gzip")) raw = new GZIPInputStream(raw);
        else if (encoding.contains("deflate")) raw = new InflaterInputStream(raw);
        String text;
        try (InputStream in = raw; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) throw new IOException("响应超过 " + maxBytes + " 字节上限");
                out.write(buffer, 0, count);
            }
            MediaType contentType = body.contentType();
            Charset charset = contentType == null ? StandardCharsets.UTF_8
                    : contentType.charset(StandardCharsets.UTF_8);
            text = new String(out.toByteArray(), charset);
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static OkHttpClient buildClient(boolean media, boolean http1Only, int callTimeoutMs) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .dns(new PublicDns())
                .proxy(Proxy.NO_PROXY)
                .protocols(http1Only
                        ? java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1)
                        : java.util.Arrays.asList(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .cookieJar(new HostCookieJar());
        builder.addInterceptor(new SafeRedirect());
        if (media) builder.addNetworkInterceptor(new TransportGuard());
        return builder.build();
    }

    /** Validates scheme, credentials, hostname and every currently resolved address (public API). */
    public static URI validatePublicUrl(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new IOException("URL 为空");
        final URI uri;
        try {
            uri = URI.create(raw.trim()).normalize();
        } catch (RuntimeException e) {
            throw new IOException("URL 格式无效", e);
        }
        String scheme = lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("仅支持 http/https URL");
        }
        if (uri.getRawUserInfo() != null) throw new IOException("URL 不得包含账户口令");
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("URL 缺少合法主机名");
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local") || normalizedHost.endsWith(".internal")) {
            throw new IOException("拒绝本机或局域网地址");
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new IOException("无法解析主机名：" + host, e);
        }
        if (addresses.length == 0) throw new IOException("主机名没有可用地址");
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IOException("拒绝回环、私网或链路本地地址：" + host);
            }
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IOException("URL 端口无效");
        return uri;
    }

    /** Public address test: rejects loopback/private/link-local/mapped/NAT64 private bypasses. */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return publicV4(bytes, 0);
        if (bytes.length == 16) {
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) embed an IPv4 address.
            boolean leadingZero = true;
            for (int i = 0; i < 10; i++) if (bytes[i] != 0) { leadingZero = false; break; }
            if (leadingZero && ((bytes[10] & 0xff) == 0xff || (bytes[10] == 0 && bytes[11] == 0))) {
                return publicV4(bytes, 12);
            }
            // NAT64 well-known prefix 64:ff9b::/96 embeds an IPv4 address.
            if (bytes[0] == 0x00 && bytes[1] == 0x64 && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                    && allZero(bytes, 4, 12)) {
                return publicV4(bytes, 12);
            }
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false; // IPv6 unique-local fc00::/7
            if (first == 0xfe && (bytes[1] & 0xc0) == 0xc0) return false; // fec0::/10 site-local
        }
        return true;
    }

    private static boolean publicV4(byte[] bytes, int offset) {
        int a = bytes[offset] & 255;
        int b = bytes[offset + 1] & 255;
        int c = bytes[offset + 2] & 255;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false; // carrier-grade NAT
        if (a == 169 && b == 254) return false; // link-local
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 198 && (b == 18 || b == 19)) return false; // benchmarking
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false; // 192.0.0.0/24, 192.0.2.0/24
        if (a == 198 && b == 51 && c == 100) return false; // 198.51.100.0/24
        if (a == 203 && b == 0 && c == 113) return false; // 203.0.113.0/24
        return true;
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private static void checkTransport(HttpUrl url) throws IOException {
        String scheme = url.scheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("仅支持 http/https 传输");
        }
        if (url.encodedUsername() != null && !url.encodedUsername().isEmpty()) {
            throw new IOException("URL 不得包含账户口令");
        }
        String host = url.host();
        if (host == null || host.isEmpty()) throw new IOException("URL 缺少合法主机名");
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")) {
            throw new IOException("拒绝本机或局域网地址");
        }
    }

    /** DNS that hands back only validated public addresses, so the connection uses them directly. */
    private static final class PublicDns implements Dns {
        private static final long TTL_NANOS = 300_000_000_000L; // 5 min: avoids a lookup per request on hot sites
        private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            CacheEntry cached = CACHE.get(hostname);
            if (cached != null && System.nanoTime() - cached.at < TTL_NANOS) return cached.addresses;
            final InetAddress[] resolved;
            try {
                resolved = InetAddress.getAllByName(hostname);
            } catch (IOException e) {
                // A transient resolver hiccup should not break a host that resolved moments ago.
                if (cached != null) return cached.addresses;
                throw new UnknownHostException("无法解析主机名：" + hostname);
            }
            if (resolved.length == 0) throw new UnknownHostException("主机名没有可用地址：" + hostname);
            for (InetAddress address : resolved) {
                if (!isPublicAddress(address)) {
                    throw new UnknownHostException("拒绝回环、私网或链路本地地址：" + hostname);
                }
            }
            // Prefer IPv4: plenty of phones and CI hosts have a routeless IPv6 stack, and trying
            // the AAAA record first fails with "Network is unreachable" even though IPv4 works.
            List<InetAddress> ordered = new java.util.ArrayList<>(resolved.length);
            for (InetAddress address : resolved) if (address.getAddress().length == 4) ordered.add(address);
            for (InetAddress address : resolved) if (address.getAddress().length != 4) ordered.add(address);
            List<InetAddress> result = List.copyOf(ordered);
            if (CACHE.size() > 128) CACHE.clear();
            CACHE.put(hostname, new CacheEntry(result, System.nanoTime()));
            return result;
        }

        private static final class CacheEntry {
            final List<InetAddress> addresses;
            final long at;
            CacheEntry(List<InetAddress> addresses, long at) { this.addresses = addresses; this.at = at; }
        }
    }

    /** Per-host cookie jar so a session survives redirects without leaking across origins. */
    private static final class HostCookieJar implements CookieJar {
        private final Map<String, List<Cookie>> store = new ConcurrentHashMap<>();

        @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            if (!cookies.isEmpty()) store.put(url.host(), cookies);
        }

        @Override public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = store.get(url.host());
            return cookies == null ? List.of() : cookies;
        }
    }

    /** Hop-by-hop redirect follower that re-checks every target before it is contacted. */
    private static final class SafeRedirect implements Interceptor {
        @Override public okhttp3.Response intercept(Chain chain) throws IOException {
            okhttp3.Request request = chain.request();
            checkTransport(request.url());
            okhttp3.Response response = chain.proceed(request);
            for (int hop = 0; isRedirect(response.code()); hop++) {
                if (hop >= MAX_REDIRECTS) {
                    response.close();
                    throw new IOException("重定向超过 " + MAX_REDIRECTS + " 次");
                }
                String location = response.header("Location");
                if (location == null || location.trim().isEmpty()) {
                    response.close();
                    throw new IOException("HTTP " + response.code() + " 重定向缺少 Location");
                }
                HttpUrl next = request.url().resolve(location.trim());
                if (next == null) {
                    response.close();
                    throw new IOException("重定向目标不是合法 HTTP(S) 地址");
                }
                checkTransport(next);
                if (request.url().isHttps() && !next.isHttps()) {
                    response.close();
                    throw new IOException("拒绝 HTTPS 降级重定向");
                }
                boolean sameOrigin = origin(request.url()).equals(origin(next));
                okhttp3.Request.Builder builder = request.newBuilder().url(next);
                int code = response.code();
                if (code == 303 || ((code == 301 || code == 302)
                        && !"GET".equals(request.method()) && !"HEAD".equals(request.method()))) {
                    builder.method("GET", null);
                }
                if (!sameOrigin) {
                    builder.removeHeader("Authorization");
                    builder.removeHeader("Cookie");
                    builder.removeHeader("Proxy-Authorization");
                }
                okhttp3.Request following = builder.build();
                response.close();
                request = following;
                response = chain.proceed(request);
            }
            return response;
        }
    }

    /** Media segments: refuse any non-HTTP(S) hop that a datasource might hand us. */
    private static final class TransportGuard implements Interceptor {
        @Override public okhttp3.Response intercept(Chain chain) throws IOException {
            checkTransport(chain.request().url());
            return chain.proceed(chain.request());
        }
    }

    private static Map<String, List<String>> copyHeaders(okhttp3.Response response) {
        Map<String, List<String>> copy = new LinkedHashMap<>(response.headers().toMultimap());
        return Collections.unmodifiableMap(copy);
    }

    private static boolean safeRequestHeader(String name) {
        if (name == null) return false;
        return !name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Connection")
                && !name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding")
                && !name.equalsIgnoreCase("Proxy-Authorization");
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static boolean permitsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String origin(HttpUrl url) {
        return lower(url.scheme()) + "://" + lower(url.host()) + ':' + url.port();
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    private static void rejectAndroidMainThread() throws IOException {
        try {
            Class<?> looper = Class.forName("android.os.Looper");
            Object main = looper.getMethod("getMainLooper").invoke(null);
            Object current = looper.getMethod("myLooper").invoke(null);
            if (main != null && main == current) throw new IOException("网络读取不能在 Android 主线程执行");
        } catch (IOException e) {
            throw e;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Plain-JVM self-tests do not have android.os.Looper.
        }
    }

    static final class Request {
        final String url;
        final String method;
        final byte[] body;
        final Map<String, String> headers;

        Request(String url, String method, byte[] body, Map<String, String> headers) {
            this.url = url;
            this.method = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            this.body = body;
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        static Request get(String url) { return new Request(url, "GET", null, Map.of()); }
    }

    static final class Response {
        final String url;
        final String body;
        final Map<String, List<String>> headers;

        Response(String url, String body, Map<String, List<String>> headers) {
            this.url = url;
            this.body = body;
            this.headers = headers;
        }
    }
}
