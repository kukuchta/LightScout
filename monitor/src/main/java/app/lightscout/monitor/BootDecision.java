package app.lightscout.monitor;

/**
 * Pure decisions of the boot / update / time receivers (WF-04 + F5, WF-10; ADR-046). Unknown actions are ignored
 * (SR-21 allow-list). No {@code USER_UNLOCKED} (F5).
 */
final class BootDecision {

    /** {@code Intent.ACTION_LOCKED_BOOT_COMPLETED}. */
    static final String LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    /** {@code Intent.ACTION_BOOT_COMPLETED}. */
    static final String BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    /** {@code Intent.ACTION_MY_PACKAGE_REPLACED}. */
    static final String MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    /** {@code Intent.ACTION_TIME_CHANGED}. */
    static final String TIME_SET = "android.intent.action.TIME_SET";
    /** {@code Intent.ACTION_TIMEZONE_CHANGED}. */
    static final String TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED";

    /** What a receiver does. */
    enum Action {
        /** Not on the allow-list, or monitoring not configured. */
        IGNORE,
        /** Post „LightScout is paused” (CH-02); DE flags only. */
        POST_PAUSED,
        /** Start {@code MonitorService} after boot. */
        START_BOOT,
        /** Start {@code MonitorService} after an update. */
        START_PACKAGE_REPLACED,
        /** WF-10: tick + watchdog rescheduled. */
        TIME_CHANGED
    }

    private BootDecision() {
    }

    /**
     * Tells whether the boot receiver accepts the action at all (allow-list, SR-21).
     *
     * @param action intent action (nullable)
     * @return {@code true} for the three boot / update actions
     */
    static boolean isBootAction(String action) {
        return LOCKED_BOOT_COMPLETED.equals(action) || BOOT_COMPLETED.equals(action)
                || MY_PACKAGE_REPLACED.equals(action);
    }

    /**
     * Tells whether the time receiver accepts the action at all (allow-list, SR-21).
     *
     * @param action intent action (nullable)
     * @return {@code true} for {@code TIME_SET} and {@code TIMEZONE_CHANGED}
     */
    static boolean isTimeAction(String action) {
        return TIME_SET.equals(action) || TIMEZONE_CHANGED.equals(action);
    }

    /**
     * Decision of {@code BootReceiver}.
     *
     * @param action               intent action (nullable)
     * @param monitoringConfigured ST-05 flag
     * @return the action
     */
    static Action forBoot(String action, boolean monitoringConfigured) {
        if (!isBootAction(action) || !monitoringConfigured) {
            return Action.IGNORE;
        }
        return switch (action) {
            case LOCKED_BOOT_COMPLETED -> Action.POST_PAUSED;
            case BOOT_COMPLETED -> Action.START_BOOT;
            default -> Action.START_PACKAGE_REPLACED;
        };
    }

    /**
     * Decision of {@code TimeChangeReceiver}.
     *
     * @param action               intent action (nullable)
     * @param monitoringConfigured ST-05 flag
     * @return {@link Action#TIME_CHANGED} or {@link Action#IGNORE}
     */
    static Action forTime(String action, boolean monitoringConfigured) {
        return isTimeAction(action) && monitoringConfigured ? Action.TIME_CHANGED : Action.IGNORE;
    }

    /**
     * Tells whether the action records the current boot count as handled (F5; IR-76): only {@code BOOT_COMPLETED}
     * (the resume path), whether or not monitoring is configured. {@code LOCKED_BOOT_COMPLETED} writes nothing, so a
     * boot whose {@code BOOT_COMPLETED} is suppressed (VR-28) stays detectable.
     *
     * @param action intent action (nullable)
     * @return {@code true} for {@code BOOT_COMPLETED}
     */
    static boolean recordsBootCount(String action) {
        return BOOT_COMPLETED.equals(action);
    }

    /**
     * IR-68: the paused notification is posted only while the user is still locked; after unlock (late delivery,
     * no secure lock screen) it would overwrite the live S-10 (same id 1).
     *
     * @param action       decided action
     * @param userUnlocked {@code UserManager.isUserUnlocked()}
     * @return {@code true} when the paused notification must be posted
     */
    static boolean postsPaused(Action action, boolean userUnlocked) {
        return action == Action.POST_PAUSED && !userUnlocked;
    }
}
