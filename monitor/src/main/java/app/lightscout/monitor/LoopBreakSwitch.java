package app.lightscout.monitor;

/**
 * VR-16 Run B switch (🏛️ Q-63 / IR-66): while on, ticks are not re-armed (the tick alarm is cancelled instead), so
 * the sync loop is dead while the process lives and the watchdog path can be observed. It is set **only** by
 * {@code DebugLoopBreakReceiver}, which exists in the {@code src/debug} source set alone; the release build contains
 * no caller of {@link #set(boolean)}, so the switch stays off (TST-13: no debug receiver in the release manifest).
 */
final class LoopBreakSwitch {

    private static volatile boolean suppressTickRearm;

    private LoopBreakSwitch() {
    }

    /**
     * Tells whether tick re-arming is suppressed.
     *
     * @return {@code true} while VR-16 Run B breaks the loop
     */
    static boolean suppressed() {
        return suppressTickRearm;
    }

    /**
     * Sets the switch (debug receiver only).
     *
     * @param on {@code true} to break the loop
     */
    static void set(boolean on) {
        suppressTickRearm = on;
    }
}
