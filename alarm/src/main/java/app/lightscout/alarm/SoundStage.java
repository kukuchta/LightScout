package app.lightscout.alarm;

/**
 * WF-02 step 3 sound fallback chain (CP-00A R-80, ADR-085): the bundled self-generated tone, then the system
 * default alarm tone, then vibration only. The constant names are the {@code ALARM_SOUND_FALLBACK} log detail.
 */
enum SoundStage {
    /** {@code res/raw/alarm_tone}. */
    PRIMARY_TONE,
    /** System default alarm URI ({@code RingtoneManager.TYPE_ALARM}). */
    SYSTEM_TONE,
    /** No sound could be played: vibration (and the notification) only. */
    VIBRATION_ONLY;

    /**
     * Returns the next stage of the chain.
     *
     * @return the next stage; {@link #VIBRATION_ONLY} stays
     */
    SoundStage next() {
        switch (this) {
            case PRIMARY_TONE:
                return SYSTEM_TONE;
            default:
                return VIBRATION_ONLY;
        }
    }
}
