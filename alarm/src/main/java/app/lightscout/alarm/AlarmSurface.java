package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmView;

/**
 * Visible part of a ringing alarm: the CH-01 notification (S-11 alarm kinds) and the S-09 content model, plus the
 * F11 screen-off watch. Implemented by {@link AlarmSurfaceController}.
 */
interface AlarmSurface {

    /**
     * A new alarm rings: post CH-01 with the full-screen intent, publish the S-09 content, watch {@code SCREEN_OFF}.
     *
     * @param view the content
     */
    void show(AlarmView view);

    /**
     * AL-6 / F20: the replaced alarm's notification is cancelled and the new one is posted with the full-screen
     * intent (one alarm notification at a time); S-09 shows the new type.
     *
     * @param view the new content
     */
    void replace(AlarmView view);

    /**
     * Content refresh while ringing (R-35 {@code Update}): CH-01 re-posted with only-alert-once, S-09 refreshed.
     *
     * @param view the content
     */
    void update(AlarmView view);

    /** Stop: cancel CH-01, close S-09, stop watching {@code SCREEN_OFF}. */
    void close();
}
