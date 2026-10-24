package cn.xianhua.pocketkit.media;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 022 以图搜图。
 *
 * <p>Two routes, and the default one sends nothing:
 *
 * <ol>
 *   <li><b>交给别的应用</b> (default): the image is handed to whatever the user has installed via the
 *       system share sheet. The user picks Google Lens, a search app, or anything else, and this app
 *       never uploads anything. This is the honest implementation of "search by image" on Android:
 *       the reverse-image engines expect a URL, and every one of them changes its upload page often
 *       enough that scraping their results from inside an app breaks within weeks.</li>
 *   <li><b>上传后打开搜索页</b>: uploads to a public host (with the same mandatory consent dialog as
 *       010), then opens the engine's by-URL search page in the browser. This needs the file to be
 *       publicly reachable, which the dialog says plainly.</li>
 * </ol>
 *
 * <p>This tool does <b>not</b> parse or display search results. Doing so would mean scraping pages
 * that forbid it and would silently break; the browser shows the real results instead.
 */
public final class ImageSearchTool implements Tool {

    private final Host host;

    public ImageSearchTool(Host host) {
        this.host = host;
    }

    /** Engines with a documented by-URL search entry point. Google Lens and TinEye are excluded. */
    private static final String[] ENGINE_NAMES = {"Yandex", "Bing"};
    private static final String[] ENGINE_URLS = {
            "https://yandex.com/images/search?rpt=imageview&url=",
            "https://www.bing.com/images/searchbyimage?cbir=sbi&imgurl=",
    };

    @Override public void build() {
        host.note("以图搜图有两种做法，默认那种不会上传任何东西。");

        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri)
                    + "\n可以用下面的「分享到其他应用」直接搜，或先上传再在浏览器里搜。");
        }));

        host.note("── 方式一：分享给其他应用（不上传，推荐）──");
        host.button("分享图片到其他应用", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/*");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // A chooser is required so the user sees the real target list and picks the engine.
                host.activity().startActivity(Intent.createChooser(send, "选择用来搜图的应用"));
                host.show("已打开系统分享面板。\n\n"
                        + "选 Google Lens、Yandex、搜狗/百度等任意支持搜图的应用即可。"
                        + "这种方式下本应用不会上传图片，由你选择的应用自行处理。");
            } catch (Exception ex) {
                host.fail(new Exception("没有找到可以接收图片的应用。请先安装一个支持以图搜图的应用。", ex));
            }
        });

        host.note("── 方式二：上传到公开图床，再用浏览器搜（会上传）──");
        host.note("这个方式会把图片上传到第三方公开图床并生成公开链接，然后交给搜索引擎按链接搜图。"
                + "图片会公开可访问，且第三方会看到你的 IP 与图片内容。");
        final Spinner engine = host.select("搜索引擎", ENGINE_NAMES);
        final Spinner hoster = host.select("图床", "0x0.st（临时，72 小时）", "catbox.moe（长期）");

        host.button("上传并打开搜索页面", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            final boolean useCatbox = hoster.getSelectedItemPosition() == 1;
            final String engineName = ENGINE_NAMES[engine.getSelectedItemPosition()];
            final String engineBase = ENGINE_URLS[engine.getSelectedItemPosition()];
            final String hosterName = useCatbox ? "catbox.moe" : "0x0.st";

            String description = "这个操作分两步，两步都会离开你的设备：\n\n"
                    + "第 1 步：把图片上传到 " + hosterName + "（第三方公开文件托管服务），"
                    + "得到一个公开链接。\n"
                    + "第 2 步：用浏览器打开 " + engineName + " 的搜图页面，并把该链接提交给 "
                    + engineName + "。\n\n"
                    + "发送内容：图片的完整内容（发送给 " + hosterName + "），"
                    + "以及图片链接（发送给 " + engineName + "）。\n"
                    + "还会发送：本应用的 User-Agent 标识。";
            String consequence = "这意味着：\n"
                    + "· 图片会出现在公开可访问的地址上，任何拿到链接的人都能查看；\n"
                    + "· 图片会被 " + engineName + " 抓取并用于该次搜图；\n"
                    + "· 文件存放在第三方服务器，本应用无法删除，也无法控制其保留时间；\n"
                    + (useCatbox ? "· catbox.moe 上匿名文件长期保留。\n"
                    : "· 0x0.st 会在 72 小时后清理，但到期时间由服务控制。\n")
                    + "· 图片原文件仍在你手机上。\n\n"
                    + "如果图片含隐私内容（证件、住址、他人照片、未公开资料），请不要使用这个方式，"
                    + "改用上面的「分享到其他应用」。";

            AlertDialog dialog = Uploader.consentDialog(host.activity(),
                    "确认上传到 " + hosterName + " 并交给 " + engineName + "？",
                    description, consequence,
                    () -> doUploadAndSearch(uri, useCatbox, engineName, engineBase, hosterName));
            dialog.show();
        });

        host.note("说明：本工具不抓取也不显示搜索结果，而是在浏览器里打开真实搜索页——"
                + "搜索结果由搜索引擎展示。直接解析搜索页既违反其条款，也会随页面改版随时失效。");
        host.note("Google Lens 与 TinEye 未列入：它们的按链接搜图入口需要浏览器会话与 cookie，"
                + "从应用直接构造 URL 并不可靠，因此不提供以免误导。要用它们请走「分享到其他应用」。");
    }

    private void doUploadAndSearch(Uri uri, boolean useCatbox, String engineName,
                                   String engineBase, String hosterName) {
        final Cancel cancel = new Cancel();
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            File temp = null;
            try {
                temp = MediaIo.scratch(host.activity(), ".search");
                String displayName = ImageLinkTool.sanitizeName(MediaIo.displayName(host.activity(), uri));
                try (InputStream in = MediaIo.openInput(host.activity(), uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    MediaIo.copy(in, out, cancel, MediaIo.MAX_BYTES, null);
                }
                cancel.check();
                String mime = ImageConvertTool.mimeOf(displayName);

                Uploader.Response response;
                if (useCatbox) {
                    List<Uploader.Part> parts = new ArrayList<>();
                    parts.add(Uploader.Part.field("reqtype", "fileupload"));
                    parts.add(Uploader.Part.file("fileToUpload", temp, displayName, mime));
                    response = Uploader.postMultipart("https://catbox.moe/user/api.php", parts, cancel);
                } else {
                    List<Uploader.Part> parts = new ArrayList<>();
                    parts.add(Uploader.Part.file("file", temp, displayName, mime));
                    parts.add(Uploader.Part.field("expires", "72"));
                    response = Uploader.postMultipart("https://0x0.st", parts, cancel);
                }
                cancel.check();
                if (!response.ok()) {
                    throw new Exception("上传到 " + hosterName + " 失败（HTTP " + response.status + "）。"
                            + "搜图需要图片先有一个公开链接，所以本次没有打开搜索页。"
                            + "可稍后重试，或改用「分享到其他应用」。");
                }
                String imageUrl = ImageLinkTool.extractLink(response.body);
                if (imageUrl == null) {
                    throw new Exception("图床返回的内容不是链接，无法继续搜图。"
                            + "可改用「分享到其他应用」。");
                }

                String searchUrl = engineBase + java.net.URLEncoder.encode(imageUrl, "UTF-8");
                Uploader.requireHttps(searchUrl);
                cancel.check();

                final String finalImageUrl = imageUrl;
                final String finalSearchUrl = searchUrl;
                host.post(() -> {
                    host.setRunning(false);
                    try {
                        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(finalSearchUrl));
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        host.activity().startActivity(open);
                        host.show("已上传并打开 " + engineName + " 的搜图页面。\n\n"
                                + "图片链接：" + finalImageUrl + "\n"
                                + "搜索地址：" + finalSearchUrl + "\n\n"
                                + "提醒：图片链接是公开的。搜索结果由 " + engineName + " 在浏览器中展示，"
                                + "本应用无法查看或删除已上传的图片。");
                    } catch (Exception ex) {
                        host.show("图片已上传成功，但没有找到可以打开网页的应用。\n\n"
                                + "图片链接：" + finalImageUrl + "\n"
                                + "手动打开这个地址即可搜图：" + finalSearchUrl + "\n\n"
                                + "提醒：该链接是公开的。");
                    }
                });
            } catch (Cancel.Stop stopped) {
                host.post(() -> {
                    host.setRunning(false);
                    host.show("已取消。若上传请求已经发出，图片可能仍已在图床上；本应用无法代为删除。");
                });
            } catch (Exception ex) {
                host.post(() -> {
                    host.setRunning(false);
                    host.show("未完成：没有走完搜图流程。\n" + ImageLinkTool.describe(ex)
                            + "\n\n可改用「分享到其他应用」，那个方式不需要上传。");
                });
            } finally {
                if (temp != null && temp.exists()) {
                    temp.delete();
                }
            }
        });
    }
}
