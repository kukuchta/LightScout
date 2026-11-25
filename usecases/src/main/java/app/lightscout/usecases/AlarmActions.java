package app.lightscout.usecases;

import app.lightscout.domain.alarm.AlarmType;

/**
 * CMP-15 AlarmActions — IF-25 (CP-02 §8.7, CP-00A R-29). Every method posts its work to {@code monitorExecutor}
 * (single writer of alarm state) and returns immediately.
 */
public interface AlarmActions {

    /**
     * Snoozes one alarm type on this phone for its snooze duration (NOT_RUNNING uses the No data snooze — F20).
     *
     * @param t the type
     */
    void snooze(AlarmType t);

    /**
     * Turns the „Alarms” master switch on or off (D-022).
     *
     * @param on new state
     */
    void setAlarmsEnabled(boolean on);

    /**
     * Turns the Low or High alarm on or off; any other type is a no-op (DS:D-021, R-29).
     *
     * @param t  LOW or HIGH
     * @param on new state
     */
    void setAlarmTypeEnabled(AlarmType t, boolean on);
}
