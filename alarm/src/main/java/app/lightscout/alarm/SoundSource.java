package app.lightscout.alarm;

/**
 * Sound source of each {@link SoundStage} (CP-00A R-80; IR-84, IR-92). The system fallback is always
 * {@code Settings.System.DEFAULT_ALARM_ALERT_URI}, which needs no permission. {@code RingtoneManager} lookups of the
 * actual default ringtone are never used (they need {@code READ_MEDIA_AUDIO}).
 */
enum SoundSource {
    /** {@code res/raw/alarm_tone} in {@code :platform} (self-generated beep loop, Q-69). */
    RAW_ALARM_TONE,
    /** {@code Settings.System.DEFAULT_ALARM_ALERT_URI}. */
    SYSTEM_DEFAULT_ALARM_URI;

    /**
     * Returns the source of a stage.
     *
     * @param stage the fallback stage
     * @return the source, or {@code null} for {@link SoundStage#VIBRATION_ONLY}
     */
    static SoundSource of(SoundStage stage) {
        switch (stage) {
            case PRIMARY_TONE:
                return RAW_ALARM_TONE;
            case SYSTEM_TONE:
                return SYSTEM_DEFAULT_ALARM_URI;
            default:
                return null;
        }
    }
}
