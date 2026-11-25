package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;

/**
 * Sound, vibration, volume and wake-lock seam of the alarm runtime (CMP-41 {@code AlarmPlayer}; implemented by
 * {@link RingEngine} on the player thread and by {@code AlarmPlayer}, which hops onto that thread). Calls are
 * synchronous: when a method returns, its volume change has happened, so the F8 save / restore order holds.
 */
interface AlarmSound {

    /**
     * Starts ringing: vibration from t = 0, sound through the fallback chain, alarm-stream volume per D-023 from the
     * given step, the partial wake lock (F16) and call detection (F10).
     *
     * @param type       the ringing type
     * @param step       escalation step to start at (0 for a new alarm; the persisted step on resume)
     * @param userVolume the user's saved alarm volume (base of the D-023 schedule)
     */
    void start(AlarmType type, int step, int userVolume);

    /**
     * Switches the ringing type, keeping the current escalation level (F8 „Replacing … keeps the current escalation
     * level”).
     *
     * @param type the new type
     */
    void switchType(AlarmType type);

    /**
     * Keep-alive (R-35 {@code Update}, heartbeat): ensures the player is playing and re-acquires the partial wake
     * lock with a 2-min timeout (F16). Starts ringing at the given step when it is not playing.
     *
     * @param type       the ringing type
     * @param step       step to start at when not playing
     * @param userVolume the user's saved alarm volume
     */
    void keepAlive(AlarmType type, int step, int userVolume);

    /**
     * Silences sound and vibration of a type at once after a Snooze request; the full stop follows with
     * {@code Stop(type)}. Ignored when another type plays.
     *
     * @param type the snoozed type
     */
    void silence(AlarmType type);

    /** Stops sound, vibration, escalation and call detection and releases the wake lock. */
    void stop();

    /**
     * Sets the alarm-stream volume back to the user's value (F8 restore).
     *
     * @param volume the saved user volume
     */
    void restoreVolume(int volume);

    /**
     * Returns the current alarm-stream volume (saved as the user's volume before the first change).
     *
     * @return the index
     */
    int currentVolume();

    /**
     * Returns the current escalation step.
     *
     * @return the step
     */
    int currentStep();
}
