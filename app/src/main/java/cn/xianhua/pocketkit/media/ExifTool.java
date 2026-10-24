package cn.xianhua.pocketkit.media;

import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 012 照片信息编辑器，含 D05 一键清除 EXIF 隐私信息。
 *
 * <p>Removal rewrites the file through {@link ExifInterface}: the pixels are copied verbatim and
 * only the metadata segment is dropped, so the picture is bit-identical while the GPS coordinates,
 * device serial-ish fields and timestamps are gone. Orientation is the one tag that must survive,
 * because dropping it would rotate every portrait photo.
 */
public final class ExifTool implements Tool {

    /** The tags that actually identify where and how a photo was taken. */
    private static final String[] PRIVACY_TAGS = {
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_SUBJECT_DISTANCE,
            ExifInterface.TAG_USER_COMMENT,
    };

    private final Host host;

    public ExifTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        final Uri[] source = {null};
        host.note("查看或清除照片里记录的拍摄位置、设备与时间信息。清除只改元数据，不改动画面像素。");

        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「查看信息」读取当前标签，或直接「清除隐私信息」。");
        }));

        host.button("查看信息", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片");
                return;
            }
            host.background(() -> {
                Map<String, String> tags = read(uri);
                StringBuilder sb = new StringBuilder();
                sb.append("图片：").append(MediaIo.displayName(host.activity(), uri)).append('\n');
                sb.append("可读元数据 ").append(tags.size()).append(" 项：\n");
                if (tags.isEmpty()) {
                    sb.append("（这张图片没有可读的 EXIF 信息，可能已被清理或本来就没有）");
                }
                for (Map.Entry<String, String> e : tags.entrySet()) {
                    sb.append("  ").append(e.getKey()).append("：").append(e.getValue()).append('\n');
                }
                List<String> risky = privacyPresent(tags);
                sb.append('\n');
                if (risky.isEmpty()) {
                    sb.append("未发现常见隐私项（定位、设备型号、拍摄时间）。");
                } else {
                    sb.append("发现隐私项：").append(String.join("、", risky));
                }
                host.post(() -> host.show(sb.toString()));
            });
        });

        host.button("清除隐私信息（生成新文件）", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片");
                return;
            }
            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    String name = MediaIo.displayName(host.activity(), uri);
                    Map<String, String> before = read(uri);
                    List<String> risky = privacyPresent(before);
                    if (risky.isEmpty()) {
                        host.post(() -> {
                            host.setRunning(false);
                            host.show("未完成：这张图片本来就没有定位、设备型号或拍摄时间等常见隐私项，无需清除。"
                                    + "（清除不会改变画面，因此也不会让文件更小。）");
                        });
                        return;
                    }

                    prepared = MediaIo.scratch(host.activity(), ".jpg");
                    int copied;
                    try (InputStream in = MediaIo.openInput(host.activity(), uri);
                         OutputStream out = new FileOutputStream(prepared)) {
                        copied = (int) MediaIo.copy(in, out, cancel, MediaIo.MAX_BYTES, null);
                    }
                    cancel.check();
                    ExifInterface exif = new ExifInterface(prepared.getAbsolutePath());
                    // Orientation must survive; without it every portrait photo comes out sideways.
                    int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                    int removed = 0;
                    for (String tag : PRIVACY_TAGS) {
                        if (exif.getAttribute(tag) != null) {
                            exif.setAttribute(tag, null);
                            removed++;
                        }
                    }
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
                    exif.saveAttributes();
                    cancel.check();

                    long after = prepared.length();
                    String outName = ImageCompressTool.suggestName(name, "clean.jpg");
                    String summary = "已清除 " + removed + " 项元数据：" + String.join("、", risky)
                            + "\n画面像素未改动，文件大小 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(after)
                            + "（原 " + cn.xianhua.pocketkit.core.SizeFmt.bytes(copied) + "）。";
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n请选择保存位置（原文件不会被修改）。");
                        host.createDocument(outName, "image/jpeg", target -> {
                            host.background(() -> {
                                try {
                                    MediaIo.publish(host.activity(), target, ready);
                                    host.post(() -> host.show(summary + "\n\n已保存：" + outName));
                                } finally {
                                    if (ready.exists()) {
                                        ready.delete();
                                    }
                                }
                            });
                        });
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

        host.note("提醒：JPEG 的 EXIF 可被清理；PNG 与部分 WebP 本就不带这些标签，此工具会如实显示「无隐私项」。"
                + "清理后的文件仍是普通图片，系统相册里可能另存一份缩略图缓存，与原文件无关。");
    }

    /** The EXIF values worth showing, in a stable order. */
    Map<String, String> read(Uri uri) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = MediaIo.openInput(host.activity(), uri)) {
            ExifInterface exif = new ExifInterface(in);
            putIf(out, "拍摄时间", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
            putIf(out, "修改时间", exif.getAttribute(ExifInterface.TAG_DATETIME));
            putIf(out, "设备品牌", exif.getAttribute(ExifInterface.TAG_MAKE));
            putIf(out, "设备型号", exif.getAttribute(ExifInterface.TAG_MODEL));
            putIf(out, "软件", exif.getAttribute(ExifInterface.TAG_SOFTWARE));
            putIf(out, "作者", exif.getAttribute(ExifInterface.TAG_ARTIST));
            putIf(out, "版权", exif.getAttribute(ExifInterface.TAG_COPYRIGHT));
            putIf(out, "图片说明", exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
            putIf(out, "镜头", exif.getAttribute(ExifInterface.TAG_LENS_MODEL));
            putIf(out, "曝光时间", exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
            putIf(out, "光圈", exif.getAttribute(ExifInterface.TAG_F_NUMBER));
            putIf(out, "ISO", exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
            putIf(out, "焦距", exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
            putIf(out, "方向", exif.getAttribute(ExifInterface.TAG_ORIENTATION));
            double[] latLong = exif.getLatLong();
            if (latLong != null) {
                out.put("拍摄位置", String.format(java.util.Locale.US, "%.6f, %.6f", latLong[0], latLong[1]));
            }
            putIf(out, "海拔", exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE));
        } catch (Exception e) {
            // A PNG or a stripped file legitimately has no EXIF; the empty map is the honest answer.
        }
        return out;
    }

    /** Which of the risky categories are present, phrased for a person. */
    static List<String> privacyPresent(Map<String, String> tags) {
        List<String> out = new ArrayList<>();
        if (tags.containsKey("拍摄位置")) {
            out.add("拍摄位置（GPS）");
        }
        if (tags.containsKey("设备型号") || tags.containsKey("设备品牌")) {
            out.add("设备型号");
        }
        if (tags.containsKey("拍摄时间") || tags.containsKey("修改时间")) {
            out.add("拍摄/修改时间");
        }
        if (tags.containsKey("作者") || tags.containsKey("版权")) {
            out.add("作者信息");
        }
        return out;
    }

    private static void putIf(Map<String, String> out, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            out.put(label, value.trim());
        }
    }
}
