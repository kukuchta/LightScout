package app.lightscout.platform.readiness;

import java.util.List;

import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.ReadinessItem;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.ReadinessStatus;

/**
 * CMP-27 {@link ReadinessChecker} (CP-05 §8.2, CP-00A R-11, R-74 / D-036).
 *
 * <p>API bands: B1 29–30 · B2 31–32 · B3 33 · B4 34 · B5 35 · B6 36+.
 * <table>
 *   <caption>Items per band</caption>
 *   <tr><th>#</th><th>shown</th><th>required</th></tr>
 *   <tr><td>1 Notifications</td><td>B3+ always; B1–B2 only while notifications are off (D-036)</td><td>yes</td></tr>
 *   <tr><td>2 DND access</td><td>always</td><td>yes</td></tr>
 *   <tr><td>3 Full-screen</td><td>B4+</td><td>yes</td></tr>
 *   <tr><td>4 Battery</td><td>always</td><td>yes</td></tr>
 *   <tr><td>5 Exact alarm</td><td>B2+ and only while {@code canScheduleExactAlarms()} is false (ADR-044)</td>
 *       <td>yes (if shown)</td></tr>
 *   <tr><td>7 Alarm channel</td><td>always</td><td>yes</td></tr>
 *   <tr><td>6 Manufacturer</td><td>always, last</td><td>no — recommended, never counted</td></tr>
 * </table>
 * Hidden items are still returned ({@code shown = false}, status {@code DONE}) so the list always has seven items.
 * {@link #check()} performs system-service queries only and is called on every tick (F15).
 */
public final class DefaultReadinessChecker implements ReadinessChecker {

    /** First API level with the {@code POST_NOTIFICATIONS} runtime permission (B3). */
    static final int API_NOTIFICATION_PERMISSION = 33;
    /** First API level with {@code canUseFullScreenIntent()} (B4). */
    static final int API_FULL_SCREEN_INTENT = 34;
    /** First API level with {@code canScheduleExactAlarms()} (B2). */
    static final int API_EXACT_ALARM = 31;

    private final ReadinessProbe probe;

    /**
     * Creates the checker.
     *
     * @param probe system queries ({@link AndroidReadinessProbe} in the app)
     */
    public DefaultReadinessChecker(ReadinessProbe probe) {
        this.probe = probe;
    }

    @Override
    public List<ReadinessItem> check() {
        int sdk = probe.sdkInt();
        return List.of(
                notifications(sdk),
                required(ReadinessItemId.DND_ACCESS, probe.notificationPolicyAccessGranted()),
                fullScreen(sdk),
                required(ReadinessItemId.BATTERY, probe.ignoringBatteryOptimizations()),
                exactAlarm(sdk),
                required(ReadinessItemId.ALARM_CHANNEL, probe.alarmChannel().alarmReady()),
                new ReadinessItem(ReadinessItemId.MANUFACTURER, true, false, ReadinessStatus.RECOMMENDED));
    }

    @Override
    public int missingRequiredCount() {
        int count = 0;
        for (ReadinessItem item : check()) {
            if (item.missingRequired()) {
                count++;
            }
        }
        return count;
    }

    private ReadinessItem notifications(int sdk) {
        boolean enabled = probe.notificationsEnabled();
        boolean shown = sdk >= API_NOTIFICATION_PERMISSION || !enabled;
        return new ReadinessItem(ReadinessItemId.NOTIFICATIONS, shown, true, status(enabled));
    }

    private ReadinessItem fullScreen(int sdk) {
        if (sdk < API_FULL_SCREEN_INTENT) {
            return hidden(ReadinessItemId.FULL_SCREEN);
        }
        return required(ReadinessItemId.FULL_SCREEN, probe.canUseFullScreenIntent());
    }

    private ReadinessItem exactAlarm(int sdk) {
        if (sdk < API_EXACT_ALARM || probe.canScheduleExactAlarms()) {
            return hidden(ReadinessItemId.EXACT_ALARM);
        }
        return new ReadinessItem(ReadinessItemId.EXACT_ALARM, true, true, ReadinessStatus.NEEDS_ATTENTION);
    }

    private static ReadinessItem required(ReadinessItemId id, boolean done) {
        return new ReadinessItem(id, true, true, status(done));
    }

    private static ReadinessItem hidden(ReadinessItemId id) {
        return new ReadinessItem(id, false, true, ReadinessStatus.DONE);
    }

    private static ReadinessStatus status(boolean done) {
        return done ? ReadinessStatus.DONE : ReadinessStatus.NEEDS_ATTENTION;
    }
}
