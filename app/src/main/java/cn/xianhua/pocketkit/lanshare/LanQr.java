package cn.xianhua.pocketkit.lanshare;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/**
 * 地址二维码。用项目里已有的 ZXing 生成，不联网、不依赖相机。
 *
 * 为什么自己画：页面上的地址很长（带 32 位令牌），手输几乎必然打错；
 * 扫一下是最省事也最不容易出错的方式。
 */
public final class LanQr {

    private LanQr() {
    }

    /** 生成二维码位图；内容太长放不下时返回 null（地址不会长到这一步，但不假装成功）。 */
    public static Bitmap render(String payload, int edge) {
        if (payload == null || payload.isEmpty() || edge <= 0) {
            return null;
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION,
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
        try {
            BitMatrix matrix = new MultiFormatWriter()
                    .encode(payload, BarcodeFormat.QR_CODE, edge, edge, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception | LinkageError ex) {
            return null;
        }
    }
}
