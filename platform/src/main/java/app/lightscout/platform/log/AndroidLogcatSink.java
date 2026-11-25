package app.lightscout.platform.log;

import android.util.Log;

import app.lightscout.domain.port.EventCode;

/**
 * {@link LogcatSink} on {@code android.util.Log}. Event lines use the tag {@link #TAG}, so
 * {@code adb logcat -s LightScout} shows exactly the event codes (QA-01…QA-05, TST-11). Priority per level
 * (Q-49): INFO → I, WARN → W, ERROR → E.
 */
public final class AndroidLogcatSink implements LogcatSink {

    /** Logcat tag of event lines. */
    public static final String TAG = "LightScout";

    @Override
    public void event(EventCode.Level level, String line) {
        switch (level) {
            case WARN -> Log.w(TAG, line);
            case ERROR -> Log.e(TAG, line);
            default -> Log.i(TAG, line);
        }
    }

    @Override
    public void debug(String tag, String message) {
        Log.d(tag, message);
    }
}
