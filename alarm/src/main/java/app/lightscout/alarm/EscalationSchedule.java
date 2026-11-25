package app.lightscout.alarm;

/**
 * D-023 / ADR-048 volume escalation (CP-07 §8.2): „start at max(user's alarm volume, 40 %), +10 % every 15 s up to
 * 100 % (≈ 90 s), vibration from the start”. Pure arithmetic on alarm-stream volume indices.
 *
 * <p>Percentages are of the stream's maximum index. The level of step {@code n} is
 * {@code ceil((max(user %, 40 %) + 10 % × n) × max / 100)}, capped at the maximum and never below the stream minimum,
 * so from the 40 % floor the maximum is reached at step 6 (90 s). Each percentage is rounded up to the next index
 * (the 40 % start floor is never undercut); on a coarse stream (few indices) this can reach the maximum one step
 * earlier, never later.
 *
 * <p>VR-12 fallback (F8): when the stream volume cannot be changed, the player escalates its own gain within the
 * current stream volume ({@link #fallbackGain(int)}).
 */
final class EscalationSchedule {

    /** Interval between escalation steps (D-023). */
    static final long STEP_MS = 15_000L;

    /** Start floor in percent of the maximum (D-023). */
    static final int START_PERCENT = 40;

    /** Increase per step in percent of the maximum (D-023). */
    static final int STEP_PERCENT = 10;

    /** Player gain while a call is active (F10). */
    static final float IN_CALL_GAIN = 0.3f;

    private EscalationSchedule() {
    }

    /**
     * Returns the alarm-stream volume index for an escalation step.
     *
     * @param userVolume the user's saved alarm volume (index)
     * @param minVolume  stream minimum index
     * @param maxVolume  stream maximum index
     * @param step       escalation step, 0 = start
     * @return the index to set
     */
    static int level(int userVolume, int minVolume, int maxVolume, int step) {
        if (maxVolume <= 0) {
            return 0;
        }
        int user = clamp(userVolume, 0, maxVolume);
        long startScaled = Math.max(100L * user, (long) START_PERCENT * maxVolume);
        long scaled = startScaled + (long) STEP_PERCENT * Math.max(0, step) * maxVolume;
        long index = (scaled + 99L) / 100L;
        return clamp((int) Math.min(index, maxVolume), Math.max(0, minVolume), maxVolume);
    }

    /**
     * Returns the first step at which the level reaches the maximum; escalation stops there.
     *
     * @param userVolume the user's saved alarm volume (index)
     * @param minVolume  stream minimum index
     * @param maxVolume  stream maximum index
     * @return the last step (0 when the start level already is the maximum)
     */
    static int lastStep(int userVolume, int minVolume, int maxVolume) {
        int step = 0;
        while (level(userVolume, minVolume, maxVolume, step) < maxVolume && step < 100) {
            step++;
        }
        return step;
    }

    /**
     * Player gain for the VR-12 fallback: 40 % +10 % per step, up to 100 %.
     *
     * @param step escalation step
     * @return the gain 0.4…1.0
     */
    static float fallbackGain(int step) {
        int percent = Math.min(100, START_PERCENT + STEP_PERCENT * Math.max(0, step));
        return percent / 100f;
    }

    /**
     * Last step of the gain fallback (100 %).
     *
     * @return 6
     */
    static int fallbackLastStep() {
        return (100 - START_PERCENT + STEP_PERCENT - 1) / STEP_PERCENT;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
