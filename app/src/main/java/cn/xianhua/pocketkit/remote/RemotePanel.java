package cn.xianhua.pocketkit.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.xianhua.pocketkit.ToolUi;

/**
 * A standalone remote browser: saved connections on the left of the flow, one directory listing at
 * a time, upload and download against the app's own cache.
 *
 * <p>Kept separate from the two-pane local manager on purpose. A remote listing can block for
 * twenty seconds, and mixing that into the local panes would stall navigation the user did not
 * initiate.
 */
public final class RemotePanel {

    private static final int ACCENT_ALPHA = 38;

    private final Context context;
    private final int ink, muted, surface, accent;
    private final RemoteStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "remote-fs");
        thread.setDaemon(true);
        return thread;
    });

    /** One open connection plus the directory it is showing. */
    private static final class Session {
        RemoteClient client;
        RemoteSession config;
        String path;
        final List<RemoteClient.Entry> entries = new ArrayList<>();
    }

    private final List<Session> open = new ArrayList<>();
    /** Connections created by a worker but not yet adopted by the UI thread. */
    private final List<Session> pending = new ArrayList<>();
    private Session current;
    /** Set once the hosting screen is gone; blocks any further UI work and adoption. */
    private volatile boolean released;

    private LinearLayout listBox;
    private TextView status, pathLine;
    private boolean busy;

    public RemotePanel(Context context, int ink, int muted, int surface, int accent) {
        this.context = context;
        this.ink = ink;
        this.muted = muted;
        this.surface = surface;
        this.accent = accent;
        this.store = new RemoteStore(context);
    }

    /** Builds the panel content into the given container. */
    public void attach(LinearLayout container) {
        container.removeAllViews();

        HorizontalScrollView actions = new HorizontalScrollView(context);
        LinearLayout row = new LinearLayout(context);
        actions.addView(row);
        container.addView(actions);

        button(row, "连接", this::chooseSaved);
        button(row, "新建连接", this::editSession);
        button(row, "上级", this::goUp);
        button(row, "刷新", () -> {
            if (current != null) load(current, current.path);
        });
        button(row, "上传", this::upload);
        button(row, "下载", this::downloadSelected);
        button(row, "新建文件夹", this::mkdir);
        button(row, "断开", this::disconnect);

        status = new TextView(context);
        ToolUi.text(status, muted, 13);
        status.setPadding(0, dp(4), 0, dp(4));
        status.setText("未连接。先点「新建连接」，支持 SFTP、SMB、FTP/FTPS、WebDAV。");
        container.addView(status);

        pathLine = new TextView(context);
        ToolUi.text(pathLine, ink, 13);
        pathLine.setPadding(0, dp(4), 0, dp(4));
        container.addView(pathLine);

        ScrollView scroll = new ScrollView(context);
        listBox = new LinearLayout(context);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        container.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        renderList();
    }

    // ---------------- connection setup ----------------

    private void chooseSaved() {
        List<RemoteStore.Saved> saved = store.load();
        if (saved.isEmpty()) {
            editSession();
            return;
        }
        String[] labels = new String[saved.size()];
        for (int i = 0; i < saved.size(); i++) {
            RemoteStore.Saved item = saved.get(i);
            labels[i] = item.session.displayName() + "\n" + item.session.endpoint();
        }
        new AlertDialog.Builder(context)
                .setTitle("选择连接")
                .setItems(labels, (d, which) -> connect(saved.get(which)))
                .setNeutralButton("管理", (d, w) -> manage(saved))
                .setNegativeButton("取消", null)
                .show();
    }

    private void manage(List<RemoteStore.Saved> saved) {
        String[] labels = new String[saved.size()];
        for (int i = 0; i < saved.size(); i++) {
            labels[i] = "编辑 · " + saved.get(i).session.displayName();
        }
        new AlertDialog.Builder(context)
                .setTitle("管理连接（长按删除）")
                .setItems(labels, (d, which) -> editSession(saved.get(which)))
                .setNeutralButton("关闭", null)
                .show()
                .getListView()
                .setOnItemLongClickListener((av, view, position, id) -> {
                    RemoteStore.Saved item = saved.get(position);
                    new AlertDialog.Builder(context)
                            .setTitle("删除连接")
                            .setMessage(item.session.displayName() + "\n只删除保存的地址与密码，不会影响服务器上的文件。")
                            .setPositiveButton("删除", (dd, ww) -> {
                                store.remove(item.session.id);
                                say("已删除：" + item.session.displayName());
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                });
    }

    /** Create-or-edit dialog. A null argument creates a new session. */
    private void editSession() {
        editSession(null);
    }

    private void editSession(RemoteStore.Saved existing) {
        RemoteSession draft = existing == null ? new RemoteSession() : existing.session.copy();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        String[] protocols = new String[RemoteSession.Protocol.values().length];
        for (int i = 0; i < protocols.length; i++) {
            protocols[i] = RemoteSession.Protocol.values()[i].label;
        }
        TextView protocolLabel = new TextView(context);
        ToolUi.text(protocolLabel, muted, 12);
        protocolLabel.setText("协议（点击切换）");
        box.addView(protocolLabel);
        LinearLayout protocolRow = new LinearLayout(context);
        box.addView(protocolRow);
        final RemoteSession.Protocol[] chosen = {draft.protocol};
        final TextView hint = new TextView(context);
        ToolUi.text(hint, muted, 12);
        final List<Button> protocolButtons = new ArrayList<>();
        for (RemoteSession.Protocol protocol : RemoteSession.Protocol.values()) {
            Button option = new Button(context);
            option.setText(protocol.label);
            option.setAllCaps(false);
            ToolUi.text(option, ink, 13);
            option.setOnClickListener(v -> {
                chosen[0] = protocol;
                for (Button b : protocolButtons) {
                    boolean active = b.getText().toString().equals(protocol.label);
                    b.setTextColor(active ? accent : ink);
                }
                hint.setText(RemoteClients.hint(protocol));
            });
            protocolButtons.add(option);
            protocolRow.addView(option);
        }
        hint.setText(RemoteClients.hint(chosen[0]));
        box.addView(hint);

        EditText name = field(box, "名称（可留空）", draft.name);
        EditText host = field(box, "服务器地址（主机名或 IP）", draft.host);
        EditText port = field(box, "端口（留空用默认）",
                draft.port > 0 ? String.valueOf(draft.port) : "");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText user = field(box, "用户名", draft.username);
        EditText password = field(box, "密码", existing == null ? "" : existing.password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText share = field(box, "共享名（SMB 必填，其他协议忽略）", draft.share);
        EditText basePath = field(box, "起始路径", draft.startPath);

        android.widget.CheckBox secure = new android.widget.CheckBox(context);
        secure.setText("加密连接（FTP→FTPS / WebDAV→HTTPS）");
        secure.setTextColor(ink);
        secure.setChecked(draft.secure);
        box.addView(secure);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(existing == null ? "新建远程连接" : "编辑远程连接")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNeutralButton("保存并连接", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                RemoteSession built = build(draft, chosen[0], name, host, port, user, share, basePath, secure);
                if (built == null) return;
                if (!save(built, password.getText().toString(), existing)) return;
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                RemoteSession built = build(draft, chosen[0], name, host, port, user, share, basePath, secure);
                if (built == null) return;
                if (!save(built, password.getText().toString(), existing)) return;
                dialog.dismiss();
                connect(new RemoteStore.Saved(built, password.getText().toString()));
            });
        });
        dialog.show();
    }

    /** Validates the form; shows the reason and returns null when it is not usable. */
    private RemoteSession build(RemoteSession draft, RemoteSession.Protocol protocol, EditText name,
                                EditText host, EditText port, EditText user, EditText share,
                                EditText basePath, android.widget.CheckBox secure) {
        String hostValue = host.getText().toString().trim();
        if (hostValue.isEmpty()) {
            say("服务器地址不能为空");
            return null;
        }
        int portValue = protocol.defaultPort;
        String portText = port.getText().toString().trim();
        if (!portText.isEmpty()) {
            try {
                portValue = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                say("端口必须是数字");
                return null;
            }
            if (portValue < 1 || portValue > 65535) {
                say("端口必须在 1 到 65535 之间");
                return null;
            }
        }
        if (protocol == RemoteSession.Protocol.SMB && share.getText().toString().trim().isEmpty()) {
            say("SMB 需要填共享名，例如 Users");
            return null;
        }
        RemoteSession out = draft.copy();
        out.protocol = protocol;
        out.name = name.getText().toString().trim();
        out.host = hostValue;
        out.port = portValue;
        out.username = user.getText().toString().trim();
        out.share = share.getText().toString().trim();
        out.startPath = basePath.getText().toString().trim();
        out.secure = secure.isChecked();
        if (!out.sameServer(draft)) out.hostKey = "";
        return out;
    }

    private boolean save(RemoteSession session, String password, RemoteStore.Saved existing) {
        List<RemoteStore.Saved> all = store.load();
        if (existing != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).session.id.equals(existing.session.id)) {
                    all.set(i, new RemoteStore.Saved(session, password));
                    break;
                }
            }
        } else {
            all.add(new RemoteStore.Saved(session, password));
        }
        if (!store.save(all)) {
            say("保存失败：本机密钥库不可用，密码无法加密，因此没有写入");
            return false;
        }
        return true;
    }

    // ---------------- connection lifecycle ----------------

    private void connect(RemoteStore.Saved saved) {
        if (busy) return;
        busy = true;
        say("正在连接 " + saved.session.protocol.label + " " + saved.session.endpoint() + "…");
        worker.execute(() -> {
            Session session = new Session();
            session.config = saved.session;
            try {
                session.client = RemoteClients.open(saved.session, saved.password);
                session.path = saved.session.rootPath();
                session.entries.addAll(session.client.list(session.path));
                // Parking it before handing off means release() can still see and close a client
                // that finished connecting after the panel was destroyed.
                synchronized (pending) {
                    pending.add(session);
                }
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() -> adopt(session));
                }
            } catch (SftpHostKeys.Rejected e) {
                closeQuietly(session.client);
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() -> confirmHostKey(saved, e));
                } else say(e.getMessage());
            } catch (Exception e) {
                closeQuietly(session.client);
                say(failure(e));
            } finally {
                busy = false;
            }
        });
    }

    private void confirmHostKey(RemoteStore.Saved saved, SftpHostKeys.Rejected key) {
        if (released || !(context instanceof android.app.Activity)) return;
        android.app.Activity activity = (android.app.Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String message = key.endpoint + "\n算法：" + key.algorithm + "\n\n当前指纹\n" + key.fingerprint;
        if (key.changed) {
            new AlertDialog.Builder(context).setTitle("服务器指纹变化，已中止")
                    .setMessage(message + "\n\n已保存指纹\n" + key.previousFingerprint
                            + "\n\n可能是服务器更换密钥，也可能是冒充连接。未发送登录密码。请先通过可信渠道核实；确实更换服务器后再新建连接，不要直接忽略。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        new AlertDialog.Builder(context).setTitle("首次连接，核对服务器身份")
                .setMessage(message + "\n\n请与服务器管理员或控制台显示的指纹核对。一致后再保存信任并连接；未确认前不会发送登录密码。")
                .setPositiveButton("核对一致并连接", (dialog, which) -> {
                    if (released) return;
                    List<RemoteStore.Saved> all = store.load();
                    RemoteStore.Saved trusted = null;
                    for (int i = 0; i < all.size(); i++) {
                        RemoteStore.Saved item = all.get(i);
                        if (!item.session.id.equals(saved.session.id)) continue;
                        if (!item.session.sameServer(saved.session) || !item.session.hostKey.isEmpty()) {
                            say("连接记录已变化，请重新选择连接并核对"); return;
                        }
                        RemoteSession pinned = item.session.copy();
                        pinned.hostKey = key.encodedKey;
                        trusted = new RemoteStore.Saved(pinned, item.password);
                        all.set(i, trusted);
                        break;
                    }
                    if (trusted == null) { say("连接记录已不存在，请重新建立连接"); return; }
                    if (!store.save(all)) { say("指纹保存失败，没有连接服务器，请稍后重试"); return; }
                    connect(trusted);
                }).setNegativeButton("取消连接", (dialog, which) -> say("已取消，未信任此服务器")).show();
    }

    private void adopt(Session session) {
        synchronized (pending) {
            pending.remove(session);
        }
        if (released) {
            // The screen is gone; nothing may adopt this connection any more.
            worker.execute(() -> closeQuietly(session.client));
            return;
        }
        // Closing the old client can block for the length of a protocol goodbye (FTP QUIT alone is
        // a round trip), so it happens off the UI thread; only the references change here.
        final RemoteClient previous = current == null ? null : current.client;
        open.remove(current);
        current = session;
        open.add(session);
        renderList();
        say("已连接：" + session.config.displayName());
        if (previous != null) worker.execute(() -> closeQuietly(previous));
    }

    private void disconnect() {
        if (current == null) {
            say("当前没有连接");
            return;
        }
        Session target = current;
        current = null;
        open.remove(target);
        renderList();
        worker.execute(() -> closeQuietly(target.client));
        say("已断开：" + target.config.displayName());
    }

    // ---------------- browsing ----------------

    private void goUp() {
        if (current == null) {
            say("当前没有连接");
            return;
        }
        String parent = parentOf(current.path, current.config.protocol);
        if (parent == null) {
            say("已经在起始目录");
            return;
        }
        load(current, parent);
    }

    private void load(Session session, String path) {
        if (busy) return;
        busy = true;
        say("正在读取 " + path + "…");
        worker.execute(() -> {
            try {
                if (!session.client.alive()) {
                    // The server dropped the connection; say so instead of showing a stale list.
                    say("连接已断开，请重新连接");
                    return;
                }
                List<RemoteClient.Entry> entries = session.client.list(path);
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        if (released) return;
                        session.path = path;
                        session.entries.clear();
                        session.entries.addAll(entries);
                        current = session;
                        renderList();
                        say("共 " + entries.size() + " 项");
                    });
                }
            } catch (Exception e) {
                say(failure(e));
            } finally {
                busy = false;
            }
        });
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        if (current == null) {
            pathLine.setText("");
            TextView empty = new TextView(context);
            ToolUi.text(empty, muted, 13);
            empty.setText("未连接远程目录。");
            empty.setPadding(0, dp(12), 0, 0);
            listBox.addView(empty);
            return;
        }
        pathLine.setText(current.config.displayName() + "\n" + current.path);
        if (current.entries.isEmpty()) {
            TextView empty = new TextView(context);
            ToolUi.text(empty, muted, 13);
            empty.setText("这个目录是空的。");
            empty.setPadding(0, dp(12), 0, 0);
            listBox.addView(empty);
            return;
        }
        for (RemoteClient.Entry entry : current.entries) {
            listBox.addView(row(entry));
        }
    }

    private View row(RemoteClient.Entry entry) {
        TextView view = new TextView(context);
        ToolUi.text(view, ink, 14);
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        view.setMinHeight(dp(48));
        view.setText((entry.directory ? "📁 " : "📄 ") + entry.name
                + "\n" + (entry.directory ? "目录" : bytes(entry.size)));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        view.setBackground(background);
        view.setOnClickListener(v -> {
            if (entry.directory) load(current, entry.path);
            else actions(entry);
        });
        return view;
    }

    private void actions(RemoteClient.Entry entry) {
        String[] items = {"下载到本地缓存", "重命名", "删除"};
        new AlertDialog.Builder(context)
                .setTitle(entry.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: download(entry); break;
                        case 1: rename(entry); break;
                        default: delete(entry); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- transfers ----------------

    private void downloadSelected() {
        if (current == null) {
            say("先连接一个远程目录");
            return;
        }
        List<RemoteClient.Entry> files = new ArrayList<>();
        for (RemoteClient.Entry entry : current.entries) {
            if (!entry.directory) files.add(entry);
        }
        if (files.isEmpty()) {
            say("当前目录里没有文件；进入子目录后再试，或直接点一个文件。");
            return;
        }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = files.get(i).name + "\n" + bytes(files.get(i).size);
        }
        new AlertDialog.Builder(context)
                .setTitle("下载到本地缓存（" + current.path + "）")
                .setItems(labels, (d, which) -> download(files.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void download(RemoteClient.Entry entry) {
        if (current == null) return;
        Session session = current;
        String name = entry.name;
        say("正在下载 " + name + "…");
        worker.execute(() -> {
            File target = new File(cacheDir(), safeName(name));
            // Download to a sibling temp file first: writing straight to the target would truncate
            // any earlier copy before a single byte arrived, and a failed transfer would leave a
            // half file that looks complete.
            File temp = new File(target.getAbsolutePath() + ".part");
            try (InputStream in = session.client.open(entry.path);
                 OutputStream out = new FileOutputStream(temp)) {
                copy(in, out);
            } catch (Exception e) {
                if (!temp.delete()) temp.deleteOnExit();
                say(failure(e));
                return;
            }
            if (target.exists() && !target.delete()) {
                say("已下载到 " + temp.getAbsolutePath() + "，但无法覆盖同名旧文件");
                return;
            }
            if (!temp.renameTo(target)) {
                say("已下载到 " + temp.getAbsolutePath() + "，但重命名失败");
                return;
            }
            say("已下载 " + name + "（" + bytes(target.length()) + "）\n位置：" + target.getAbsolutePath());
        });
    }

    private void upload() {
        if (current == null) {
            say("先连接一个远程目录");
            return;
        }
        File dir = cacheDir();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            say("本地缓存 " + dir.getAbsolutePath() + " 里没有可上传的文件。\n先把文件放到这里，或从管理器复制进来。");
            return;
        }
        List<File> usable = new ArrayList<>();
        String[] labels = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            usable.add(files[i]);
            labels[i] = files[i].getName() + "\n" + bytes(files[i].length());
        }
        new AlertDialog.Builder(context)
                .setTitle("上传到 " + current.path)
                .setItems(labels, (d, which) -> uploadOne(usable.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadOne(File file) {
        Session session = current;
        worker.execute(() -> {
            String destination = join(session.path, file.getName(), session.config.protocol);
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = session.client.create(destination)) {
                copy(in, out);
                say("已上传 " + file.getName() + " → " + destination);
                List<RemoteClient.Entry> entries = session.client.list(session.path);
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        if (released) return;
                        session.entries.clear();
                        session.entries.addAll(entries);
                        renderList();
                    });
                }
            } catch (Exception e) {
                say(failure(e));
            }
        });
    }

    private void mkdir() {
        if (current == null) {
            say("先连接一个远程目录");
            return;
        }
        EditText input = new EditText(context);
        input.setHint("新建文件夹");
        new AlertDialog.Builder(context)
                .setTitle("新建文件夹")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!validEntryName(name)) return;
                    Session session = current;
                    String path = join(session.path, name, session.config.protocol);
                    worker.execute(() -> {
                        try {
                            session.client.mkdir(path);
                            say("已创建 " + path);
                            load(session, session.path);
                        } catch (Exception e) {
                            say(failure(e));
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rename(RemoteClient.Entry entry) {
        Session session = current;
        EditText input = new EditText(context);
        input.setText(entry.name);
        new AlertDialog.Builder(context)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.equals(entry.name)) return;
                    if (!validEntryName(name)) return;
                    worker.execute(() -> {
                        try {
                            session.client.rename(entry.path, name);
                            say("已重命名：" + entry.name + " → " + name);
                            load(session, session.path);
                        } catch (Exception e) {
                            say(failure(e));
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void delete(RemoteClient.Entry entry) {
        Session session = current;
        new AlertDialog.Builder(context)
                .setTitle("删除 " + entry.name + "？")
                .setMessage(entry.directory
                        // SFTP rmdir, FTP RMD and WebDAV DELETE without Depth all require an empty
                        // collection; promising a recursive delete would be a lie.
                        ? "目录必须是空的才能删除；非空会失败，需要先删里面的文件。没有回收站。"
                        : "服务器上的文件会被永久删除，没有回收站。")
                .setPositiveButton("删除", (d, w) -> worker.execute(() -> {
                    try {
                        session.client.delete(entry.path, entry.directory);
                        say("已删除：" + entry.name);
                        load(session, session.path);
                    } catch (Exception e) {
                        say(failure(e));
                    }
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- helpers ----------------

    /** Copies until the stream ends. 0-byte reads are legal and do not mean EOF. */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n == 0) continue;
            out.write(buffer, 0, n);
        }
    }

    /** Download staging area: the app cache, so nothing lands in the user's folders uninvited. */
    private File cacheDir() {
        File dir = new File(context.getCacheDir(), "remote");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // Fall back to the cache root rather than failing the transfer outright.
            return context.getCacheDir();
        }
        return dir;
    }

    /**
     * Strips any path separator from a server-provided name.
     *
     * <p>A hostile or broken server can return an entry name containing {@code ../}; writing that
     * straight into a local path would put the download outside the cache directory.
     */
    private static String safeName(String name) {
        if (name == null) return "download";
        // Split on both separators and drop "." / ".." segments entirely, so a server cannot make
        // the download land outside the cache directory no matter what it returns as a name.
        StringBuilder out = new StringBuilder();
        for (String segment : name.split("[/\\\\]")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            if (out.length() > 0) out.append('_');
            out.append(segment);
        }
        String cleaned = out.toString().trim();
        if (cleaned.isEmpty()) return "download";
        // A leading dot would hide the file, and on some file systems is treated specially.
        while (cleaned.startsWith(".")) cleaned = "_" + cleaned.substring(1);
        return cleaned;
    }

    /**
     * Accepts a single path element and nothing else.
     *
     * <p>Without this a name of {@code ../x} would be joined onto the current directory and land
     * outside it — the server would happily create the file there.
     */
    private boolean validEntryName(String name) {
        if (name.isEmpty()) {
            say("名称不能为空");
            return false;
        }
        if (".".equals(name) || "..".equals(name)) {
            say("“.”和“..”不能作为名称");
            return false;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            say("名称里不能有路径分隔符");
            return false;
        }
        if (name.indexOf('\0') >= 0) {
            say("名称里有不可用字符");
            return false;
        }
        return true;
    }

    private static String parentOf(String path, RemoteSession.Protocol protocol) {
        char separator = protocol == RemoteSession.Protocol.SMB ? '\\' : '/';
        if (path == null) return null;
        String value = path;
        while (value.length() > 1 && value.charAt(value.length() - 1) == separator) {
            value = value.substring(0, value.length() - 1);
        }
        int index = value.lastIndexOf(separator);
        // "/" or "\" alone is the root: there is nothing above it. A single leading separator
        // means the entry sits directly in the root, so going up lands on the root itself rather
        // than refusing with "already at the start directory".
        if (index < 0) return null;
        if (index == 0) return String.valueOf(separator);
        return value.substring(0, index);
    }

    private static String join(String parent, String child, RemoteSession.Protocol protocol) {
        char separator = protocol == RemoteSession.Protocol.SMB ? '\\' : '/';
        String base = parent == null || parent.isEmpty()
                ? String.valueOf(separator) : parent;
        if (base.charAt(base.length() - 1) != separator) base = base + separator;
        return base + child;
    }

    private static String bytes(long size) {
        if (size < 0) return "大小未知";
        if (size < 1024) return size + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = size / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private void button(LinearLayout row, String label, Runnable action) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        ToolUi.text(b, accent, 13);
        b.setMinHeight(dp(44));
        b.setOnClickListener(v -> {
            if (busy) {
                say("上一次操作还在进行，请稍候");
                return;
            }
            action.run();
        });
        row.addView(b);
    }

    private EditText field(LinearLayout box, String hint, String value) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        if (value != null && !value.isEmpty()) input.setText(value);
        ToolUi.text(input, ink, 14);
        box.addView(input);
        return input;
    }

    private void say(String message) {
        // A worker finishing after the screen closed must not touch a detached view tree.
        if (released) return;
        if (status == null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(() -> {
                if (!released && status != null) status.setText(message);
            });
        } else {
            status.setText(message);
        }
    }

    private static String failure(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
        return "未完成：" + message;
    }

    private void closeQuietly(RemoteClient client) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception ignored) {
            // Nothing to do: the connection is being discarded.
        }
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Closes every connection; call from the hosting activity's onDestroy.
     *
     * <p>Closing a protocol session is a network operation — FTP sends {@code QUIT} and waits for a
     * reply, SMB logs off, WebDAV tears down its pool — so it must not run on the UI thread. The
     * references are detached here immediately and the actual closes are handed to a throwaway
     * thread, because {@link #worker} is being shut down.
     */
    public void release() {
        released = true;
        List<RemoteClient> toClose = new ArrayList<>();
        synchronized (pending) {
            for (Session session : pending) toClose.add(session.client);
            pending.clear();
        }
        for (Session session : new ArrayList<>(open)) toClose.add(session.client);
        open.clear();
        current = null;
        listBox = null;
        status = null;
        pathLine = null;
        if (toClose.isEmpty()) {
            worker.shutdownNow();
            return;
        }
        Thread closer = new Thread(() -> {
            for (RemoteClient client : toClose) closeQuietly(client);
            worker.shutdownNow();
        }, "remote-fs-close");
        closer.setDaemon(true);
        closer.start();
    }
}
