package app.lightscout.domain.alarm;

import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.model.Trend;

import java.time.Instant;
import java.util.Objects;

/**
 * Content of the alarm screen and alarm notification (S-09 / CH-01, CP-00A R-4, R-80).
 *
 * @param type          the alarm type
 * @param sgv           glucose of the reading shown; nullable
 * @param trend         trend of that reading ({@link Trend#UNKNOWN} when none)
 * @param readingTime   time of that reading; nullable
 * @param cause         data-loss cause, NO_DATA only; nullable
 * @param lastCheck     NOT_RUNNING: last completed check; nullable
 * @param dataLossSince NO_DATA: anchor for „No new readings for {n} min”; nullable
 * @param snoozeMinutes snooze duration of this type (NOT_RUNNING → No data snooze, F20)
 */
public record AlarmView(AlarmType type, Integer sgv, Trend trend, Instant readingTime,
                        DataLossCause cause, Instant lastCheck, Instant dataLossSince,
                        int snoozeMinutes) {

    /**
     * Creates a view.
     *
     * @throws NullPointerException if {@code type} or {@code trend} is null
     */
    public AlarmView {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trend, "trend");
    }
}
