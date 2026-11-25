package app.lightscout.platform.log;

/**
 * Tells whether credential-encrypted (CE) storage is available (CP-00 §6).
 */
public interface UnlockState {

    /**
     * Tells whether the user has unlocked the phone since boot.
     *
     * @return {@code true} when CE storage may be touched
     */
    boolean isUserUnlocked();
}
