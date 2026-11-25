package app.lightscout.alarm;

/**
 * S-09 key policy (A-S09-1, DS:A-26, DS:D-084, F1): volume up and volume down snooze; the power key and screen-off
 * never snooze (the power key cannot be intercepted by an app; screen-off is not an input). Back is handled by the
 * always-enabled no-op back callback (F17), not here.
 */
final class SnoozeKeys {

    /** {@code KeyEvent.KEYCODE_VOLUME_UP}. */
    static final int KEYCODE_VOLUME_UP = 24;
    /** {@code KeyEvent.KEYCODE_VOLUME_DOWN}. */
    static final int KEYCODE_VOLUME_DOWN = 25;
    /** {@code KeyEvent.KEYCODE_POWER}. */
    static final int KEYCODE_POWER = 26;
    /** {@code KeyEvent.ACTION_DOWN}. */
    static final int ACTION_DOWN = 0;

    private SnoozeKeys() {
    }

    /**
     * Tells whether S-09 consumes a key (so that a volume key never changes the volume while S-09 is shown).
     *
     * @param keyCode key code
     * @return {@code true} for volume up / down
     */
    static boolean consumes(int keyCode) {
        return keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN;
    }

    /**
     * Tells whether a key event snoozes: the first down event of volume up or volume down.
     *
     * @param keyCode     key code
     * @param action      key action
     * @param repeatCount repeat count of the event
     * @return {@code true} when the event snoozes
     */
    static boolean snoozes(int keyCode, int action, int repeatCount) {
        return consumes(keyCode) && action == ACTION_DOWN && repeatCount == 0;
    }
}
