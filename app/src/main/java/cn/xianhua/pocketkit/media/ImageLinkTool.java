package cn.xianhua.pocketkit.media;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 010 图片转链接：把图片上传到公开图床，得到可分享的 URL。
 *
 * <p><b>This is the one tool in the module that sends a file off the device.</b> Two safeguards are
 * built in and are not optional:
 * <ol>
 *   <li>Nothing is uploaded until the user reads a dialog naming the exact service, what is sent,
 *       and what it means (public URL, third-party retention, no reliable delete).
 *   <li>The upload then runs only in the dialog's confirmed callback. There is no automatic retry
 *       and no background upload.</li>
 * </ol>
 *
 * <p>Service endpoints are the ones published by the operators themselves:
 * <ul>
 *   <li>0x0.st — {@code POST https://0x0.st}, multipart field {@code file}. Returns the URL as text.
 *       Supports a {@code secret} field for a hard-to-guess URL and an {@code expires} field.</li>
 *   <li>catbox.moe — {@code POST https://catbox.moe/user/api.php} with {@code reqtype=fileupload}
 *       and {@code fileToUpload}. Anonymous uploads omit {@code userhash}.</li>
 * </ul>
 * Both were verified against the operators' own documentation, not inferred.
 */
public final class ImageLinkTool implements Tool {

    private final Host host;

    public ImageLinkTool(Host host) {
        this.host = host;
    }

    private static final String SERVICE_0X0 = "0x0.st";
    private static final String SERVICE_CATBOX = "catbox.moe";

    @Override public void build() {
        host.note("把图片上传到公开图床，得到一个可以直接粘贴的图片链接。");
        host.note("注意：这是本应用中唯一会把文件发送到外部服务器的功能。上传后图片在互联网上公开可访问，"
                + "任何人都可能看到；文件由第三方保管，本应用无法保证删除，也无法控制其保留时间。"
                + "不要在图片里包含身份证、银行卡、住址、他人隐私或未公开的商业信息。");

        final Uri[] source = {null};
        final android.widget.TextView info = host.note("尚未选择图片");
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            long size = MediaIo.size(host.activity(), uri);
            info.setText("已选择：" + MediaIo.displayName(host.activity(), uri)
                    + (size > 0 ? "（" + cn.xianhua.pocketkit.core.SizeFmt.bytes(size) + "）" : "")
                    + "\n可以点「上传并获取链接」了。");
        }));

        final Spinner service = host.select("上传服务",
                "0x0.st（临时，可设有效期）", "catbox.moe（长期）");
        final Spinner retain = host.select("保留时间（仅 0x0.st 支持）",
                "72 小时（推荐，最短）", "30 天", "365 天", "由服务决定");
        final Spinner secrecy = host.select("链接形式（仅 0x0.st 支持）",
                "短链接（易猜，推荐分享）", "长链接（难猜，更私密）");
        final EditText outName = host.field("保存的链接文本文件名（可留空）", "例如 link.txt");

        host.button("上传并获取链接", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            final boolean useCatbox = service.getSelectedItemPosition() == 1;
            final long expiresHours = new long[]{72, 720, 8760, -1}[retain.getSelectedItemPosition()];
            final boolean wantSecret = secrecy.getSelectedItemPosition() == 1;

            String serviceName = useCatbox ? SERVICE_CATBOX : SERVICE_0X0;
            String description;
            if (useCatbox) {
                description = "将要把这张图片上传到 " + SERVICE_CATBOX + "（第三方公开文件托管服务，"
                        + "由 Catbox LLC 运营，位于境外）。\n\n"
                        + "发送内容：图片文件的完整内容，以及本应用的 User-Agent 标识。\n"
                        + "不会发送：你的文件名以外的任何信息、设备标识、通讯录或位置。";
            } else {
                description = "将要把这张图片上传到 " + SERVICE_0X0 + "（第三方临时文件托管服务，"
                        + "由境外个人运营）。\n\n"
                        + "发送内容：图片文件的完整内容、本应用的 User-Agent 标识。"
                        + "该服务会为审核目的记录上传者 IP 地址与 User-Agent。\n"
                        + "不会发送：你的设备标识、通讯录或位置。";
            }
            String consequence = "上传后：\n"
                    + "· 图片会获得一个公开链接，任何拿到链接的人都能查看与下载；\n"
                    + "· 文件存放在第三方服务器上，本应用无法删除，也无法保证何时被清理；\n"
                    + (useCatbox
                    ? "· catbox.moe 上匿名上传的文件长期保留。\n"
                    : "· 0x0.st 会在有效期（本次选择：" + (expiresHours < 0 ? "由服务决定" : expiresHours + " 小时")
                    + "）后清理文件；到期时间由服务控制。\n")
                    + "· 图片本身仍在你手机上；本应用不会删除或修改原图。";

            AlertDialog dialog = Uploader.consentDialog(host.activity(),
                    "确认上传到 " + serviceName + "？", description, consequence,
                    () -> doUpload(uri, useCatbox, expiresHours, wantSecret, outName.getText().toString()));
            dialog.show();
        });

        host.note("上传成功后可以保存链接为 TXT，也可以直接复制。链接一旦公开，请把它当作公开信息对待。");
    }

    private void doUpload(Uri uri, boolean useCatbox, long expiresHours, boolean wantSecret, String fileName) {
        final Cancel cancel = new Cancel();
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            File temp = null;
            try {
                // Copy the picked image into the cache: the upload must send exact bytes, and a
                // content:// stream cannot be re-read for a retry or a length probe.
                temp = MediaIo.scratch(host.activity(), ".upload");
                String displayName = MediaIo.displayName(host.activity(), uri);
                try (InputStream in = MediaIo.openInput(host.activity(), uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    MediaIo.copy(in, out, cancel, MediaIo.MAX_BYTES, null);
                }
                long bytes = temp.length();
                cancel.check();

                String safeName = sanitizeName(displayName);
                String mime = ImageConvertTool.mimeOf(safeName);

                Uploader.Response response;
                String serviceLabel;
                if (useCatbox) {
                    List<Uploader.Part> parts = new ArrayList<>();
                    parts.add(Uploader.Part.field("reqtype", "fileupload"));
                    parts.add(Uploader.Part.file("fileToUpload", temp, safeName, mime));
                    response = Uploader.postMultipart("https://catbox.moe/user/api.php", parts, cancel);
                    serviceLabel = SERVICE_CATBOX;
                } else {
                    List<Uploader.Part> parts = new ArrayList<>();
                    parts.add(Uploader.Part.file("file", temp, safeName, mime));
                    if (wantSecret) {
                        // Presence of the field is what enables the secret form; the value is ignored.
                        parts.add(Uploader.Part.field("secret", ""));
                    }
                    if (expiresHours > 0) {
                        parts.add(Uploader.Part.field("expires", String.valueOf(expiresHours)));
                    }
                    response = Uploader.postMultipart("https://0x0.st", parts, cancel);
                    serviceLabel = SERVICE_0X0;
                }
                cancel.check();

                if (!response.ok()) {
                    throw new Exception("上传被服务拒绝（HTTP " + response.status + "）。"
                            + (response.body.trim().isEmpty() ? "服务没有给出说明。" :
                            "服务返回：" + preview(response.body))
                            + "\n可能原因：服务暂时不可用、被限流、或图片类型/大小不被接受。可稍后重试或换另一个服务。");
                }
                String link = extractLink(response.body);
                if (link == null) {
                    throw new Exception("上传返回的内容不是链接，因此没有把它当成成功。服务返回："
                            + preview(response.body) + "\n你可以稍后再试，或换另一个上传服务。");
                }

                String summary = "上传成功。\n服务：" + serviceLabel + "\n文件：" + safeName
                        + "（" + cn.xianhua.pocketkit.core.SizeFmt.bytes(bytes) + "）\n\n链接：\n" + link
                        + "\n\n提醒：这个链接是公开的，任何人拿到都能访问。"
                        + (serviceLabel.equals(SERVICE_0X0)
                        ? "\n0x0.st 会在有效期后自动清理；到期时间由服务控制。"
                        : "\ncatbox.moe 上匿名文件长期保留，本应用无法删除。");
                final String finalLink = link;
                final String finalSummary = summary;
                final String suggested = fileName == null || fileName.trim().isEmpty()
                        ? "link.txt" : fileName.trim();
                host.post(() -> {
                    host.setRunning(false);
                    host.show(finalSummary + "\n\n可把链接保存为 TXT 文件。");
                    host.createDocument(suggested.endsWith(".txt") ? suggested : suggested + ".txt",
                            "text/plain", target -> host.background(() -> {
                                try {
                                    File text = MediaIo.scratch(host.activity(), ".txt");
                                    try (FileOutputStream os = new FileOutputStream(text)) {
                                        os.write((finalLink + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    }
                                    MediaIo.publish(host.activity(), target, text);
                                    text.delete();
                                    host.post(() -> host.show(finalSummary + "\n\n已保存链接文件。"));
                                } catch (Exception ex) {
                                    host.post(() -> host.fail(ex));
                                }
                            }));
                });
            } catch (Cancel.Stop stopped) {
                host.post(() -> {
                    host.setRunning(false);
                    host.show("已取消。若请求已经发出，图片可能仍已上传到服务方；本应用无法代为删除。");
                });
            } catch (Exception ex) {
                host.post(() -> {
                    host.setRunning(false);
                    host.show("未完成：上传没有成功。\n" + describe(ex)
                            + "\n\n图片没有离开你的手机（或请求未完成）。可以稍后重试。");
                });
            } finally {
                if (temp != null && temp.exists()) {
                    temp.delete();
                }
            }
        });
    }

    /** Turns a network failure into a sentence a person can act on. */
    public static String describe(Exception ex) {
        String message = ex.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }
        String name = ex.getClass().getSimpleName();
        if (name.contains("UnknownHost")) {
            return "无法解析服务地址（可能是断网或 DNS 问题）。请检查网络后重试。";
        }
        if (name.contains("SocketTimeout")) {
            return "连接服务超时。可能是网络慢或服务繁忙，请稍后重试。";
        }
        if (name.contains("SSL") || name.contains("Certificate")) {
            return "HTTPS 握手失败（证书或系统时间问题）。请检查系统时间是否正确。";
        }
        if (name.contains("IOException")) {
            return "网络传输中断。请检查网络连接后重试。";
        }
        return "错误类型：" + name;
    }

    /** Services return the URL as plain text; anything else is treated as a failure, not a link. */
    public static String extractLink(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Take the first whitespace-delimited token and require it to be an http(s) URL.
        String first = trimmed.split("\\s+")[0];
        if (first.startsWith("https://") || first.startsWith("http://")) {
            return first;
        }
        return null;
    }

    /** Strips path separators and control characters so a filename cannot escape anywhere. */
    public static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "image.jpg";
        }
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        cleaned = cleaned.replaceAll("^[.]+", "");
        if (cleaned.isEmpty()) {
            return "image.jpg";
        }
        return cleaned.length() > 100 ? cleaned.substring(cleaned.length() - 100) : cleaned;
    }

    private static String preview(String body) {
        String one = body == null ? "" : body.replace('\n', ' ').trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}
