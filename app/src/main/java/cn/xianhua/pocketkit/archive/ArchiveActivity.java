package cn.xianhua.pocketkit.archive;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cn.xianhua.pocketkit.BaseToolActivity;
import cn.xianhua.pocketkit.ToolUi;

/**
 * 归档：选一个压缩包解压，或选多个文件打成 ZIP。
 *
 * 只做压缩/解压这一件事，刻意不碰 FileToolsActivity 与 filetools 包（那边负责去重改名）：
 * 本页不解析路径、不改名、不管理目录，落点完全由 SAF 决定，解压只新建一个独立子目录。
 *
 * 三条硬约束写在实现里而不是说明里：
 *  - 全流程流式，不把整包读进内存；
 *  - 路径穿越 / 符号链接 / 条目数 / 单条大小 / 总量 / 压缩比都有界（见 {@link ArchiveLimits}）；
 *  - 取消、密码错、超上限时只删「本次新建」的东西（{@link SafDirectory#deleteCreated}），原文件不动。
 */
public class ArchiveActivity extends BaseToolActivity {

    private static final int PICK_ARCHIVE = 7401;
    private static final int PICK_FILES = 7402;
    private static final int PICK_TREE = 7403;

    private Uri archiveUri;
    private final java.util.List<Uri> sources = new java.util.ArrayList<>();
    private Uri outputTree;

    private TextView modeLabel;
    private TextView sourceLabel;
    private TextView outputLabel;
    private TextView progressLabel;
    private android.widget.EditText passwordField;
    private ProgressBar progressBar;
    private Button cancelButton;
    private volatile boolean cancelRequested;

    @Override protected void onCreate(Bundle state) {
        if (!getIntent().hasExtra("title")) getIntent().putExtra("title", "压缩与解压");
        if (!getIntent().hasExtra("tool_id")) getIntent().putExtra("tool_id", "F03");
        super.onCreate(state);
        buildUi();
        if (state != null && state.getString("archive") != null) {
            archiveUri = Uri.parse(state.getString("archive"));
        }
        if (state != null && state.getString("output") != null) {
            outputTree = Uri.parse(state.getString("output"));
        }
        render();
        if (state == null) handleIncoming(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (archiveUri != null) state.putString("archive", archiveUri.toString());
        if (outputTree != null) state.putString("output", outputTree.toString());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    // ---------------- 界面 ----------------

    private void buildUi() {
        note("选一个压缩包解压，或者选多个文件打成 ZIP。解压只往你选的目录里新建一个独立子目录，"
                + "压缩只新建一个 ZIP；都不会覆盖或删除已有文件。");
        modeLabel = note("");
        sourceLabel = note("");
        outputLabel = note("");

        button("选择压缩包（解压）", this::pickArchive);
        button("选择文件（打包成 ZIP）", this::pickFiles);
        button("选择输出目录", this::pickOutput);
        passwordField = field("密码（可选）",
                "解有密码的 ZIP 时填；打包时填了就写成 AES-256 加密 ZIP");
        passwordField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("开始解压", this::startExtract);
        button("开始压缩", this::startPack);

        progressLabel = note("还没有任务。");
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        body.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(8)));
        cancelButton = button("取消当前任务", this::requestCancel);
        cancelButton.setEnabled(false);

        TextView capability = new TextView(this);
        capability.setText("能力边界：" + ArchiveFormats.limitation());
        ToolUi.text(capability, muted, 12);
        capability.setPadding(dp(12), dp(10), dp(12), dp(10));
        capability.setBackground(ToolUi.fill(surface, dp(12), outlineColor(), dp(1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        body.addView(capability, params);
    }

    private int outlineColor() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return android.graphics.Color.parseColor(dark ? "#40516C" : "#D7E0ED");
    }

    private void render() {
        modeLabel.setText(archiveUri != null
                ? "模式：解压（已选压缩包）"
                : sources.isEmpty() ? "模式：还没选东西" : "模式：打包 " + sources.size() + " 个文件");
        sourceLabel.setText(archiveUri != null
                ? "输入：" + displayName(archiveUri)
                : sources.isEmpty() ? "输入：尚未选择"
                : "输入：" + sources.size() + " 个文件（" + describeFirst() + "…）");
        outputLabel.setText(outputTree == null ? "输出目录：尚未选择" : "输出目录：" + describeTree());
    }

    private String describeFirst() {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (Uri uri : sources) {
            if (shown++ >= 3) {
                text.append(" 等");
                break;
            }
            if (shown > 1) text.append("、");
            text.append(displayName(uri));
        }
        return text.toString();
    }

    private String describeTree() {
        String segment = outputTree.getLastPathSegment();
        return segment == null ? outputTree.toString() : segment;
    }

    // ---------------- 选择 ----------------

    private void pickArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        launch(intent, PICK_ARCHIVE, "没有可用的文件选择器。");
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        launch(intent, PICK_FILES, "没有可用的文件选择器。");
    }

    private void pickOutput() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        launch(intent, PICK_TREE, "没有可用的目录选择器，输出目录无法选择。");
    }

    private void launch(Intent intent, int request, String missing) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (android.content.ActivityNotFoundException ex) {
            fail(new IllegalStateException(missing));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == PICK_ARCHIVE || requestCode == PICK_FILES || requestCode == PICK_TREE) {
                note("已取消选择。");
            }
            return;
        }
        if (requestCode == PICK_ARCHIVE) {
            archiveUri = data.getData();
            sources.clear();
            render();
            return;
        }
        if (requestCode == PICK_FILES) {
            int added = 0;
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null && !sources.contains(uri)) {
                        sources.add(uri);
                        added++;
                    }
                }
            } else if (data.getData() != null && !sources.contains(data.getData())) {
                sources.add(data.getData());
                added++;
            }
            archiveUri = null;
            render();
            note("加了 " + added + " 个文件；上面选的压缩包已取消。");
            return;
        }
        if (requestCode == PICK_TREE) {
            Uri tree = data.getData();
            if (tree == null) return;
            try {
                // 目录授权要持久化，否则重启后写入会静默失败。
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ex) {
                note("这个目录无法长期授权，重启应用后需要重新选择。");
            }
            outputTree = tree;
            render();
        }
    }

    /** 从别的应用「打开方式 / 分享」过来：单个像压缩包就解压，多个就打包。 */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        java.util.List<Uri> incoming = new java.util.ArrayList<>();
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) incoming.add(uri);
            }
        }
        if (incoming.isEmpty()) {
            Uri data = intent.getData();
            if (data == null && Intent.ACTION_SEND.equals(intent.getAction())) {
                data = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (data != null) incoming.add(data);
        }
        incoming.removeIf(uri -> !"content".equals(uri.getScheme()));
        if (incoming.isEmpty()) return;
        if (incoming.size() > 200) { fail(new IOException("一次最多选择 200 个文件")); return; }
        if (incoming.size() == 1) {
            archiveUri = incoming.get(0);
            sources.clear();
        } else {
            sources.clear();
            sources.addAll(incoming);
            archiveUri = null;
        }
        render();
    }

    // ---------------- 解压 ----------------

    private void startExtract() {
        final Uri source = archiveUri;
        if (source == null) {
            fail(new IllegalStateException("先选一个压缩包。"));
            return;
        }
        if (outputTree == null) {
            fail(new IllegalStateException("先选输出目录：解压结果需要一个明确的落点。"));
            return;
        }
        final String name = displayName(source);
        final Uri tree = outputTree;
        background(() -> {
            cancelRequested = false;
            ExtractResult result = runExtract(source, tree, name);
            runOnUiThread(() -> {
                finishJob();
                show(result.summary());
            });
        });
    }

    private ExtractResult runExtract(Uri source, Uri tree, String displayName) {
        ContentResolver resolver = getContentResolver();
        SafDirectory output = new SafDirectory(resolver, tree);
        String root = uniqueRoot(tree, displayName);
        Progress progress = new Progress("准备中…");
        ExtractResult result = new ExtractResult();
        result.outputName = root;
        try {
            byte[] head = readHead(source);
            ArchiveFormats.Kind kind = ArchiveFormats.sniff(head, displayName);
            long archiveBytes = sizeOf(source);
            if (kind == ArchiveFormats.Kind.ZIP) {
                try (InputStream in = resolver.openInputStream(source)) {
                    if (in == null) throw new IOException("文件打不开，授权可能已失效");
                    result = new ZipExtractor(ArchiveLimits.DEFAULTS, archiveBytes)
                            .extract(in, displayName, passwordOrNull(),
                                    output, root, progress);
                }
            } else {
                result = extractWithLibarchive(resolver, source, root, output,
                        archiveBytes, progress, kind);
            }
            if (result.wipe) {
                output.deleteCreated();
            }
            return result;
        } catch (ArchiveProgress.Cancelled cancelled) {
            output.deleteCreated();
            result.cancelled = true;
            result.abort("已取消。本次新建的文件与目录已清理，原压缩包没有改动。");
            return result;
        } catch (Budget.Exceeded exceeded) {
            output.deleteCreated();
            result.abort(exceeded.getMessage() + "\n已停止，本次新建的文件与目录已清理。");
            return result;
        } catch (Exception ex) {
            output.deleteCreated();
            result.abort(message(ex));
            return result;
        }
    }

    private ExtractResult extractWithLibarchive(ContentResolver resolver, Uri source, String root,
                                                SafDirectory output, long archiveBytes,
                                                Progress progress, ArchiveFormats.Kind kind)
            throws IOException {
        if (kind == ArchiveFormats.Kind.UNKNOWN) {
            // 名字不认识也照试：libarchive 会按文件头自己判断，认不出会明确报错。
            progress.report("按文件头识别格式…", 0, -1);
        }
        ParcelFileDescriptor descriptor = null;
        File staged = null;
        try {
            descriptor = resolver.openFileDescriptor(source, "r");
        } catch (IOException | UnsupportedOperationException ex) {
            descriptor = null;
        }
        if (descriptor == null) {
            // 云盘等流式来源没有可 seek 的 fd：先流式落到缓存，再交给 libarchive。
            progress.report("来源不支持随机读取，正在缓存…", 0, archiveBytes);
            try (InputStream in = resolver.openInputStream(source)) {
                if (in == null) throw new IOException("文件打不开，授权可能已失效");
                staged = LibarchiveReader.stage(in, getCacheDir(), progress, "缓存中…");
            }
            descriptor = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        LibarchiveReader reader = LibarchiveReader.open(descriptor, staged, passwordBytes());
        return new LibarchiveExtractor(ArchiveLimits.DEFAULTS, archiveBytes)
                .extract(reader, root, output, progress);
    }

    /** 输出子目录名：压缩包基础名 + (1)(2)，绝不复用目录里已有的名字。 */
    private String uniqueRoot(Uri tree, String displayName) {
        java.util.Set<String> taken;
        try {
            taken = new SafDirectory(getContentResolver(), tree).existingNames();
        } catch (IOException ex) {
            taken = new java.util.LinkedHashSet<>();
        }
        return EntryNames.rootName(displayName, taken);
    }

    // ---------------- 压缩 ----------------

    private void startPack() {
        if (sources.isEmpty()) {
            fail(new IllegalStateException("先选要打包的文件（可多选）。"));
            return;
        }
        if (outputTree == null) {
            fail(new IllegalStateException("先选输出目录：ZIP 需要一个明确的落点。"));
            return;
        }
        final java.util.List<Uri> picked = new java.util.ArrayList<>(sources);
        final Uri tree = outputTree;
        final String name = ZipPacker.defaultName(displayName(picked.get(0)));
        background(() -> {
            cancelRequested = false;
            String message;
            try {
                message = runPack(picked, tree, name);
            } catch (Exception ex) {
                message = "未完成：" + message(ex);
            }
            String finalMessage = message;
            runOnUiThread(() -> {
                finishJob();
                show(finalMessage);
            });
        });
    }

    private String runPack(java.util.List<Uri> picked, Uri tree, String name) throws IOException {
        SafDirectory output = new SafDirectory(getContentResolver(), tree);
        java.util.Set<String> taken = output.existingNames();
        String unique = EntryNames.unique(name, taken);
        java.util.List<ArchiveSource> list = new java.util.ArrayList<>();
        for (Uri uri : picked) {
            list.add(new SafSource(getContentResolver(), uri));
        }
        Progress progress = new Progress("打包中…");
        String documentPath = unique;
        try (OutputStream out = output.create(documentPath)) {
            ZipPacker.Encryption used = ZipPacker.pack(list, out, passwordOrNull(),
                    passwordOrNull() == null ? ZipPacker.Encryption.NONE : ZipPacker.Encryption.AES256,
                    progress);
            return "已打包 " + list.size() + " 个文件 → " + unique + "\n"
                    + "加密：" + (used == ZipPacker.Encryption.AES256
                    ? "AES-256（Windows 7+ / 7-Zip 都能打开；XP 时代的老工具不认）" : "无")
                    + "\n原文件没有被移动或删除。";
        } catch (ArchiveProgress.Cancelled cancelled) {
            output.delete(documentPath);
            return "已取消，本次新建的 ZIP 已删除。原文件没有被改动。";
        } catch (IOException | RuntimeException ex) {
            output.delete(documentPath);
            throw ex;
        }
    }

    /** 密码框：空表示不加密 / 没有密码。 */
    private char[] passwordOrNull() {
        String text = passwordField == null ? "" : passwordField.getText().toString();
        return text.isEmpty() ? null : text.toCharArray();
    }

    private byte[] passwordBytes() {
        char[] chars = passwordOrNull();
        return chars == null ? null
                : new String(chars).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------- 进度 / 取消 ----------------

    private void requestCancel() {
        cancelRequested = true;
        progressLabel.setText("正在取消…（当前块写完后停下并清理本次新建的内容）");
    }

    private void finishJob() {
        cancelRequested = false;
        progressBar.setVisibility(View.GONE);
        progressBar.setIndeterminate(true);
        cancelButton.setEnabled(false);
    }

    /** 工作线程 → 主线程的进度通道；标签做了节流，避免每个 64 KiB 都刷一次 UI。 */
    private final class Progress implements ArchiveProgress {
        private final String prefix;
        private long lastUpdate;
        private long lastPercent = -1;

        Progress(String prefix) {
            this.prefix = prefix;
            runOnUiThread(() -> {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(true);
                cancelButton.setEnabled(true);
                progressLabel.setText(prefix);
            });
        }

        @Override public void onProgress(String label, long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastUpdate < 120) return;
            lastUpdate = now;
            report(label, done, total);
        }

        void report(String label, long done, long total) {
            long percent = total > 0 ? Math.min(100, done * 100 / total) : -1;
            if (percent == lastPercent && percent >= 0 && System.currentTimeMillis() - lastUpdate < 300) return;
            lastPercent = percent;
            runOnUiThread(() -> {
                progressLabel.setText(label + "\n" + ArchiveLimits.Size.text(done)
                        + (total > 0 ? " / " + ArchiveLimits.Size.text(total) + "（" + percent + "%）" : ""));
                if (percent >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress((int) percent);
                } else {
                    progressBar.setIndeterminate(true);
                }
            });
        }

        @Override public boolean isCancelled() {
            return cancelRequested || workers.isShutdown();
        }
    }

    // ---------------- 工具 ----------------

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // 拿不到显示名就退回 URI 最后一段。
        } finally {
            if (cursor != null) cursor.close();
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "未命名" : segment;
    }

    private long sizeOf(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
            // 未知大小不影响解压，只影响压缩比判断。
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private byte[] readHead(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return new byte[0];
            byte[] head = new byte[8];
            int read = 0;
            while (read < head.length) {
                int step = in.read(head, read, head.length - read);
                if (step < 0) break;
                read += step;
            }
            byte[] exact = new byte[read];
            System.arraycopy(head, 0, exact, 0, read);
            return exact;
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    private static String message(Throwable ex) {
        String text = ex.getMessage();
        return text == null || text.trim().isEmpty() ? ex.getClass().getSimpleName() : text;
    }

    /** SAF 来源的最小实现；名字与大小都从系统查，不看 URI 里的字符串。 */
    private final class SafSource implements ArchiveSource {
        private final ContentResolver resolver;
        private final Uri uri;
        private final String name;
        private final long size;

        SafSource(ContentResolver resolver, Uri uri) {
            this.resolver = resolver;
            this.uri = uri;
            this.name = displayName(uri);
            this.size = sizeOf(uri);
        }

        @Override public String name() {
            return name;
        }

        @Override public long size() {
            return size;
        }

        @Override public InputStream open() throws IOException {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) throw new IOException("文件打不开：" + name);
            return in;
        }
    }

    public static boolean supports(String id) {
        return "F03".equals(id);
    }

    public static String limitation(String id) {
        return "F03".equals(id) ? ArchiveFormats.limitation() : "";
    }
}
