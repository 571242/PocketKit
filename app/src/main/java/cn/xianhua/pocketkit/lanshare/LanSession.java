package cn.xianhua.pocketkit.lanshare;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次局域网互传会话的全部状态。
 *
 * 为什么是静态单例：服务（前台通知、闲置超时）和界面（Activity 可能被回收重建）
 * 必须看到同一份状态。离开页面不断开，靠的就是状态在这里而不是在 Activity 里。
 *
 * 约定：只有用户动作才会 start()，没有开机广播、没有自动重启。
 */
public final class LanSession {

    /** 会话状态变化与传输事件。回调都在主线程。 */
    public interface Observer {
        void onSessionChanged(State state);

        void onServerEvent(LanServer.Event event);
    }

    public enum State { IDLE, STARTING, RUNNING, STOPPED, ERROR }

    /** 闲置多久没有请求就自动停止并释放端口。 */
    public static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private static final Object LOCK = new Object();
    private static LanServer server;
    private static State state = State.IDLE;
    private static String lastError = "";
    private static String url = "";
    private static final List<Observer> observers = new ArrayList<>();
    /** 用户挑选的 URI → 服务端条目 id。移除共享时用它精确撤下那一条。 */
    private static final java.util.Map<String, String> sharedIds = new java.util.LinkedHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LanSession() {
    }

    public static State state() {
        synchronized (LOCK) {
            return state;
        }
    }

    public static boolean isRunning() {
        return state() == State.RUNNING;
    }

    public static String lastError() {
        synchronized (LOCK) {
            return lastError;
        }
    }

    /** 浏览器地址（含令牌）；未运行时为空串。 */
    public static String url() {
        synchronized (LOCK) {
            return url;
        }
    }

    public static int port() {
        LanServer active = server;
        return active == null ? -1 : active.port();
    }

    public static void addObserver(Observer observer) {
        synchronized (LOCK) {
            if (!observers.contains(observer)) {
                observers.add(observer);
            }
        }
        observer.onSessionChanged(state());
    }

    public static void removeObserver(Observer observer) {
        synchronized (LOCK) {
            observers.remove(observer);
        }
    }

    static void notifyState(State next, String message) {
        synchronized (LOCK) {
            state = next;
            if (message != null) {
                lastError = message;
            }
        }
        final State snapshot = state();
        main.post(() -> {
            List<Observer> copy;
            synchronized (LOCK) {
                copy = new ArrayList<>(observers);
            }
            for (Observer observer : copy) {
                observer.onSessionChanged(snapshot);
            }
        });
    }

    private static void notifyEvent(LanServer.Event event) {
        main.post(() -> {
            List<Observer> copy;
            synchronized (LOCK) {
                copy = new ArrayList<>(observers);
            }
            for (Observer observer : copy) {
                observer.onServerEvent(event);
            }
        });
    }

    /** 当前会话里浏览器可下载的文件（手机挑的 + 已上传的）。 */
    public static List<LanServer.SharedFile> files() {
        LanServer active = server;
        return active == null ? new ArrayList<>() : active.files();
    }

    public static List<LanServer.Message> messages() {
        LanServer active = server;
        return active == null ? new ArrayList<>() : active.messages();
    }

    public static List<LanServer.Transfer> transfers() {
        LanServer active = server;
        return active == null ? new ArrayList<>() : active.transfers();
    }

    public static long lastActivity() {
        LanServer active = server;
        return active == null ? 0 : active.lastActivity();
    }

    /** 手机侧发文字。未运行时返回 false。 */
    public static boolean sendText(String text) {
        LanServer active = server;
        if (active == null || !active.isRunning()) {
            return false;
        }
        active.sendText(text);
        return true;
    }

    /**
     * 加入用户明确选中的文件；只共享这一个文件本身，不公开它所在的目录。
     *
     * 内容在使用时才从 ContentResolver 打开：先要一个长期句柄会让授权提前失效，
     * 而且用户可能一次挑很多大文件，不能都开着。
     *
     * 同一份 URI 重复加入时会更新原有条目（换名字/换大小），不会在浏览器里出现两份。
     */
    public static void share(final Context context, final Uri uri, String name, long size)
            throws IOException {
        LanServer active = server;
        if (active == null || !active.isRunning()) {
            throw new IOException("共享尚未启动");
        }
        synchronized (LOCK) {
            // Returning to the screen must not invalidate browser download links.
            if (sharedIds.containsKey(uri.toString())) return;
            String id = active.share(name, size, () -> {
                InputStream stream = context.getContentResolver().openInputStream(uri);
                if (stream == null) {
                    throw new IOException("打不开「" + name + "」，它可能已被移动或删除");
                }
                return stream;
            });
            sharedIds.put(uri.toString(), id);
        }
    }

    /** Restore the selection UI without adding files that were not explicitly shared. */
    public static List<Uri> selectedUris() {
        synchronized (LOCK) {
            List<Uri> values = new ArrayList<>();
            for (String uri : sharedIds.keySet()) values.add(Uri.parse(uri));
            return values;
        }
    }

    /** 撤下某个被挑选的 URI；返回它是否本来就在共享里。 */
    public static boolean unshare(Uri uri) {
        LanServer active = server;
        if (active == null) {
            return false;
        }
        synchronized (LOCK) {
            String id = sharedIds.remove(uri.toString());
            return id != null && active.unshare(id);
        }
    }

    /**
     * 启动会话。会抛出让界面直接显示的失败原因。
     *
     * @param receiverTree 用户选定的 SAF 接收目录；为 null 时不允许上传（只会是启动方的 bug）。
     */
    public static void start(Context context, String bindAddress, String pageHtml, Uri receiverTree)
            throws IOException {
        synchronized (LOCK) {
            if (state == State.RUNNING || state == State.STARTING) {
                return;
            }
            state = State.STARTING;
        }
        try {
            String token = newToken();
            SafStore store = new SafStore(context.getContentResolver(), receiverTree);
            LanServer created = new LanServer(token, store, pageHtml, LanSession::notifyEvent,
                    java.net.InetAddress.getByName(bindAddress), 0, 4L * 1024 * 1024 * 1024);
            created.start();
            String address = "http://" + bindAddress + ":" + created.port() + created.urlPath();
            synchronized (LOCK) {
                server = created;
                url = address;
                lastError = "";
            }
            notifyState(State.RUNNING, null);
        } catch (IOException | RuntimeException ex) {
            synchronized (LOCK) {
                server = null;
                url = "";
            }
            notifyState(State.ERROR, ex.getMessage() == null ? ex.getClass().getSimpleName()
                    : ex.getMessage());
            throw ex instanceof IOException ? (IOException) ex : new IOException(ex);
        }
    }

    /** 停止并释放端口。可重复调用。 */
    public static void stop() {
        LanServer active;
        synchronized (LOCK) {
            active = server;
            server = null;
            url = "";
            if (state != State.IDLE) {
                state = State.STOPPED;
            }
        }
        if (active != null) {
            active.stop();
        }
        synchronized (LOCK) {
            sharedIds.clear();
        }
        notifyState(State.STOPPED, null);
    }

    /** 用户离开后彻底清空（不保留上一次的错误提示）。 */
    public static void reset() {
        stop();
        synchronized (LOCK) {
            state = State.IDLE;
            lastError = "";
        }
        notifyState(State.IDLE, "");
    }

    /** 会话令牌：32 位 base32，SecureRandom。猜不到就等于连不上。 */
    static String newToken() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 从 assets/lanshare/index.html 读页面；读不到就抛错，不拿空页面糊过去。 */
    public static String readPage(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("lanshare/index.html")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }
}
