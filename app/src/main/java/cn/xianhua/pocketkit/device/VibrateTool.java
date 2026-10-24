package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.SeekBar;
import java.util.Locale;

/**
 * 073 震动器。
 *
 * 需要 VIBRATE 权限（普通权限，安装即授予，不需要运行时申请）。
 * 部分设备没有振动马达，此时明确说明而不是静默无效。
 */
public final class VibrateTool {

    private VibrateTool() {
    }

    public static boolean supports(String id) {
        return "073".equals(id);
    }

    public static String limitation(String id) {
        return "073".equals(id)
                ? "是否真的振动取决于设备是否有马达；频率与强度由系统实现，不同机型差异很大，不能用于精密定时或触觉反馈校准。"
                : "";
    }

    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static void build(Ui ui) {
        Vibrator v = vibrator(ui.activity());
        if (v == null || !v.hasVibrator()) {
            ui.note("本机没有可用的振动马达，这项功能无法使用。");
            return;
        }

        ui.note("振动时长越短越省电。长按连续振动会明显耗电并发热，请避免长时间开启。");

        final int[] duration = {200};
        final android.widget.TextView durationLabel = ui.note("时长：200 毫秒");
        SeekBar bar = new SeekBar(ui.activity());
        bar.setMax(1900);
        bar.setProgress(duration[0] - 100);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                duration[0] = progress + 100;
                durationLabel.setText(String.format(Locale.US, "时长：%d 毫秒", duration[0]));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        ui.body().addView(bar, new android.widget.LinearLayout.LayoutParams(-1, ui.dp(40)));

        ui.button("振动一次", () -> {
            if (!v.hasVibrator()) {
                ui.fail(new IllegalStateException("本机没有振动马达。"));
                return;
            }
            v.vibrate(VibrationEffect.createOneShot(duration[0], VibrationEffect.DEFAULT_AMPLITUDE));
            ui.show(String.format(Locale.US, "已发出一次 %d 毫秒的振动。", duration[0]));
        });

        ui.button("节奏振动（长-短-长）", () -> {
            long[] pattern = {0, 400, 150, 120, 150, 400};
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            ui.show("已播放：长 400ms → 短 120ms → 长 400ms。");
        });

        ui.button("停止振动", () -> {
            v.cancel();
            ui.show("已请求停止振动。");
        });

        if (v.hasAmplitudeControl()) {
            ui.note("本机支持振幅控制，可切换到「自定义振幅」按钮按百分比调节强度。");
            final int[] amplitude = {128};
            final android.widget.TextView ampLabel = ui.note("振幅：128 / 255（约 50%）");
            SeekBar amp = new SeekBar(ui.activity());
            amp.setMax(255);
            amp.setProgress(amplitude[0]);
            amp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    amplitude[0] = Math.max(1, progress);
                    ampLabel.setText(String.format(Locale.US, "振幅：%d / 255（约 %d%%）",
                            amplitude[0], Math.round(amplitude[0] / 255f * 100)));
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            ui.body().addView(amp, new android.widget.LinearLayout.LayoutParams(-1, ui.dp(40)));
            ui.button("用该振幅振动一次", () -> {
                v.vibrate(VibrationEffect.createOneShot(duration[0], amplitude[0]));
                ui.show(String.format(Locale.US, "已用振幅 %d 振动 %d 毫秒。", amplitude[0], duration[0]));
            });
        } else {
            ui.note("本机不支持振幅控制，只能使用系统默认强度。");
        }

        ui.addCleanup(v::cancel);
        ui.show("振动会在按下按钮时发生。");
    }
}
