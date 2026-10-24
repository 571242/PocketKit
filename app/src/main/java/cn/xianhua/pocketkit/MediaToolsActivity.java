package cn.xianhua.pocketkit;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import cn.xianhua.pocketkit.media.Host;
import cn.xianhua.pocketkit.media.Tool;

/**
 * Entry point for every image / document / video tool (mine to own per the module contract).
 *
 * <p>Responsibilities are narrow on purpose: it owns the SAF result plumbing and the cancel
 * affordance, then hands the body to whichever {@link Tool} matches {@code tool_id}. The tools
 * themselves contain all the behaviour, which is what makes them testable and reviewable one at a
 * time.
 *
 * <p>Contract: {@link #supports(String)} returns true only for ids with a real implementation, and
 * {@link #limitation(String)} explains every other id and every known restriction.
 */
public class MediaToolsActivity extends BaseToolActivity implements Host {

    private static final int REQUEST_OPEN = 4201;
    private static final int REQUEST_CREATE = 4202;
    private static final int REQUEST_OPEN_MULTI = 4203;

    private Host.UriCallback pendingOpen;
    private Host.UriCallback pendingCreate;
    private Host.MultiUriCallback pendingMulti;
    private Runnable canceller;
    private Button cancelButton;

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (java.util.Arrays.asList("020", "026", "027", "031", "034", "A04").contains(toolId)) {
            try { MlKitBootstrap.ensureInitialized(this); }
            catch (RuntimeException | LinkageError failure) { fail(failure); return; }
        }
        Tool tool = create(toolId);
        if (tool == null) {
            note("这个工具暂时不可用。");
            String reason = limitation(toolId);
            show(reason == null || reason.trim().isEmpty() ? "该功能暂时不可用。" : reason);
            return;
        }
        tool.build();
        // The cancel affordance goes last, directly above the reading pane, so it is next to the
        // progress text the user is watching rather than buried above the inputs.
        cancelButton = button("取消当前任务", () -> {
            Runnable current = canceller;
            if (current != null) {
                current.run();
                show("已请求取消，正在停止…");
            }
        });
        cancelButton.setVisibility(android.view.View.GONE);
    }

    /** Factory: the only place that knows which id maps to which screen. */
    Tool create(String id) {
        if (id == null) {
            return null;
        }
        switch (id) {
            case "011":
            case "023":
                return new cn.xianhua.pocketkit.media.ImageCompressTool(this);
            case "004":
                return new cn.xianhua.pocketkit.media.ColorPickerTool(this);
            case "007":
                return new cn.xianhua.pocketkit.media.GifTool(this);
            case "008":
                return new cn.xianhua.pocketkit.media.GradientImageTool(this);
            case "010":
                return new cn.xianhua.pocketkit.media.ImageLinkTool(this);
            case "022":
                return new cn.xianhua.pocketkit.media.ImageSearchTool(this);
            case "024":
                return new cn.xianhua.pocketkit.media.StickerTool(this);
            case "026":
                return new cn.xianhua.pocketkit.media.ImageLabelTool(this);
            case "027":
                return new cn.xianhua.pocketkit.media.FaceBlendTool(this);
            case "029":
            case "030":
            case "032":
                return new cn.xianhua.pocketkit.media.EnhanceTool(this, id);
            case "031":
                return new cn.xianhua.pocketkit.media.TableTool(this);
            case "033":
                return new cn.xianhua.pocketkit.media.CountTool(this);
            case "005":
                return new cn.xianhua.pocketkit.media.DrawTool(this);
            case "012":
                return new cn.xianhua.pocketkit.media.ExifTool(this);
            case "013":
            case "018":
            case "019":
            case "025":
                return new cn.xianhua.pocketkit.media.ImageLayoutTool(this, id);
            case "014":
            case "015":
                return new cn.xianhua.pocketkit.media.ImageConvertTool(this, id);
            case "020":
                return new cn.xianhua.pocketkit.media.QrTool(this);
            case "034":
                return new cn.xianhua.pocketkit.media.OcrTool(this);
            case "A01":
            case "A02":
                return new cn.xianhua.pocketkit.media.PdfTool(this);
            case "A03":
                return new cn.xianhua.pocketkit.media.ScanTool(this);
            case "A04":
                return new cn.xianhua.pocketkit.media.RedactTool(this);
            case "A08":
            case "080":
                // 080 shares the video screen; VideoTool picks the audio-extraction panel by tool_id.
                return new cn.xianhua.pocketkit.media.VideoTool(this);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------ id contract

    private static final String[] IMPLEMENTED = {
            "004", "005", "007", "008", "010", "011", "012", "013", "014", "015", "018", "019",
            "020", "022", "023", "024", "025", "026", "027", "029", "030", "031", "032", "033", "034",
            "080", "A01", "A02", "A03", "A04", "A08"};

    public static boolean supports(String id) {
        if (id == null) {
            return false;
        }
        for (String candidate : IMPLEMENTED) {
            if (candidate.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Reasons for the ids a user can see but that are not ready, and limits of the ready ones. */
    public static String limitation(String id) {
        if (id == null) {
            return "未知入口。";
        }
        switch (id) {
            case "011":
                return "按最长边像素、按总像素、按目标文件大小三种压缩；PNG 为无损，无法按目标大小压缩。";
            case "023":
                return "JPEG、PNG、WebP 格式转换与缩放。";
            case "012":
                return "查看 EXIF 与一键清除定位、设备、时间等隐私标签（保留方向标签以免照片转向）。";
            case "020":
                return "生成文本 / Wi-Fi / 电话 / 短信 / 邮件 / 名片二维码，识别图片中的二维码与一维条码。"
                        + "本机识别，可能收集诊断数据。";
            case "034":
                return "中文 / 英文离线 OCR 与导出 TXT（UTF-8 含 BOM）。竖排与手写体识别率低。"
                        + "模型随包内置，SDK 闭源。";
            case "A01":
                return "PDF 合并（多选）、按每 N 页或页码范围拆分、旋转指定页或全部页、按页码删除页面。"
                        + "结果另存为新文件，不改原文件。加密 PDF 需已解密（受密码保护的会明确报错）。";
            case "A02":
                return "多张图片合成 PDF（可按图片尺寸或 A4 纵向/横向排页）、PDF 按页导出 PNG。";
            case "A03":
                return "自动找边、透视校正、去阴影、对比度增强、灰度/黑白二值，输出 JPG 或 PNG；"
                        + "找边失败或去阴影不理想时可改用「手动指定四角」兜底（填四角百分比）。"
                        + "找边在合成测试图上四角误差约 2 像素，这是合成图的验证结果，不代表真实照片的精度；"
                        + "复杂背景、深阴影、纸张与背景亮度接近时会检出失败，界面会如实说明。";
            case "A04":
                return "文本脱敏（电话 / 邮箱 / 卡号，正则定位）与图片打码（对识别到的文字区域涂黑），"
                        + "人脸打码使用人脸检测。";
            case "A08":
                return "视频压缩（可选分辨率与码率，可只压一段；音轨原样复制不重编码）与按时间裁剪"
                        + "（整段不重编码，快速无损）。重编码依赖设备自带的 H.264 编码器，"
                        + "个别机型可能不支持所选分辨率（会明确报错）。";
            case "004":
                return "打开图片后点击取色，显示 HEX / RGB / HSL；也可输入坐标取色。"
                        + "点击位置会按显示矩阵逆变换回原图像素，缩放下不会取错。";
            case "005":
                return "手绘、调色、调笔粗、撤销一笔、清空、导出 PNG。笔画按矢量保存，撤销是真的移除。";
            case "007":
                return "多图合成 GIF（每帧单独量化、局部调色板、延迟与循环可设、尺寸不一可居中或裁满）"
                        + "与 GIF 逐帧真分解（还原透明、disposal 0/1/2/3、交错、局部调色板与每帧延迟；"
                        + "可选导出全部帧或仅第 1 帧并明确标注）。"
                        + "支持多帧 GIF 编码。"
                        + "GIF 仅 256 色，照片类内容会有色带（格式限制）。";
            case "008":
                return "自由尺寸渐变图（线性按角度 / 径向 / 扫描 / 四角对称），2~6 色自定义，"
                        + "可选叠加形状（同心圆 / 细网格 / 斜线 / 色块）与颗粒，输出 PNG 或 JPEG。"
                        + "本机 Canvas 绘制，不联网、不下载素材。";
            case "010":
                return "上传到 0x0.st 或 catbox.moe 获取公开图片链接，可设 0x0.st 的保留时长与"
                        + "难猜链接，链接可导出为 TXT。**这是会把文件发到外部服务器的功能**："
                        + "上传前必须读并确认逐次弹出的说明（服务名、发送内容、公开可见与无法删除的后果），"
                        + "确认后才发请求；仅 HTTPS；文件名会做防路径穿越处理。"
                        + "服务为第三方运营，其可用性与保留策略不由本应用控制。";
            case "013":
                return "纵向 / 横向 / 网格拼接，可设间距与底色；统一宽度或高度后等比缩放，不拉伸变形。";
            case "014":
                return "图片 → Base64（可选 data URI 前缀，结果存 TXT 以免界面卡顿）、Base64 → 图片"
                        + "（支持 data URI、URL-safe 字母表，写入前先校验确实是图片）。";
            case "015":
                return "文字水印，可调位置（四角 / 正中）、不透明度、颜色、字号（按图片宽度比例）、"
                        + "整图斜向平铺；白字自动加黑描边以免在浅色照片上看不见。水印烤进像素，不能分离。";
            case "018":
                return "切 3×3 九格，可选「先居中裁成正方形再切」，导出 9 个 PNG。";
            case "019":
                return "像素化（每格取平均色，横向格数可调，可选黑白 / 四阶灰度），放大时不做插值以保留方块边缘。";
            case "022":
                return "两种方式。默认「分享给其他应用」走系统分享面板交给 Google Lens 等应用，"
                        + "本应用不上传任何数据；另一种是上传到 0x0.st / catbox.moe 取得公开链接后，"
                        + "用浏览器打开 Yandex 或 Bing 的按链接搜图页（上传前有逐次确认说明）。"
                        + "本工具不抓取也不显示搜索结果——那既违反搜索引擎条款，也会随页面改版失效。"
                        + "Google Lens 与 TinEye 未列入：其按链接入口依赖浏览器会话，构造 URL 不可靠。";
            case "024":
                return "表情图制作，含轮廓白边（对透明区做边缘膨胀，贴着主体轮廓而非套方框）、"
                        + "底部/顶部文字带描边、圆形或圆角裁剪、方图居中裁切、透明/白/黑背景，"
                        + "PNG 或 JPEG 输出。JPEG 选透明背景会被拒绝并说明原因。";
            case "025":
                return "文字渲染成图片（4 种画布 / 5 档字号 / 3 套配色 / 居中和左对齐 / 可选标题）。"
                        + "中文按字断行、英文按空格断行；文字放不下时会明确报错而不是静默截断。";
            case "026":
                return "图片标签识别，给出英文标签、置信度和常见项中文对照。可能收集诊断数据。"
                        + "范围明确：只给大致类别，不给位置框、不计数、不认人、不读字；标签集约 400 类，"
                        + "不在其中则如实报告「没有达到置信度的标签」，不编造结果。";
            case "027":
                return "人脸检测与对齐，"
                        + "再通过羽化椭圆掩膜做交叉渐变。可调混合强度与羽化宽度，并可导出检测标注图。"
                        + "明确不是生成式换脸：不改变姿态与光照，也不会生成五官；两张脸角度差太大时会重影。";
            case "029":
                return "锐化，可调强度、半径和噪点保护，"
                        + "输出 PNG 或 JPEG。诚实说明：它提升已有边缘的对比度，不恢复已丢失的细节，"
                        + "也不能把模糊人脸变清楚——那需要超分模型，本工具不提供也不做假效果。";
            case "030":
                return "按倍数沿横向或纵向还原被线性拉伸的图片，"
                        + "内置 4:3↔16:9、1:1→16:9、3:2→16:9 常用比例，也可手动填倍数或按目标比例估算。"
                        + "只对「线性拉伸」有效：裁切、透视变形或内容被扭曲的情况无法修复，界面如实说明。"
                        + "输出尺寸有 4000 万像素上限。";
            case "031":
                return "离线 OCR 识别文字并按版面整理表格，"
                        + "可调列分隔敏感度，导出 TSV / CSV / 纯文本（UTF-8 含 BOM），"
                        + "导出前先给出预览。诚实说明：这是按版面的几何推断，不是理解表格语义，"
                        + "合并单元格、无空白分隔的相邻列、斜着拍的照片会不准。";
            case "032":
                return "高质量放大，支持 2/3/4/6 倍或指定长边像素，"
                        + "输出 PNG 或 JPEG，放大后总像素上限 6400 万（超限会明确拒并说明）。"
                        + "诚实说明：这是插值放大，像素变多、边缘变顺滑，但不增加真实细节，"
                        + "不是 AI 超分。";
            case "033":
                return "在对比明显的背景上数独立物体（药片、硬币、零件、圆点），"
                        + "自动区分明暗物体并忽略小噪点，可导出标注结果图与逐块面积明细表。"
                        + "诚实说明：这不是目标检测，不知道在数什么；物体互相接触会合并成一个、"
                        + "背景杂乱或反光时会数错。结果图上会标出每个被计数的物体供核对，"
                        + "并对「粘连」这种典型失败给出提示。";
            case "080":
                return "从视频无损提取音轨（不重新编码），"
                        + "输出格式跟随源音轨（AAC/M4A、MP3、Opus、Vorbis）；无音轨会明确告知。";
            default:
                return "该功能暂不可用。";
        }
    }

    // ------------------------------------------------------------ Host bridge

    @Override public Activity activity() {
        return this;
    }

    @Override public LinearLayout body() {
        return body;
    }

    @Override public int dp(float n) {
        return super.dp(n);
    }

    @Override public android.widget.TextView note(String text) {
        return super.note(text);
    }

    @Override public EditText field(String label, String hint) {
        return super.field(label, hint);
    }

    @Override public Spinner select(String label, String... choices) {
        return super.select(label, choices);
    }

    @Override public CheckBox checkbox(String label, boolean checkedByDefault) {
        super.note(label);
        android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setText("启用"); box.setTextColor(ink); box.setChecked(checkedByDefault); box.setMinHeight(dp(44));
        body.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    @Override public Button button(String label, Runnable action) {
        return super.button(label, action);
    }

    @Override public void show(String text) {
        super.show(text);
    }

    @Override public void fail(Throwable ex) {
        super.fail(ex);
    }

    @Override public void background(Work job) {
        super.background(job);
    }

    @Override public void post(Runnable action) {
        runOnUiThread(action);
    }

    @Override public void pickDocument(String mimeType, Host.UriCallback onResult) {
        pendingOpen = onResult;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType == null ? "*/*" : mimeType);
        // Read access is enough; the app never needs to write back into the picked document.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override public void pickDocuments(String mimeType, Host.MultiUriCallback onResult) {
        pendingMulti = onResult;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType == null ? "*/*" : mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_MULTI);
    }

    @Override public void createDocument(String suggestedName, String mimeType, Host.UriCallback onResult) {
        pendingCreate = onResult;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType == null ? "*/*" : mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CREATE);
    }

    @Override public void onCancel(Runnable c) {
        canceller = c;
    }

    @Override public void setRunning(boolean running) {
        if (cancelButton != null) {
            cancelButton.setVisibility(running ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        if (!running) {
            canceller = null;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data == null ? null : data.getData();
        if (requestCode == REQUEST_OPEN) {
            Host.UriCallback callback = pendingOpen;
            pendingOpen = null;
            if (callback == null) {
                return;
            }
            if (resultCode == RESULT_OK && uri != null) {
                callback.onResult(uri);
            } else {
                show("已取消选择，未选中任何文件。");
            }
        } else if (requestCode == REQUEST_CREATE) {
            Host.UriCallback callback = pendingCreate;
            pendingCreate = null;
            if (callback == null) {
                return;
            }
            if (resultCode == RESULT_OK && uri != null) {
                callback.onResult(uri);
            } else {
                // The user declined the destination: the prepared result stays unsaved, and saying so
                // is better than quietly dropping it.
                show("已取消保存位置选择，结果未写出。");
            }
        } else if (requestCode == REQUEST_OPEN_MULTI) {
            Host.MultiUriCallback callback = pendingMulti;
            pendingMulti = null;
            if (callback == null) {
                return;
            }
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                // A multi-select returns a ClipData; a single tap still arrives in getData().
                android.content.ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri item = clip.getItemAt(i).getUri();
                        if (item != null) {
                            uris.add(item);
                        }
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            if (uris.isEmpty()) {
                show("已取消选择，未选中任何文件。");
            } else {
                callback.onResult(uris);
            }
        }
    }

    @Override protected void onDestroy() {
        // Cancel any in-flight job: the worker is shut down by the base class, and a cancel request
        // lets its loops stop before touching a half-written cache file.
        Runnable current = canceller;
        if (current != null) {
            current.run();
        }
        super.onDestroy();
    }
}
