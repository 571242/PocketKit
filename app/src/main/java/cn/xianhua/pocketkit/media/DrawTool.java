package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 005 画板：手绘 + 撤销 + 导出 PNG。
 *
 * <p>Strokes are kept as vector paths rather than painted straight into a bitmap. That is what makes
 * undo honest (a removed stroke really is gone from every later redraw), and it also means the
 * drawing stays sharp if the board is later rendered at a different size.
 */
public final class DrawTool implements Tool {

    private final Host host;

    public DrawTool(Host host) {
        this.host = host;
    }

    /** One stroke: colour, width and the points it passes through. */
    private static final class Stroke {
        final int color;
        final float width;
        final Path path = new Path();

        Stroke(int color, float width) {
            this.color = color;
            this.width = width;
        }
    }

    /** The drawable surface: owns the strokes, redraws them, and hit-tests nothing else. */
    private static final class Board extends View {
        final Deque<Stroke> strokes = new ArrayDeque<>();
        Stroke current;
        int color = Color.BLACK;
        float width = 6f;

        Board(android.content.Context context) {
            super(context);
        }

        void begin(float x, float y) {
            current = new Stroke(color, width);
            current.path.moveTo(x, y);
            strokes.addLast(current);
            invalidate();
        }

        void extend(float x, float y) {
            if (current == null) {
                return;
            }
            current.path.lineTo(x, y);
            invalidate();
        }

        void end() {
            current = null;
        }

        void undo() {
            if (!strokes.isEmpty()) {
                strokes.removeLast();
                invalidate();
            }
        }

        void clear() {
            strokes.clear();
            current = null;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            for (Stroke stroke : strokes) {
                paint.setColor(stroke.color);
                paint.setStrokeWidth(stroke.width);
                canvas.drawPath(stroke.path, paint);
            }
        }
    }

    @Override public void build() {
        host.note("手指直接画。笔画以矢量保存，撤销是真的删掉，不是盖一层白。导出为 PNG。");

        final Board board = new Board(host.activity());
        board.setBackgroundColor(Color.WHITE);
        host.body().addView(board, new LinearLayout.LayoutParams(-1, host.dp(380)));

        final Spinner colorChoice = host.select("颜色",
                "黑", "红", "蓝", "绿", "橙", "紫", "灰");
        final int[] palette = {Color.BLACK, 0xFFD32F2F, 0xFF1565C0, 0xFF2E7D32,
                0xFFE65100, 0xFF6A1B9A, 0xFF616161};
        colorChoice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                  int position, long id) {
                board.color = palette[Math.min(position, palette.length - 1)];
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        host.note("笔迹粗细");
        final SeekBar thickness = new SeekBar(host.activity());
        thickness.setMax(48);
        thickness.setProgress(6);
        thickness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                board.width = Math.max(2f, progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        host.body().addView(thickness, new LinearLayout.LayoutParams(-1, -2));

        board.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    board.begin(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    board.extend(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    board.end();
                    return true;
                default:
                    return false;
            }
        });

        host.button("撤销一笔", board::undo);
        host.button("清空画板", board::clear);

        host.button("导出为 PNG", () -> {
            if (board.strokes.isEmpty()) {
                host.show("未完成：画板上还没有内容。请先画几笔。");
                return;
            }
            // Render at a fixed size independent of the on-screen view, so the exported file is
            // predictable and does not change with the device's screen density.
            final int outW = 1080;
            final int outH = Math.max(1, Math.round(outW * board.getHeight() / (float) Math.max(1, board.getWidth())));
            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.WHITE);
                    canvas.scale(outW / (float) board.getWidth(), outH / (float) board.getHeight());
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    for (Stroke stroke : board.strokes) {
                        paint.setColor(stroke.color);
                        paint.setStrokeWidth(stroke.width);
                        canvas.drawPath(stroke.path, paint);
                    }
                    cancel.check();
                    byte[] png = ImageIo.encode(bitmap, ImgSizing.Format.PNG, 100);
                    bitmap.recycle();
                    prepared = MediaIo.scratch(host.activity(), ".png");
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(png);
                    }
                    String name = "drawing-" + System.currentTimeMillis() + ".png";
                    String summary = "画板导出：" + outW + "×" + outH + "，"
                            + board.strokes.size() + " 笔，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(png.length);
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n请选择保存位置。");
                        host.createDocument(name, "image/png", target -> host.background(() -> {
                            try {
                                MediaIo.publish(host.activity(), target, ready);
                                host.post(() -> host.show(summary + "\n\n已保存：" + name));
                            } finally {
                                if (ready.exists()) {
                                    ready.delete();
                                }
                            }
                        }));
                    });
                } catch (Cancel.Stop stopped) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.show("已取消，未写出文件。");
                    });
                } catch (Exception ex) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.fail(ex);
                    });
                } finally {
                    if (prepared != null && prepared.exists()) {
                        prepared.delete();
                    }
                }
            });
        });

        host.note("导出尺寸按画板的显示比例换算，固定 1080 像素宽，因此不同手机上结果一致。");
    }
}
