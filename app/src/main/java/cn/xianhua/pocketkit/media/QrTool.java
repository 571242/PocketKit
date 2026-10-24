package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 020 二维码工具，含 D04 增强：条码识别与 Wi-Fi 二维码生成。
 *
 * <p>Two directions in one screen. Generation uses ZXing's encoder, so it works with no camera,
 * no network and no Google SDK. Recognition uses ML Kit's bundled barcode model, which runs
 * on-device; the SDK is closed-source and may collect diagnostic data, which the screen states
 * plainly rather than claiming the app never talks to anyone.
 */
public final class QrTool implements Tool {

    private final Host host;

    public QrTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        host.note("生成二维码 / 条码，或识别图片里的码。生成和识别都在本机完成，"
                + "可能收集诊断与性能数据。");

        // ---------------- 生成 ----------------
        host.note("── 生成二维码 ──");
        final Spinner kind = host.select("类型",
                "文本 / 网址", "Wi-Fi 网络", "电话号码", "短信", "邮件", "联系人 vCard");
        final EditText text = host.field("内容", "网址、文本或任意内容");
        final EditText wifiSsid = host.field("Wi-Fi 名称 SSID（仅 Wi-Fi 类型）", "网络名称");
        final EditText wifiPass = host.field("Wi-Fi 密码（仅 Wi-Fi 类型）", "密码，开放网络留空");
        final Spinner wifiAuth = host.select("Wi-Fi 加密方式", "WPA/WPA2", "WEP", "无密码");
        final Spinner size = host.select("输出边长", "512", "768", "1024");
        host.note("Wi-Fi 二维码的密码含 ; , : \" \\ 时会自动转义，否则扫码会读错。");

        host.button("生成并保存", () -> {
            String payload;
            try {
                payload = buildPayload(kind.getSelectedItemPosition(),
                        text.getText().toString(),
                        wifiSsid.getText().toString(),
                        wifiPass.getText().toString(),
                        wifiAuth.getSelectedItemPosition());
            } catch (IllegalArgumentException ex) {
                host.show("未完成：" + ex.getMessage());
                return;
            }
            int edge = ImageCompressTool.parsePositive((String) size.getSelectedItem(), 512);
            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    BitMatrix matrix = encode(payload, edge);
                    cancel.check();
                    Bitmap bitmap = toBitmap(matrix);
                    byte[] png = ImageIo.encode(bitmap, ImgSizing.Format.PNG, 100);
                    bitmap.recycle();
                    prepared = MediaIo.scratch(host.activity(), ".png");
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(prepared)) {
                        os.write(png);
                    }
                    String name = "qrcode-" + System.currentTimeMillis() + ".png";
                    String summary = "已生成 " + edge + "×" + edge + " 二维码（PNG，无压缩损失）。\n"
                            + "编码内容：" + preview(payload, 300);
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary);
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

        // ---------------- 识别 ----------------
        host.note("── 识别图片中的码 ──");
        final Uri[] source = {null};
        host.button("选择图片识别", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「开始识别」。");
        }));

        host.button("开始识别", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片");
                return;
            }
            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                Bitmap bitmap = null;
                BarcodeScanner scanner = null;
                try {
                    bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, cancel);
                    InputImage image = InputImage.fromBitmap(bitmap, 0);
                    BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                            .build();
                    scanner = BarcodeScanning.getClient(options);
                    List<Barcode> codes = com.google.android.gms.tasks.Tasks.await(scanner.process(image));
                    cancel.check();

                    String report;
                    if (codes.isEmpty()) {
                        report = "没有识别到二维码或条码。\n可能原因：码太小、图片模糊、反光，或不是标准码。"
                                + "可以换一张更清晰、码占画面更大的图片重试。";
                    } else {
                        report = render(codes);
                    }
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(report);
                    });
                } catch (Cancel.Stop stopped) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.show("已取消。");
                    });
                } catch (Exception ex) {
                    host.post(() -> {
                        host.setRunning(false);
                        host.fail(ex);
                    });
                } finally {
                    // Cancelling or an await() failure used to skip both releases, leaking a
                    // full-size Bitmap plus one live ML Kit detector per attempt.
                    if (scanner != null) {
                        scanner.close();
                    }
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            });
        });
    }

    /** Builds the payload for the selected type, throwing a message the user can act on. */
    static String buildPayload(int kind, String text, String ssid, String password, int authIndex) {
        String raw = text == null ? "" : text.trim();
        switch (kind) {
            case 1:
                WifiQr.Auth auth;
                switch (authIndex) {
                    case 1: auth = WifiQr.Auth.WEP; break;
                    case 2: auth = WifiQr.Auth.NOPASS; break;
                    default: auth = WifiQr.Auth.WPA; break;
                }
                String pass = password == null ? "" : password;
                if (auth == WifiQr.Auth.NOPASS && !pass.trim().isEmpty()) {
                    throw new IllegalArgumentException("选了「无密码」却又填了密码，二者只能取一");
                }
                return WifiQr.payload(ssid == null ? "" : ssid.trim(), pass, auth, false);
            case 2:
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("请填写电话号码");
                }
                return "tel:" + raw;
            case 3:
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("请填写短信接收号码");
                }
                return "SMSTO:" + raw;
            case 4:
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("请填写邮件地址");
                }
                return "mailto:" + raw;
            case 5:
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("请填写联系人姓名或完整 vCard 内容");
                }
                // A bare name still produces a valid contact; a full vCard is passed through.
                return raw.startsWith("BEGIN:VCARD") ? raw : "BEGIN:VCARD\nVERSION:3.0\nFN:" + raw + "\nEND:VCARD";
            default:
                if (raw.isEmpty()) {
                    throw new IllegalArgumentException("请填写要编码的内容");
                }
                return raw;
        }
    }

    /** Renders any payload as a square QR matrix; throws when the content does not fit. */
    static BitMatrix encode(String payload, int edge) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        // QR has a hard capacity; a long payload must fail loudly instead of producing a blank code.
        hints.put(EncodeHintType.ERROR_CORRECTION,
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
        try {
            return new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, edge, edge, hints);
        } catch (com.google.zxing.WriterException e) {
            throw new Exception("内容太长，放不进二维码（约 2000 字节上限）。请缩短内容，"
                    + "或改用短链接后再生成。原始信息：" + e.getMessage());
        }
    }

    static Bitmap toBitmap(BitMatrix matrix) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }

    /** Human-readable result for each detected code, with Wi-Fi codes decoded into fields. */
    static String render(List<Barcode> codes) {
        StringBuilder sb = new StringBuilder();
        sb.append("识别到 ").append(codes.size()).append(" 个码：\n");
        List<String> kinds = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            Barcode code = codes.get(i);
            String type = typeName(code.getFormat());
            kinds.add(type);
            sb.append('\n').append(i + 1).append(". ").append(type).append('\n');
            String value = code.getRawValue();
            if (value == null || value.trim().isEmpty()) {
                sb.append("   （这个码没有可读文本内容，可能是二进制数据）\n");
            } else if (value.regionMatches(true, 0, "WIFI:", 0, 5)) {
                WifiQr.Parsed wifi = WifiQr.parse(value);
                if (wifi.isWifi()) {
                    sb.append("   网络名称：").append(wifi.ssid).append('\n');
                    sb.append("   加密方式：").append(WifiQr.label(wifi.auth)).append('\n');
                    if (wifi.auth != WifiQr.Auth.NOPASS) {
                        sb.append("   密码：").append(wifi.password).append('\n');
                    }
                } else {
                    sb.append("   内容：").append(value).append('\n');
                }
            } else {
                sb.append("   内容：").append(value).append('\n');
            }
            if (code.getUrl() != null && !code.getUrl().getTitle().isEmpty()) {
                sb.append("   标题：").append(code.getUrl().getTitle()).append('\n');
            }
        }
        sb.append("\n类型汇总：").append(String.join("、", kinds));
        return sb.toString();
    }

    static String typeName(int format) {
        switch (format) {
            case Barcode.FORMAT_QR_CODE: return "二维码 QR";
            case Barcode.FORMAT_AZTEC: return "Aztec 码";
            case Barcode.FORMAT_DATA_MATRIX: return "Data Matrix";
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_EAN_13: return "EAN-13 商品条码";
            case Barcode.FORMAT_EAN_8: return "EAN-8 商品条码";
            case Barcode.FORMAT_UPC_A: return "UPC-A 商品条码";
            case Barcode.FORMAT_UPC_E: return "UPC-E 商品条码";
            case Barcode.FORMAT_CODE_128: return "Code 128";
            case Barcode.FORMAT_CODE_39: return "Code 39";
            case Barcode.FORMAT_CODE_93: return "Code 93";
            case Barcode.FORMAT_ITF: return "ITF 交叉二五码";
            case Barcode.FORMAT_CODABAR: return "Codabar";
            default: return "条码（格式 " + format + "）";
        }
    }

    static String preview(String payload, int max) {
        String one = payload.replace('\n', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
