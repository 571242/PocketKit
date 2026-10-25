package cn.xianhua.pocketkit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.LinearLayout;
import cn.xianhua.pocketkit.pinoverlay.PinGeometry;
import cn.xianhua.pocketkit.pinoverlay.PinImageLoader;
import cn.xianhua.pocketkit.pinoverlay.PinOverlayService;
import java.io.File;
import java.io.IOException;

/**
 * Entry for the pinned reference image: pick or receive one image, preview it, and start the
 * user-started overlay. The activity itself never draws over another app.
 */
public final class PinImageActivity extends BaseToolActivity {
    private static final int PICK = 7801;
    private File staged;
    private Bitmap shown;
    private ImageView preview;

    @Override protected void onCreate(Bundle state) {
        // BaseToolActivity reads the header title and the back button from the intent before it
        // builds the view, and a share-sheet launch carries no extras, so both defaults go first.
        if (!getIntent().hasExtra("title")) getIntent().putExtra("title", "贴屏参考图");
        if (!getIntent().hasExtra("tool_id")) getIntent().putExtra("tool_id", "I01");
        super.onCreate(state);
        note("选一张图钉在屏幕最上层当参考：单指拖动、双指缩放、操控条调不透明度或收起成小块。不读取其他应用画面，也不是分屏。");
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setContentDescription("所选参考图预览");
        body.addView(preview, new LinearLayout.LayoutParams(-1, dp(220)));
        button("选择图片", this::pick);
        button("授权悬浮窗", () -> {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            show("开启「显示在其他应用上层」后回到这里，再点「开启贴屏」。");
        });
        if (Build.VERSION.SDK_INT >= 33) button("授权通知（推荐）", () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7802));
        button("开启贴屏", this::startPin);
        button("关闭贴屏", () -> {
            stopService(new Intent(this, PinOverlayService.class));
            show("已关闭贴屏参考图。");
        });
        if (state != null && state.getString("staged") != null) {
            File kept = new File(state.getString("staged"));
            if (kept.isFile()) previewFile(kept); else kept.delete();
        } else {
            handleIncoming(getIntent());
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (staged != null) state.putString("staged", staged.getAbsolutePath());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    /** ACTION_SEND from a gallery or screenshot share sheet, or getData() from a file manager. */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;
        try {
            if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (SecurityException grantIsTemporary) {
            // A screenshot provider may hand out a one-activity grant; the copy below does not need more.
        }
        stage(uri);
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK); }
        catch (RuntimeException missing) { fail(new IllegalStateException("没有可用的图片选择器")); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        try { getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException grantIsTemporary) { /* copied below, so a transient grant is enough */ }
        stage(data.getData());
    }

    /** Copies the grant into the app cache at once: the overlay then reads only its own file. */
    private void stage(Uri uri) {
        int side = PinGeometry.decodeSide(screenWidth(), screenHeight());
        background(() -> {
            File next = PinImageLoader.copy(this, uri);
            Bitmap bitmap;
            try {
                bitmap = PinImageLoader.decode(next, side);
            } catch (IOException | OutOfMemoryError error) {
                next.delete();
                throw new IOException(error instanceof OutOfMemoryError ? "图片过大，内存不足" : String.valueOf(error.getMessage()));
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) { bitmap.recycle(); next.delete(); return; }
                if (staged != null) staged.delete();
                staged = next;
                // Swap the bitmap in before recycling the old one; a recycled bitmap still bound to
                // the view would fail the next draw.
                Bitmap previous = shown;
                shown = bitmap;
                preview.setImageBitmap(bitmap);
                if (previous != null) previous.recycle();
                show("已选择 " + bitmap.getWidth() + " × " + bitmap.getHeight() + "。授权悬浮窗后点「开启贴屏」。");
            });
        });
    }

    /** Rebuilds the preview from the cache file a rotation kept, without asking for the URI again. */
    private void previewFile(File file) {
        int side = PinGeometry.decodeSide(screenWidth(), screenHeight());
        background(() -> {
            Bitmap bitmap;
            try {
                bitmap = PinImageLoader.decode(file, side);
            } catch (IOException | OutOfMemoryError error) {
                file.delete();
                throw new IOException("之前的图片已无法预览，请重新选择");
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) { bitmap.recycle(); return; }
                staged = file;
                Bitmap previous = shown;
                shown = bitmap;
                preview.setImageBitmap(bitmap);
                if (previous != null) previous.recycle();
                show("已选择 " + bitmap.getWidth() + " × " + bitmap.getHeight() + "。授权悬浮窗后点「开启贴屏」。");
            });
        });
    }

    private void startPin() {
        if (staged == null || !staged.isFile()) throw new IllegalStateException("请先选择一张图片");
        if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("请先授权悬浮窗，再回来点「开启贴屏」");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            show("贴屏已启动。未授权通知时前台通知不会显示，但仍可从本页「关闭贴屏」结束。");
        }
        try {
            startForegroundService(new Intent(this, PinOverlayService.class)
                    .putExtra(PinOverlayService.EXTRA_PATH, staged.getAbsolutePath()));
        } catch (RuntimeException refused) {
            // Android 12+ can refuse a foreground start from the background; the pin keeps its file.
            throw new IllegalStateException("系统没有允许启动贴屏，请保持本页在前台再点一次");
        }
        // The service owns the file now; a fresh copy is made on the next start.
        staged = null;
        show("贴屏已请求启动，可以切到其他应用。参考图会留在最上层，直到你在操控条或通知里关闭。");
    }

    private int screenWidth() { return getResources().getDisplayMetrics().widthPixels; }

    private int screenHeight() { return getResources().getDisplayMetrics().heightPixels; }

    @Override protected void onDestroy() {
        if (preview != null) preview.setImageDrawable(null);
        if (shown != null) shown.recycle();
        shown = null;
        if (!isChangingConfigurations() && staged != null) staged.delete();
        staged = null;
        super.onDestroy();
    }

    public static boolean supports(String id) { return "I01".equals(id); }

    public static String limitation(String id) {
        return "贴屏参考图：从相册或系统截图分享里选一张图钉在屏幕最上层，可拖动、双指缩放、调不透明度、收起成小块；需要悬浮窗授权，不读取其他应用画面。";
    }
}
