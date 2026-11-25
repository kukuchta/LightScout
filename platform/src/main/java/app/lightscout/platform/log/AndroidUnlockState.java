package app.lightscout.platform.log;

import android.content.Context;
import android.os.UserManager;

/**
 * {@link UnlockState} on {@code UserManager.isUserUnlocked()}. Once unlocked it stays unlocked until the process
 * dies (CE storage stays available until reboot), so the answer is cached after the first {@code true}.
 * Any failure reads as locked.
 */
public final class AndroidUnlockState implements UnlockState {

    private final Context context;
    private volatile boolean unlocked;

    /**
     * Creates the state reader.
     *
     * @param context any context (the application context is kept)
     */
    public AndroidUnlockState(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean isUserUnlocked() {
        if (unlocked) {
            return true;
        }
        try {
            UserManager users = context.getSystemService(UserManager.class);
            unlocked = users != null && users.isUserUnlocked();
        } catch (RuntimeException e) {
            return false;
        }
        return unlocked;
    }
}
