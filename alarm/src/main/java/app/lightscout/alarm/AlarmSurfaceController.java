package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.NotificationChannels;

/**
 * CH-01 alarm notification and S-09 content orchestration (CP-07 §7.2 ADR-047, F9, F11, F20, GIR-4; CP-00A R-36,
 * R-37, R-81), free of Android types.
 *
 * <ul>
 *   <li>One alarm notification at a time (id 2). A new alarm, a replacement, the F11 screen-off re-post and the
 *       F10 call-end re-post use a <b>fresh</b> post (cancel, then post with the full-screen intent) so that the
 *       platform applies GIR-4 again (S-09 when the screen is off or locked, heads-up when in use).</li>
 *   <li>{@code Update} and the F9 delete-intent re-post use an only-alert-once post; a swipe is never a snooze
 *       (AL-4).</li>
 *   <li>Q-52: every post asks CMP-26 for the <b>current</b> alarm channel id (F7 may have re-created CH-01 as
 *       {@code alarms_v1}, which cancels the old channel's notification); no id is cached.</li>
 * </ul>
 * Methods are called from {@code monitorExecutor}, the main thread (receivers) and the player thread (call end), so
 * they are {@code synchronized}; the port hops to the main looper itself.
 */
final class AlarmSurfaceController implements AlarmSurface {

    /**
     * Notification seam ({@code NotificationManager} + {@code NotificationCompat} in the app).
     */
    interface NotificationPort {

        /**
         * Posts the alarm notification (id 2).
         *
         * @param channelId     the current CH-01 id
         * @param view          the content
         * @param onlyAlertOnce {@code true} for a content refresh or the F9 re-post
         */
        void post(String channelId, AlarmView view, boolean onlyAlertOnce);

        /** Cancels the alarm notification (id 2). */
        void cancel();
    }

    /**
     * F11 {@code SCREEN_OFF} watch ({@code AlarmScreenOffReceiver}, registered only while ringing — R-37).
     */
    interface ScreenOffWatch {

        /**
         * Starts watching.
         *
         * @param onScreenOff called on each {@code SCREEN_OFF}
         */
        void register(Runnable onScreenOff);

        /** Stops watching. */
        void unregister();
    }

    private final NotificationPort notifications;
    private final NotificationChannels channels;
    private final AlarmScreenModel screen;
    private final ScreenOffWatch screenOff;

    private AlarmView current;
    private boolean watching;

    /**
     * Creates the controller.
     *
     * @param notifications notification seam
     * @param channels      CMP-26 (current CH-01 id per post — Q-52)
     * @param screen        S-09 content model
     * @param screenOff     F11 screen-off watch
     */
    AlarmSurfaceController(NotificationPort notifications, NotificationChannels channels, AlarmScreenModel screen,
                           ScreenOffWatch screenOff) {
        this.notifications = notifications;
        this.channels = channels;
        this.screen = screen;
        this.screenOff = screenOff;
    }

    @Override
    public synchronized void show(AlarmView view) {
        current = view;
        screen.publish(view);
        postFresh(view);
        if (!watching) {
            watching = true;
            screenOff.register(this::onScreenOff);
        }
    }

    @Override
    public synchronized void replace(AlarmView view) {
        current = view;
        screen.publish(view);
        postFresh(view);
    }

    @Override
    public synchronized void update(AlarmView view) {
        current = view;
        screen.publish(view);
        notifications.post(channelId(), view, true);
    }

    @Override
    public synchronized void close() {
        current = null;
        notifications.cancel();
        screen.publish(null);
        if (watching) {
            watching = false;
            screenOff.unregister();
        }
    }

    /** F11: while ringing and S-09 is not in the foreground, {@code SCREEN_OFF} re-posts CH-01 with the FSI. */
    synchronized void onScreenOff() {
        if (current != null && !screen.foreground()) {
            postFresh(current);
        }
    }

    /** F10: at call end, re-post CH-01 with the FSI (S-09 if screen off or locked, otherwise heads-up — GIR-4). */
    synchronized void onCallEnded() {
        if (current != null) {
            postFresh(current);
        }
    }

    /**
     * F9: the alarm notification was swiped away → immediate re-post with only-alert-once; not a snooze (AL-4).
     *
     * @param type the type named by the delete intent
     */
    synchronized void onNotificationDeleted(AlarmType type) {
        if (current != null && current.type() == type) {
            notifications.post(channelId(), current, true);
        }
    }

    private void postFresh(AlarmView view) {
        notifications.cancel();
        notifications.post(channelId(), view, false);
    }

    private String channelId() {
        return channels.channelId(ChannelId.ALARMS);
    }
}
