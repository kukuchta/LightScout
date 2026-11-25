package app.lightscout.monitor;

/**
 * Build-time switches of {@code :monitor}. Changing one is a reviewed code change, never a user setting.
 */
final class MonitorConfig {

    /**
     * VR-07 pre-approved fallback (D-020): use {@code setAlarmClock} for the expected-reading tick only.
     * {@code false} = the recommended path ({@code setExactAndAllowWhileIdle}). Extending the fallback to the
     * watchdog or window ticks needs a DCR (D-030).
     */
    static final boolean ALARM_CLOCK_FOR_EXPECTED_READING_TICK = false;

    private MonitorConfig() {
    }
}
