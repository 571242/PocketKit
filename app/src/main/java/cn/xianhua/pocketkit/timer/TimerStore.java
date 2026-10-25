package cn.xianhua.pocketkit.timer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/** Independent timer snapshots; wall-clock adjustments must never invalidate elapsed-time timers. */
public final class TimerStore {
    private static final String PREF = "pocketkit_timer";
    private final Context context;
    private final SharedPreferences prefs;

    public TimerStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String key(TimerState.Mode mode) { return "state_v1_" + mode.name(); }

    public long bootId() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    public TimerState.Restored load(TimerState.Mode mode) {
        return TimerState.restore(raw(mode), SystemClock.elapsedRealtime(), bootId());
    }

    public String raw(TimerState.Mode mode) { return prefs.getString(key(mode), ""); }

    public boolean save(TimerState state) {
        state.markSaved(SystemClock.elapsedRealtime(), bootId());
        return prefs.edit().putString(key(state.mode()), state.encode()).commit();
    }
}
