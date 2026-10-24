package cn.xianhua.pocketkit;

import android.content.Context;

/** Initializes the ML Kit runtime once, lazily and safely across tool screens. */
public final class MlKitBootstrap {
    private static final Once ONCE = new Once();

    private MlKitBootstrap() {
    }

    public static void ensureInitialized(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("ML Kit 初始化需要 Context。");
        }
        ensureInitialized(context, c ->
                com.google.mlkit.common.MlKit.initialize(c.getApplicationContext()));
    }

    interface Initializer {
        void initialize(Context context);
    }

    static void ensureInitialized(Context context, Initializer initializer) {
        ONCE.ensure(context, initializer);
    }

    static final class Once {
        private final Object lock = new Object();
        private volatile boolean initialized;

        void ensure(Context context, Initializer initializer) {
            if (initialized) {
                return;
            }
            synchronized (lock) {
                if (initialized) {
                    return;
                }
                initializer.initialize(context);
                initialized = true;
            }
        }
    }
}
