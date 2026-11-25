package cn.xianhua.pocketkit;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import cn.xianhua.pocketkit.media.MediaIo;
import cn.xianhua.pocketkit.media.VideoTool;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LinkExtractActivity extends BaseToolActivity {
    private static final int SAVE = 3603;
    private static final String SNAPSHOT = """
            (()=>{
              const media=[], add=(url,type)=>{if(url&&media.length<60)media.push([url,type||'']);};
              for(const e of document.querySelectorAll('video,audio')){
                const type=e.localName==='video'?'video/mp4':'audio/mp4';
                add(e.currentSrc||e.src,type);
                for(const s of e.querySelectorAll('source[src]'))add(s.src,s.type||type);
              }
              for(const r of performance.getEntriesByType('resource'))
                if(r.initiatorType==='video'||r.initiatorType==='audio')add(r.name,r.initiatorType+'/mp4');
              const main=document.querySelector('article,main,[role=main]')||document.body;
              const desc=document.querySelector('meta[name=description],meta[property="og:description"]');
              return {title:document.title,text:(main?.innerText?.trim()||desc?.content||'').slice(0,100000),media};
            })()
            """;

    private final LinkTransfer transfer = new LinkTransfer();
    private final LinkedHashSet<String> observed = new LinkedHashSet<>();
    private EditText input;
    private TextView status, summary;
    private FrameLayout preview;
    private Button parse, browse, rescan, textAction, mediaAction;
    private WebView web;
    private HttpUrl requested;
    private LinkPage result;
    private String userAgent = LinkTransfer.MOBILE_UA;
    private boolean busy;
    private File prepared;

    public static boolean supports(String id) { return "N03".equals(id); }
    public static String limitation(String id) { return "支持网页正文、公开影音文件和抖音分享链接。加密或分段流不能直接保存。"; }

    @Override protected void onCreate(Bundle state) {
        Intent intent = getIntent();
        intent.putExtra("title", "全能提取").putExtra("tool_id", "N03");
        super.onCreate(state);
        note("粘贴网页链接，或整段抖音分享文案。");
        input = field("链接 / 分享文案", "https://v.douyin.com/…");
        input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12_000)});
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            input.setText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
        }
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        parse = button("提取内容", this::parseLink);
        status = note("链接只会在你点提取后访问，不经过第三方解析站。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        textAction = button("查看文字", this::showText);
        mediaAction = button("查看影音", this::showMedia);
        summary = note("");
        browse = button("在网页中提取", this::openPreview);
        preview = new FrameLayout(this);
        body.addView(preview, new android.widget.LinearLayout.LayoutParams(-1, dp(330)));
        rescan = button("重新扫描网页", this::scanPreview);
        if (state != null) {
            String name = state.getString("prepared", "");
            if (name.matches("pk-[a-zA-Z0-9]+\\.link")) {
                File file = new File(new File(getCacheDir(), "media"), name);
                if (file.isFile()) { prepared = file; busy = true; status.setText("请选择保存位置"); }
            }
        }
        render();
    }

    private void render() {
        parse.setEnabled(!busy);
        browse.setEnabled(!busy);
        rescan.setEnabled(!busy);
        textAction.setEnabled(!busy);
        mediaAction.setEnabled(!busy);
        boolean hasText = result != null && !result.text.isEmpty();
        int count = result == null ? 0 : result.media.size();
        textAction.setVisibility(hasText ? View.VISIBLE : View.GONE);
        mediaAction.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        mediaAction.setText("查看影音（" + count + "）");
        summary.setVisibility(hasText || count > 0 ? View.VISIBLE : View.GONE);
        summary.setText(result == null || result.title.isEmpty() ? "" : result.title);
        if (result == null || result.title.isEmpty()) summary.setVisibility(View.GONE);
        browse.setVisibility(requested != null && web == null ? View.VISIBLE : View.GONE);
        preview.setVisibility(web == null ? View.GONE : View.VISIBLE);
        rescan.setVisibility(web == null ? View.GONE : View.VISIBLE);
    }

    private void parseLink() {
        HttpUrl url = LinkPage.fromShare(input.getText().toString());
        if (url == null) { input.setError("没有找到 HTTP / HTTPS 链接"); return; }
        input.setError(null);
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        closePreview();
        requested = url;
        result = null;
        userAgent = LinkTransfer.MOBILE_UA;
        work("正在提取…", () -> {
            LinkPage page = transfer.read(url);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                result = page;
                idle(page.media.isEmpty() ? "未找到影音。页面可播放的话，打开网页播放后再扫。" : "提取完成，选择要保存的内容。"
                );
            });
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openPreview() {
        HttpUrl page = result == null ? requested : result.url;
        if (page == null || web != null) return;
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return LinkPage.http(request.getUrl().toString()) == null;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                synchronized (observed) { observed.clear(); }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame() && "GET".equals(request.getMethod())) {
                    String link = request.getUrl().toString();
                    HttpUrl url = LinkPage.http(link);
                    if (url != null && !LinkPage.mediaMime(url, "").isEmpty()) remember(link);
                }
                return null;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (web == view && !busy) scanPreview();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (web == view && request.isForMainFrame() && !busy) status.setText("网页没加载成功，检查网络后可重新提取。");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail details) {
                if (view == web) {
                    closePreview();
                    status.setText("网页已停止，重新打开后再试。");
                    render();
                }
                return true;
            }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            HttpUrl target = LinkPage.http(url);
            if (target != null && !LinkPage.mediaMime(target, mime).isEmpty()) {
                LinkPage pageResult = new LinkPage(page);
                if (result != null && result.url.equals(page)) {
                    pageResult.media.putAll(result.media);
                    pageResult.text = result.text;
                    pageResult.title = result.title;
                }
                pageResult.add(url, mime);
                result = pageResult;
                userAgent = ua == null ? web.getSettings().getUserAgentString() : ua;
                if (!busy) idle("找到影音文件，点「查看影音」选择保存。");
            }
        });
        preview.addView(web, new FrameLayout.LayoutParams(-1, -1));
        render();
        status.setText("网页加载中。页面能播放的话，播放几秒再点「重新扫描网页」。");
        web.loadUrl(LinkPage.douyinWatch(page).toString());
    }

    private void remember(String link) {
        synchronized (observed) { if (observed.size() < 60) observed.add(link); }
    }

    private void scanPreview() {
        WebView view = web;
        if (view == null || busy) return;
        HttpUrl page = LinkPage.http(view.getUrl());
        if (page == null) return;
        view.evaluateJavascript(SNAPSHOT, json -> {
            if (view != web || busy || !page.equals(LinkPage.http(view.getUrl()))) return;
            try {
                JSONObject data = new JSONObject(json);
                LinkPage scanned = new LinkPage(page);
                scanned.title = data.optString("title");
                scanned.text = LinkPage.limitText(data.optString("text"));
                if (result != null && (result.url.equals(page) || page.equals(LinkPage.douyinWatch(result.url)))) {
                    if (scanned.text.isEmpty()) scanned.text = result.text;
                    scanned.media.putAll(result.media);
                }
                JSONArray items = data.optJSONArray("media");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONArray row = items.getJSONArray(i);
                    scanned.add(row.getString(0), row.optString(1));
                }
                synchronized (observed) { for (String link : observed) scanned.add(link, ""); }
                result = scanned;
                userAgent = view.getSettings().getUserAgentString();
                idle(scanned.media.isEmpty() ? "没找到可提取的影音。先确认网页能播放，再重扫。" : "已扫描当前网页，选择要保存的内容。");
            } catch (Exception ignored) { idle("网页仍在加载，请稍后重扫。"); }
        });
    }

    private void showText() {
        if (result == null || result.text.isEmpty()) return;
        String text = result.text, name = fileName(result.title, "网页文字", "txt");
        TextView content = new TextView(this);
        ToolUi.text(content, ink, 15);
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        content.setTextIsSelectable(true);
        content.setText(text);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setTitle("网页文字").setView(scroll)
                .setNegativeButton("关闭", null).setNeutralButton("复制文字", (d, w) -> copy("网页文字", text))
                .setPositiveButton("保存 TXT", (d, w) -> prepare(null, false, text, name)).show();
    }

    private void showMedia() {
        if (result == null) return;
        LinkPage.Item[] items = result.media.values().toArray(new LinkPage.Item[0]);
        String[] labels = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            LinkPage.Item item = items[i];
            String path = item.url.pathSegments().get(item.url.pathSegments().size() - 1);
            labels[i] = item.label() + " " + (i + 1) + " · " + item.url.host()
                    + (path.isEmpty() ? "" : "\n" + (path.length() > 64 ? path.substring(0, 63) + "…" : path));
        }
        new android.app.AlertDialog.Builder(this).setTitle("选择影音").setItems(labels, (d, w) -> showActions(items[w]))
                .setNegativeButton("关闭", null).show();
    }

    private void showActions(LinkPage.Item item) {
        if (item.stream()) {
            new android.app.AlertDialog.Builder(this).setTitle("分段流媒体")
                    .setMessage("这是播放列表，不能直接保存为视频或音频文件。")
                    .setPositiveButton("复制链接", (d, w) -> copy(item.label(), item.url.toString()))
                    .setNegativeButton("关闭", null).show();
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle(item.label())
                .setItems(item.video() ? new String[]{"下载视频", "提取视频音轨", "复制链接"} : new String[]{"下载音频", "复制链接"},
                        (d, w) -> {
                            if (w == 0 || w == 1 && item.video()) prepare(item, w == 1, null, null);
                            else copy(item.label(), item.url.toString());
                        }).setNegativeButton("关闭", null).show();
    }

    private void prepare(LinkPage.Item item, boolean audio, String text, String textName) {
        LinkPage page = result;
        String ua = userAgent;
        work(text != null ? "正在准备文字…" : audio ? "正在下载视频并提取音轨…" : "正在下载" + item.label() + "…", () -> {
            File file = MediaIo.scratch(this, ".link");
            File output = file;
            boolean ready = false;
            try {
                final String mime, name;
                if (text != null) {
                    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
                    mime = "text/plain";
                    name = textName;
                } else {
                    LinkPage.Item saved = transfer.download(item, page.url, ua, file);
                    if (audio) {
                        output = MediaIo.scratch(this, ".link");
                        try { VideoTool.remuxAudio(this, Uri.fromFile(file), output); }
                        catch (Exception ex) { throw new IOException("无法导出音轨：原视频没有音轨，或手机不支持该音轨格式。", ex); }
                        mime = "audio/mp4";
                        name = fileName(page.title, "视频音轨", "m4a");
                    } else {
                        mime = saved.mime;
                        name = fileName(page.title, saved.label(), saved.extension());
                    }
                }
                File complete = output;
                runOnUiThread(() -> chooseFile(complete, name, mime));
                ready = true;
            } finally {
                if (file != output || !ready) file.delete();
                if (file != output && !ready) output.delete();
            }
        });
    }

    private void chooseFile(File file, String name, String mime) {
        if (isFinishing() || isDestroyed()) { file.delete(); return; }
        prepared = file;
        status.setText("请选择保存位置");
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, name), SAVE);
        } catch (RuntimeException ex) {
            prepared = null;
            file.delete();
            idle("无法打开系统文件选择器。");
        }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != SAVE || prepared == null) return;
        File file = prepared;
        prepared = null;
        Uri uri = data == null ? null : data.getData();
        if (code != RESULT_OK || uri == null || !"content".equals(uri.getScheme())) {
            file.delete();
            idle("已取消保存。");
            return;
        }
        status.setText("正在写入文件…");
        workers.execute(() -> {
            try {
                MediaIo.publish(this, uri, file);
                runOnUiThread(() -> idle("已保存。"));
            } catch (Exception ex) { runOnUiThread(() -> idle("写入失败，请换个位置再试。")); }
            finally { file.delete(); }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (prepared != null) state.putString("prepared", prepared.getName());
    }

    private void work(String message, Work action) {
        if (busy) return;
        busy = true;
        status.setText(message);
        render();
        workers.execute(() -> {
            try { action.run(); }
            catch (Exception | LinkageError e) {
                String messageText = e.getMessage();
                if (messageText == null || messageText.contains("://") || messageText.length() > 130) messageText = "请检查网络或重试";
                String failure = "未完成：" + messageText;
                runOnUiThread(() -> idle(failure));
            }
        });
    }

    private void idle(String message) {
        if (isDestroyed() || isFinishing()) return;
        busy = false;
        status.setText(message);
        render();
    }

    private void copy(String label, String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private static String fileName(String title, String fallback, String ext) {
        String name = title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", " ").trim();
        if (name.length() > 60) name = name.substring(0, 60).trim();
        return (TextUtils.isEmpty(name) ? fallback : name) + "." + ext;
    }

    private void closePreview() {
        if (web == null) return;
        WebView old = web;
        web = null;
        preview.removeView(old);
        old.stopLoading();
        old.destroy();
        synchronized (observed) { observed.clear(); }
    }

    @Override protected void onDestroy() {
        transfer.cancel();
        closePreview();
        if (prepared != null && isFinishing()) prepared.delete();
        super.onDestroy();
    }
}
