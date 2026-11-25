package cn.xianhua.pocketkit.reading.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import cn.xianhua.pocketkit.ToolUi;
import cn.xianhua.pocketkit.reading.ReadingEngine;
import cn.xianhua.pocketkit.reading.ReadingModels.Chapter;
import cn.xianhua.pocketkit.reading.ReadingModels.Playback;
import cn.xianhua.pocketkit.reading.ReadingModels.Progress;
import cn.xianhua.pocketkit.reading.ReadingModels.Source;
import cn.xianhua.pocketkit.reading.ReadingStore;
import cn.xianhua.pocketkit.reading.SourceHttp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频播放：androidx.media3 ExoPlayer + PlayerView。
 *
 * <p>与阅读器同一入口（progress_id / 旧 progress_json）；选集 / 线路、倍速、横屏、续播、播完自动下一集，
 * 换集只在本线路内前后走，不会跳到别的线路。播放器只在 onCreate 建、onDestroy 释放，不常驻、不起后台服务。
 * 播放地址由引擎按需取（直链或 CMS 解析后的地址），请求交给 {@link SourceHttp#mediaClient()} 的
 * OkHttp 数据源，走公网 DNS / 重定向校验。
 */
public final class ReadingPlayerActivity extends Activity {
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private int loadVersion;

    private ReadingStore store;
    private ReadingEngine engine;
    private Source source;
    private Progress progress;
    private List<Chapter> chapters;

    private ExoPlayer player;
    private PlayerView view;
    private OkHttpDataSource.Factory http;
    private DefaultMediaSourceFactory mediaSources;
    private TextView where, progressText;
    private LinearLayout episodes;
    private HorizontalScrollView episodeScroll;
    private View prevButton, nextButton;

    private int chapterIndex;
    private int pendingJumpMs;
    private boolean landscape;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!openIntent()) return;
        if (state != null) {
            chapterIndex = state.getInt("chapter", progress.chapterIndex);
            pendingJumpMs = state.getInt("position", (int) Math.max(0, Math.min(Integer.MAX_VALUE, progress.positionMs)));
        } else {
            chapterIndex = progress.chapterIndex;
            pendingJumpMs = (int) Math.max(0, Math.min(Integer.MAX_VALUE, progress.positionMs));
        }
        buildUi();
        buildPlayer();
        playChapter(chapterIndex, pendingJumpMs);
    }

    private boolean openIntent() {
        try {
            store = new ReadingStore(this);
            engine = new ReadingEngine();
        } catch (RuntimeException problem) {
            failAndClose("阅读数据没能打开：" + ReadingUi.reason(problem));
            return false;
        }
        // Prefer the stored record id; a large catalogue cannot ride inside the Intent.
        try {
            progress = ReadingUi.readProgress(getIntent(), store);
        } catch (RuntimeException bad) {
            failAndClose(ReadingUi.reason(bad));
            return false;
        }
        if (progress == null) { failAndClose("没有要播放的视频记录"); return false; }
        if (progress.detail == null || progress.detail.chapters == null || progress.detail.chapters.isEmpty()) {
            failAndClose("这条记录没有可播放的剧集"); return false;
        }
        chapters = progress.detail.chapters;
        source = store.source(progress.sourceId);
        if (source == null) { failAndClose("这个视频所属的源已被删除"); return false; }
        String issue = ReadingEngine.supportIssue(source);
        if (issue != null && !issue.isEmpty()) { failAndClose(issue); return false; }
        return true;
    }

    // ---------------------------------------------------------------- chrome

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1520"));
        ReadingUi.insets(this, root, 8, 4);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, ReadingUi.dp(this, 2), 0, ReadingUi.dp(this, 2));
        String title = progress.detail.item != null && progress.detail.item.title != null && !progress.detail.item.title.isBlank()
                ? progress.detail.item.title : "视频";
        where = ReadingUi.bold(this, title, Color.parseColor("#E7EEFC"), 15);
        where.setMaxLines(1);
        where.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(where, new LinearLayout.LayoutParams(0, -2, 1));
        progressText = ReadingUi.label(this, "", Color.parseColor("#A8B8D1"), 12);
        header.addView(progressText);
        root.addView(header);

        view = new PlayerView(this);
        view.setUseController(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setBackgroundColor(Color.BLACK);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));

        episodeScroll = new HorizontalScrollView(this);
        episodeScroll.setHorizontalScrollBarEnabled(false);
        episodes = new LinearLayout(this);
        episodeScroll.addView(episodes);
        root.addView(episodeScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        prevButton = ReadingUi.pill(this, "上一集", Color.parseColor("#9BBCFF"), () -> jump(-1));
        footer.addView(prevButton);
        nextButton = ReadingUi.pill(this, "下一集", Color.parseColor("#9BBCFF"), () -> jump(1));
        footer.addView(nextButton);
        footer.addView(ReadingUi.pill(this, "选集", Color.parseColor("#9BBCFF"), this::chooseEpisode));
        footer.addView(ReadingUi.pill(this, "线路", Color.parseColor("#9BBCFF"), this::chooseLine));
        footer.addView(ReadingUi.pill(this, "倍速", Color.parseColor("#9BBCFF"), this::chooseSpeed));
        footer.addView(ReadingUi.pill(this, "全屏", Color.parseColor("#9BBCFF"), this::toggleFullscreen));
        HorizontalScrollView footScroll = new HorizontalScrollView(this);
        footScroll.setHorizontalScrollBarEnabled(false);
        footScroll.addView(footer);
        root.addView(footScroll, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void buildPlayer() {
        // Route media through the engine's guarded client so every request gets public-DNS and
        // redirect checks; using okhttp also keeps a single, auditable HTTP stack (no DefaultDataSource,
        // which could also reach file/content URIs).
        http = new OkHttpDataSource.Factory(SourceHttp.mediaClient())
                .setUserAgent("PocketKit/1.0");
        mediaSources = new DefaultMediaSourceFactory(http);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSources)
                .build();
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) autoNext();
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (isFinishing() || isDestroyed()) return;
                showPlaybackError(ReadingUi.reason(error));
            }
        });
        view.setPlayer(player);
    }

    // ---------------------------------------------------------------- playback

    private void playChapter(int index, int startMs) {
        if (index < 0 || index >= chapters.size()) { toast("没有这一集"); return; }
        chapterIndex = index;
        progress.chapterIndex = index;
        final Chapter chapter = chapters.get(index);
        final int ticket = ++loadVersion;
        where.setText(chapterTitle(index));
        progressText.setText((index + 1) + "/" + chapters.size() + " · 解析中…");
        renderEpisodes();
        setNavEnabled(false);

        workers.execute(() -> {
            Playback pb = null;
            String error = null;
            try {
                if (Thread.currentThread().isInterrupted()) return;
                String issue = ReadingEngine.supportIssue(source);
                if (issue != null && !issue.isEmpty()) error = issue;
                else pb = engine.playback(source, chapter);
            } catch (Exception failure) {
                error = ReadingUi.reason(failure);
            }
            final Playback ready = pb;
            final String why = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || ticket != loadVersion) return;
                if (why != null || ready == null || ready.url == null || ready.url.isBlank()) {
                    setNavEnabled(true);
                    showPlaybackError(why == null ? "这个源没有返回播放地址" : why);
                    return;
                }
                startPlayer(ready, startMs > 0 ? startMs : 0);
            });
        });
    }

    private void startPlayer(Playback pb, int startMs) {
        Map<String, String> headers = pb.headers;
        http.setDefaultRequestProperties(headers == null ? java.util.Collections.emptyMap() : headers);
        MediaItem.Builder item = new MediaItem.Builder().setUri(pb.url);
        String mime = pb.mimeType == null ? null : pb.mimeType.trim();
        if (mime != null && mime.contains("/") && !mime.contains(" ")) item.setMimeType(mime);
        player.setMediaSource(mediaSources.createMediaSource(item.build()));
        player.prepare();
        if (startMs > 0) player.seekTo(startMs);
        player.setPlayWhenReady(true);
        progress.positionMs = startMs;
        setNavEnabled(true);
        progressText.setText((chapterIndex + 1) + "/" + chapters.size());
        savePosition();
    }

    private void renderEpisodes() {
        if (episodes == null) return;
        episodes.removeAllViews();
        String group = chapters.get(chapterIndex).group;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            if (group != null && !group.isBlank() && c.group != null && !c.group.isBlank() && !group.equals(c.group)) continue;
            final int target = i;
            int color = i == chapterIndex ? Color.parseColor("#0E1520") : Color.parseColor("#9BBCFF");
            View pill = ReadingUi.pill(this, episodeLabel(i), color, () -> { if (target != chapterIndex) playChapter(target, 0); });
            if (i == chapterIndex) pill.setBackground(ToolUi.fill(Color.parseColor("#9BBCFF"), ReadingUi.dp(this, 12), 0, 0));
            episodes.addView(pill);
        }
    }

    private String episodeLabel(int index) {
        String name = chapters.get(index).name;
        if (name == null || name.isBlank()) return String.valueOf(index + 1);
        return name.length() > 10 ? name.substring(0, 10) : name;
    }

    private String chapterTitle(int index) {
        String name = chapters.get(index).name;
        return name == null || name.isBlank() ? "第 " + (index + 1) + " 集" : name;
    }

    private void setNavEnabled(boolean enabled) {
        if (prevButton != null) prevButton.setEnabled(enabled && hasNeighbour(-1));
        if (nextButton != null) nextButton.setEnabled(enabled && hasNeighbour(1));
    }

    /** The current play line's label; blank groups collapse to one "默认线路". */
    private String currentLine() {
        String g = chapters.get(chapterIndex).group;
        return g == null || g.isBlank() ? "默认线路" : g;
    }

    private boolean sameLine(int index, String line) {
        String g = chapters.get(index).group;
        return line.equals(g == null || g.isBlank() ? "默认线路" : g);
    }

    /** Next/previous episode *on the current line* only — never a chapter that belongs to another line. */
    private int neighbourIndex(int delta) {
        return neighbour(chapters, chapterIndex, delta);
    }

    static String lineOf(Chapter c) {
        return c.group == null || c.group.isBlank() ? "默认线路" : c.group;
    }

    /** Pure scanning helper: step from {@code from} by {@code delta} to the nearest chapter on the same line, or -1. */
    static int neighbour(java.util.List<Chapter> chapters, int from, int delta) {
        if (chapters == null || from < 0 || from >= chapters.size() || delta == 0) return -1;
        String line = lineOf(chapters.get(from));
        for (int i = from + delta; i >= 0 && i < chapters.size(); i += delta) {
            if (line.equals(lineOf(chapters.get(i)))) return i;
        }
        return -1;
    }

    private boolean hasNeighbour(int delta) { return neighbourIndex(delta) >= 0; }

    private void autoNext() {
        int next = neighbourIndex(1);
        if (next >= 0) playChapter(next, 0);
        else toast("本线路已经是最后一集了");
    }

    private void jump(int delta) {
        int target = neighbourIndex(delta);
        if (target < 0) { toast(delta < 0 ? "本线路已经是第一集" : "本线路已经是最后一集"); return; }
        playChapter(target, 0);
    }

    // ---------------------------------------------------------------- menu

    private void chooseEpisode() {
        List<String> labels = new ArrayList<>();
        final List<Integer> index = new ArrayList<>();
        String line = currentLine();
        for (int i = 0; i < chapters.size(); i++) {
            if (!sameLine(i, line)) continue;
            labels.add((index.size() + 1) + "  " + chapterTitle(i));
            index.add(i);
        }
        int selected = Math.max(0, index.indexOf(chapterIndex));
        new AlertDialog.Builder(this)
                .setTitle((progress.detail.item != null ? progress.detail.item.title : "选集") + " · " + line)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    dialog.dismiss();
                    int target = index.get(which);
                    if (target != chapterIndex) playChapter(target, 0);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseLine() {
        List<String> groups = new ArrayList<>();
        for (Chapter c : chapters) {
            String g = c.group == null || c.group.isBlank() ? "默认线路" : c.group;
            if (!groups.contains(g)) groups.add(g);
        }
        if (groups.size() <= 1) { toast("这个视频只有一条线路"); return; }
        String current = chapters.get(chapterIndex).group;
        if (current == null || current.isBlank()) current = "默认线路";
        int selected = Math.max(0, groups.indexOf(current));
        new AlertDialog.Builder(this)
                .setTitle("切换线路")
                .setSingleChoiceItems(groups.toArray(new String[0]), selected, (dialog, which) -> {
                    dialog.dismiss();
                    switchLine(groups.get(which));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void switchLine(String group) {
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            String g = c.group == null || c.group.isBlank() ? "默认线路" : c.group;
            if (group.equals(g)) { playChapter(i, 0); return; }
        }
        toast("这条线路没有可播放的剧集");
    }

    private void chooseSpeed() {
        String[] labels = new String[SPEEDS.length];
        int current = 0;
        float now = player == null ? 1f : player.getPlaybackParameters().speed;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = SPEEDS[i] + "×" + (Math.abs(SPEEDS[i] - now) < 0.01f ? "（当前）" : "");
            if (Math.abs(SPEEDS[i] - now) < 0.01f) current = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("倍速")
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (player != null) player.setPlaybackSpeed(SPEEDS[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleFullscreen() {
        landscape = !landscape;
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // ---------------------------------------------------------------- lifecycle

    private void savePosition() {
        if (progress == null || store == null) return;
        long pos = player == null ? progress.positionMs : Math.max(progress.positionMs, safePosition());
        progress.chapterIndex = chapterIndex;
        progress.positionMs = pos;
        progress.updatedAt = System.currentTimeMillis();
        try { store.saveProgress(progress); } catch (RuntimeException ignored) { }
    }

    private long safePosition() {
        try { return player == null ? 0 : Math.max(0, player.getCurrentPosition()); }
        catch (RuntimeException gone) { return 0; }
    }

    @Override protected void onPause() {
        if (player != null) player.pause();
        savePosition();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (player != null) progress.positionMs = Math.max(progress.positionMs, safePosition());
        super.onSaveInstanceState(state);
        state.putInt("chapter", chapterIndex);
        state.putInt("position", (int) Math.max(0, Math.min(Integer.MAX_VALUE, progress.positionMs)));
    }

    @Override protected void onDestroy() {
        loadVersion++;
        workers.shutdownNow();
        if (player != null) { player.release(); player = null; }
        if (view != null) { view.setPlayer(null); view = null; }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- misc

    private void showPlaybackError(String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("这一集播不了")
                .setMessage(message + "\n\n可以重试、换一集或换一条线路。")
                .setPositiveButton("重试", (d, w) -> playChapter(chapterIndex, 0))
                .setNegativeButton("知道了", null)
                .show();
    }

    private void failAndClose(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void toast(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
