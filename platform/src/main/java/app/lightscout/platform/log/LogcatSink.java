package app.lightscout.platform.log;

import app.lightscout.domain.port.EventCode;

/**
 * Logcat seam of the logger (ADR-014: platform {@code android.util.Log} behind the own wrapper, no Timber).
 */
public interface LogcatSink {

    /**
     * Writes an event line (release and debug builds, 08 §8.1) at the logcat priority of its level (Q-49):
     * INFO → {@code Log.i}, WARN → {@code Log.w}, ERROR → {@code Log.e}. Never DEBUG / VERBOSE.
     *
     * @param level the R-13 level of the event
     * @param line  the R-13 line (unchanged text, includes the level)
     */
    void event(EventCode.Level level, String line);

    /**
     * Writes a debug message at DEBUG priority. Called by debug builds only.
     *
     * @param tag     tag
     * @param message message
     */
    void debug(String tag, String message);
}
