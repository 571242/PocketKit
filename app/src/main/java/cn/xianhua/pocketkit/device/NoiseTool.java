package cn.xianhua.pocketkit.device;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 070 噪音测量。
 *
 * 默认只报告 dBFS（相对数字满刻度的电平），不冒充声压级。
 * 手机麦克风没有可读的绝对灵敏度标定，也没有可信的默认偏移量，
 * 所以绝不用"dBFS + 94"这类固定偏移把数字包装成 dB SPL。
 *
 * 用户若确实需要声压级，必须自己填入由外部声级计实测得出的偏移量，
 * 且结果始终标注为"依据自定义偏移的估算"。
 */
public final class NoiseTool {

    private NoiseTool() {
    }

    public static boolean supports(String id) {
        return "070".equals(id);
    }

    public static String limitation(String id) {
        return "070".equals(id)
                ? "未校准：默认只给出 dBFS 相对电平，不是声压级。麦克风有自动增益与频响限制，读数只能用于同一台设备的前后对比，不能作为噪声检测或法定声级计依据。"
                : "";
    }

    private static final int SAMPLE_RATE = 44100;

    public static void build(Ui ui) {
        ui.note("测量的是麦克风收到的数字电平，用 dBFS 表示：0 dBFS 是数字满刻度，数值越小越安静。");
        ui.note(SensorMath.UNCALIBRATED_SPL_NOTE);
        ui.note("要把读数换算成声压级，必须先用一台真实声级计测出已知声源，再把你实测的差值填进下面的偏移量。没有这一步，任何 dB SPL 数字都是编的。");

        final boolean[] useCustomOffset = {false};
        final int[] offset = {0};

        final android.widget.TextView offsetLabel = (android.widget.TextView)
                ui.note("自定义偏移：未启用（当前只显示 dBFS 相对电平）");

        android.widget.CheckBox enable = new android.widget.CheckBox(ui.activity());
        enable.setText("启用自定义偏移，把 dBFS 换算成估算声压级");
        enable.setTextColor(ui.ink());
        enable.setOnCheckedChangeListener((view, checked) -> {
            useCustomOffset[0] = checked;
            offsetLabel.setText(checked
                    ? String.format(Locale.US, "自定义偏移：已启用 +%d dB（依据你自己的声级计实测，不是校准结果）", offset[0])
                    : "自定义偏移：未启用（当前只显示 dBFS 相对电平）");
        });
        ui.body().addView(enable);

        android.widget.EditText offsetField = ui.field("实测偏移量（dB，仅在勾选上面选项后生效）", "例如 92");
        offsetField.setText("0");

        ui.button("应用偏移量", () -> {
            String raw = offsetField.getText().toString().trim();
            int value;
            try {
                value = raw.isEmpty() ? 0 : Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                ui.fail(new IllegalArgumentException("偏移量必须是整数（dB）。"));
                return;
            }
            if (value < 0 || value > 140) {
                ui.fail(new IllegalArgumentException("偏移量应在 0 到 140 dB 之间。"));
                return;
            }
            offset[0] = value;
            offsetLabel.setText(useCustomOffset[0]
                    ? String.format(Locale.US, "自定义偏移：已启用 +%d dB（依据你自己的声级计实测，不是校准结果）", value)
                    : "自定义偏移：已保存 +" + value + " dB，但尚未启用");
            ui.note("已保存。勾选上面的选项才会用到它。");
        });

        final AtomicBoolean running = new AtomicBoolean(false);

        ui.button("开始测量", () -> ui.ensurePermission(
                android.Manifest.permission.RECORD_AUDIO,
                "噪音测量需要麦克风权限。录音只在内存中处理，不保存、不写入文件、不上传。",
                () -> {
                    if (running.get()) {
                        ui.note("已经在测量中。");
                        return;
                    }
                    running.set(true);
                    Thread worker = new Thread(
                            () -> recordLoop(ui, running, offset, useCustomOffset), "noise-meter");
                    worker.setDaemon(true);
                    worker.start();
                }));

        ui.button("停止测量", () -> {
            running.set(false);
            ui.show("已停止测量，麦克风将在当前缓冲读完后释放。");
        });

        ui.addCleanup(() -> running.set(false));

        // 关键：按 Home 时 Activity 不会销毁，麦克风必须在这里真正停掉，
        // 不能等 onDestroy —— 否则等于在用户看不见的时候持续录音。
        ui.onStopAction(() -> running.set(false));
        ui.onStartAction(() -> {
            if (!running.get()) {
                ui.note("已从后台返回。麦克风已停止，如需继续请重新按「开始测量」。");
            }
        });

        ui.note(SensorSession.STOP_ON_LEAVE_NOTE);
        ui.show("按「开始测量」后才会占用麦克风。");
    }

    private static void recordLoop(Ui ui, AtomicBoolean running, int[] offset, boolean[] useCustomOffset) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            running.set(false);
            ui.activity().runOnUiThread(() -> ui.fail(new IllegalStateException(
                    "本机不支持 44.1kHz 单声道录音，无法测量。")));
            return;
        }
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 10 * 2);

        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                running.set(false);
                ui.activity().runOnUiThread(() -> ui.fail(new IllegalStateException(
                        "麦克风初始化失败，可能被其他应用占用。")));
                return;
            }
            short[] buffer = new short[bufferSize / 2];
            float display = SensorMath.DB_FLOOR;
            float peak = SensorMath.DB_FLOOR;
            recorder.startRecording();

            while (running.get()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                float rms = SensorMath.pcmRms(buffer, read);
                // dBFS 本身恒为负；这里就是原始数字电平，不做任何伪装成 SPL 的加工。
                float dbfs = (float) SensorMath.dbfs(rms, 32768f);
                // 快起慢落：峰值不掉，显示值缓慢回落，读数才看得清。
                display = dbfs > display ? dbfs : SensorMath.lowPass(display, dbfs, 0.05f);
                peak = Math.max(peak, dbfs);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US, "相对电平 %.1f dBFS\n峰值 %.1f dBFS\n", display, peak));
                if (useCustomOffset[0]) {
                    sb.append(String.format(Locale.US, "\n估算声压级 ≈ %.1f dB\n", dbfs + offset[0]));
                    sb.append("这个数字 = dBFS + 你填的 ").append(offset[0])
                            .append(" dB，只在你自己的标定条件下成立。\n");
                } else {
                    sb.append("\n未启用偏移量，因此不给出 dB SPL 数字。\n");
                }
                sb.append('\n').append(SensorMath.UNCALIBRATED_SPL_NOTE);

                final String text = sb.toString();
                ui.activity().runOnUiThread(() -> ui.show(text));
            }
        } catch (SecurityException denied) {
            running.set(false);
            ui.activity().runOnUiThread(() -> ui.fail(denied));
            return;
        } catch (Exception ex) {
            ui.activity().runOnUiThread(() -> ui.fail(ex));
        } finally {
            running.set(false);
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (IllegalStateException ignored) {
                    // 已经停止，无需处理。
                }
                recorder.release();
            }
        }
    }
}
