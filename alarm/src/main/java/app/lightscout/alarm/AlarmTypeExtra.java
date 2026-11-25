package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;

/**
 * The {@code alarmType} intent extra (IF-50…IF-52, SR-22): written as the {@link AlarmType} constant name and
 * validated against the enum on receipt; anything missing or unknown is ignored.
 */
final class AlarmTypeExtra {

    /** Extra name. */
    static final String NAME = "alarmType";

    private AlarmTypeExtra() {
    }

    /**
     * Parses the extra.
     *
     * @param value the extra value, may be {@code null}
     * @return the type, or {@code null} when missing or unknown
     */
    static AlarmType parse(String value) {
        if (value == null) {
            return null;
        }
        for (AlarmType t : AlarmType.values()) {
            if (t.name().equals(value)) {
                return t;
            }
        }
        return null;
    }
}
