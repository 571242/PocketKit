package cn.xianhua.pocketkit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import cn.xianhua.pocketkit.media.Cancel;
import cn.xianhua.pocketkit.media.ImageIo;
import cn.xianhua.pocketkit.media.MediaIo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, local-only image patch editor. Original documents are never opened for writing. */
public final class PatchEditorActivity extends Activity {
    private static final int PICK_IMAGE = 9101;
    private static final int SAVE_IMAGE = 9102;
    // Keep one full ARGB image plus the editor's copy inside a phone heap; larger photos are
    // refused with a message instead of being silently downsampled.
    private static final long MAX_PIXELS = 8_000_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PatchEditorView editor;
    private TextView hint, featherLabel;
    private Button choose, save, select, extract, stamp, undo, shape;
    private final Button[] arrows = new Button[4];
    private Spinner blend;
    private SeekBar feather;
    private int ink, muted, accent, surface, base, outline;
    private boolean busy, dirty, oval;
    private Uri source;
    private File prepared;

    public static boolean supports(String id) { return "I02".equals(id); }
    public static String limitation(String id) {
        return "从图片取样背景或文字手动盖印；不是 AI 自动消字。原尺寸另存 PNG，不保留照片元数据。";
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ink = Color.parseColor(dark ? "#E7EEFC" : "#172D4D");
        muted = Color.parseColor(dark ? "#A8B8D1" : "#61718A");
        accent = Color.parseColor(dark ? "#9BBCFF" : "#2264DC");
        surface = Color.parseColor(dark ? "#1B293E" : "#FFFFFF");
        base = Color.parseColor(dark ? "#101B2B" : "#F3F6FB");
        outline = Color.parseColor(dark ? "#40516C" : "#D7E0ED");
        buildUi();
        updateControls();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(base);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        LinearLayout header = row();
        header.setPadding(dp(6), 0, dp(6), 0);
        Button back = control("返回", false);
        back.setOnClickListener(v -> onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(62), dp(48)));
        TextView title = new TextView(this);
        ToolUi.text(title, ink, 19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setText("图片修字");
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        choose = control("选图", false);
        choose.setOnClickListener(v -> chooseImage());
        header.addView(choose, new LinearLayout.LayoutParams(dp(62), dp(48)));
        save = control("保存", true);
        save.setOnClickListener(v -> saveImage());
        header.addView(save, new LinearLayout.LayoutParams(dp(62), dp(44)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));

        hint = new TextView(this);
        ToolUi.text(hint, muted, 13);
        hint.setPadding(dp(14), dp(4), dp(14), dp(5));
        hint.setMinHeight(dp(36));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        editor = new PatchEditorView(this);
        editor.setContentDescription("图片修字画布");
        editor.setOnStateChanged(this::updateControls);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(5), dp(8), dp(5));
        panel.setBackgroundColor(surface);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = row();
        select = addWeighted(actions, "新建选区", false, () -> editor.beginSelection());
        extract = addWeighted(actions, "提取", true, () -> {
            if (!editor.extractPatch()) toast("请先把选框放在图片里");
        });
        stamp = addWeighted(actions, "盖印", true, () -> {
            if (editor.stamp()) { dirty = true; updateControls(); }
            else toast("请先提取切片并将它移到图片上");
        });
        undo = addWeighted(actions, "撤销", false, () -> { if (editor.undo()) dirty = true; updateControls(); });
        panel.addView(actions);

        LinearLayout settings = row();
        shape = control("矩形选区", false);
        shape.setOnClickListener(v -> {
            oval = !oval;
            editor.setOval(oval);
            shape.setText(oval ? "椭圆选区" : "矩形选区");
        });
        settings.addView(shape, new LinearLayout.LayoutParams(dp(94), dp(44)));
        blend = new Spinner(this);
        String[] modes = {"正常覆盖", "留黑字 · 正片叠底", "留白字 · 滤色"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, modes) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                TextView text = (TextView) super.getView(position, convert, parent);
                ToolUi.text(text, ink, 14);
                return text;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        blend.setAdapter(adapter);
        blend.setContentDescription("切片混合方式");
        blend.setPadding(dp(10), 0, dp(8), 0);
        blend.setBackground(ToolUi.ripple(this, surface, dp(10), outline, dp(1)));
        blend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editor.setBlendMode(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams blendParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        blendParams.leftMargin = dp(5);
        settings.addView(blend, blendParams);
        panel.addView(settings);

        LinearLayout featherRow = row();
        featherLabel = new TextView(this);
        ToolUi.text(featherLabel, ink, 13);
        featherLabel.setText("羽化 4px");
        featherRow.addView(featherLabel, new LinearLayout.LayoutParams(dp(82), -2));
        feather = new SeekBar(this);
        feather.setMax(20);
        feather.setProgress(4);
        feather.setContentDescription("切片边缘羽化像素");
        feather.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                featherLabel.setText("羽化 " + value + "px");
                editor.setFeather(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        editor.setFeather(4);
        featherRow.addView(feather, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(featherRow);

        LinearLayout nudge = row();
        TextView nudgeLabel = new TextView(this);
        ToolUi.text(nudgeLabel, muted, 13);
        nudgeLabel.setText("微调 1 像素");
        nudge.addView(nudgeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        String[] names = {"←", "↑", "↓", "→"};
        String[] descriptions = {"切片左移一像素", "切片上移一像素", "切片下移一像素", "切片右移一像素"};
        int[] dx = {-1, 0, 0, 1}, dy = {0, -1, 1, 0};
        for (int i = 0; i < arrows.length; i++) {
            final int index = i;
            arrows[i] = control(names[i], false);
            arrows[i].setContentDescription(descriptions[i]);
            arrows[i].setOnClickListener(v -> editor.nudgePatch(dx[index], dy[index]));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(44));
            p.leftMargin = dp(3);
            nudge.addView(arrows[i], p);
        }
        panel.addView(nudge);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(darkMode() ? 0 :
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
        root.requestApplyInsets();
    }

    private boolean darkMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button control(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setStateListAnimator(null);
        ToolUi.text(button, primary ? (darkMode() ? base : Color.WHITE) : ink, 13);
        button.setBackground(ToolUi.ripple(this, primary ? accent : surface, dp(10),
                primary ? Color.TRANSPARENT : outline, primary ? 0 : dp(1)));
        return button;
    }

    private Button addWeighted(LinearLayout row, String label, boolean primary, Runnable action) {
        Button button = control(label, primary);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, p);
        return button;
    }

    private void updateControls() {
        if (editor == null || choose == null || save == null || select == null || shape == null) return;
        boolean image = editor.hasImage();
        boolean selecting = editor.isSelecting();
        boolean patch = editor.hasPatch();
        available(choose, !busy);
        available(save, !busy && image);
        available(select, !busy && image);
        available(extract, !busy && image && selecting);
        available(stamp, !busy && patch && !selecting);
        available(undo, !busy && editor.canUndo());
        available(shape, !busy && selecting);
        available(blend, !busy && patch && !selecting);
        available(feather, !busy && image);
        for (Button arrow : arrows) if (arrow != null) available(arrow, !busy && patch && !selecting);
        editor.setEnabled(!busy);
        if (busy) hint.setText("正在处理图片，请稍候…");
        else if (!image) hint.setText("选图后，取一块背景盖住旧字，或复制现成文字补字。");
        else if (selecting) hint.setText("拖动选框或四角对准取样；双指缩放、平移图片，然后点提取。");
        else if (patch) hint.setText("单指任意处拖动切片，双指缩放图片；对齐后盖印，可重复使用。");
        else hint.setText("点新建选区，框住干净背景或现成文字。");
    }

    private void available(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    private void chooseImage() {
        if (dirty) {
            new AlertDialog.Builder(this).setMessage("当前图片还有未保存的修改，换图会丢失这些修改。")
                    .setNegativeButton("继续编辑", null).setPositiveButton("换图", (d, w) -> launchPicker()).show();
        } else launchPicker();
    }

    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_IMAGE); }
        catch (RuntimeException ex) { toast("没有可用的图片选择器"); }
    }

    private void loadImage(Uri uri) {
        busy = true;
        updateControls();
        worker.execute(() -> {
            Bitmap decoded = null;
            try {
                decoded = ImageIo.decode(this, uri, MAX_PIXELS, true, true, new Cancel());
                Bitmap result = decoded;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { result.recycle(); return; }
                    try {
                        int width = result.getWidth();
                        int height = result.getHeight();
                        editor.setImage(result);
                        if (!editor.hasImage()) {
                            toast("图片过大，设备内存不足，请选择较小的图片");
                            return;
                        }
                        source = uri;
                        dirty = false;
                        toast("已载入 " + width + " × " + height + "，点新建选区开始");
                    } catch (OutOfMemoryError error) {
                        toast("图片过大，设备内存不足，请选择较小的图片");
                    } finally {
                        if (!result.isRecycled()) result.recycle();
                        busy = false;
                        updateControls();
                    }
                });
            } catch (Exception | OutOfMemoryError ex) {
                if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                reportFailure(ex);
            }
        });
    }

    private void saveImage() {
        if (!editor.hasImage() || busy) return;
        if (editor.hasPatch() && !editor.isSelecting()) {
            new AlertDialog.Builder(this)
                    .setMessage("保存的是已盖印的内容，浮动切片不会自动加入。")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("盖印并保存", (d, w) -> {
                        if (editor.stamp()) { dirty = true; prepareSave(); }
                        else toast("请先把切片移到图片上");
                    })
                    .setPositiveButton("保存已盖印", (d, w) -> prepareSave()).show();
        } else prepareSave();
    }

    private void prepareSave() {
        busy = true;
        updateControls();
        Bitmap bitmap = editor.getImage();
        worker.execute(() -> {
            File file = null;
            try {
                file = MediaIo.scratch(this, ".png");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("图片编码失败");
                }
                File ready = file;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { ready.delete(); return; }
                    prepared = ready;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("image/png").putExtra(Intent.EXTRA_TITLE, "图片修字.png");
                    try { startActivityForResult(intent, SAVE_IMAGE); }
                    catch (RuntimeException ex) { discardPrepared(); busy = false; updateControls(); toast("没有可用的文件保存器"); }
                });
            } catch (Exception | OutOfMemoryError ex) {
                if (file != null) file.delete();
                reportFailure(ex);
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE) {
            if (result == RESULT_OK && data != null && data.getData() != null) loadImage(data.getData());
        } else if (request == SAVE_IMAGE) {
            if (result != RESULT_OK || data == null || data.getData() == null || prepared == null) {
                discardPrepared(); busy = false; updateControls(); return;
            }
            Uri target = data.getData();
            if (sameDocument(source, target)) {
                discardPrepared(); busy = false; updateControls();
                toast("不能覆盖原图，请选择一个新文件名");
                return;
            }
            File file = prepared;
            prepared = null;
            worker.execute(() -> {
                try {
                    MediaIo.publish(this, target, file);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        dirty = false; busy = false; updateControls();
                        toast("已另存 PNG，原图未修改");
                    });
                } catch (Exception ex) { reportFailure(ex); }
                finally { file.delete(); }
            });
        }
    }

    private boolean sameDocument(Uri a, Uri b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        try {
            return java.util.Objects.equals(a.getAuthority(), b.getAuthority())
                    && DocumentsContract.isDocumentUri(this, a) && DocumentsContract.isDocumentUri(this, b)
                    && DocumentsContract.getDocumentId(a).equals(DocumentsContract.getDocumentId(b));
        } catch (RuntimeException ignored) { return false; }
    }

    private void reportFailure(Throwable error) {
        String message = error instanceof OutOfMemoryError ? "图片过大，设备内存不足" : error.getMessage();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            updateControls();
            toast("未完成：" + (message == null || message.isEmpty() ? "请重试" : message));
        });
    }

    private void discardPrepared() {
        if (prepared != null) prepared.delete();
        prepared = null;
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(float value) { return ToolUi.dp(this, value); }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }

    /** Shared by the header button, the legacy back key and API 33+ predictive back. */
    private void back() {
        if (busy) { toast("图片正在处理，请稍候"); return; }
        if (!dirty) { finish(); return; }
        new AlertDialog.Builder(this).setMessage("还有未保存的修改，退出后会丢失。")
                .setNegativeButton("继续编辑", null).setPositiveButton("退出", (d, w) -> finish()).show();
    }

    private Object backCallback;

    @android.annotation.TargetApi(33)
    private static final class Api33Back {
        static Object register(PatchEditorActivity activity) {
            android.window.OnBackInvokedCallback callback = activity::back;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(PatchEditorActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) callback);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= 33) backCallback = Api33Back.register(this);
    }

    @Override protected void onStop() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            Api33Back.unregister(this, backCallback);
            backCallback = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        discardPrepared();
        worker.shutdown();
        super.onDestroy();
    }
}
