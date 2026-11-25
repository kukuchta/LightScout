package app.lightscout.feature.home;

import app.lightscout.domain.alarm.AlarmType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rows of the read-only „Snoozed” window (DCR-21, D-128): one per running snooze, in alarm-severity order.
 *
 * @param type        snoozed alarm type
 * @param endsAt      local end time (minute precision)
 * @param minutesLeft whole minutes left, rounded up, at least 1
 */
record SnoozeRows(AlarmType type, LocalTime endsAt, int minutesLeft) {

    /**
     * Builds the rows of the snoozes still running at {@code now}.
     *
     * @param snoozes end instant per type
     * @param now     IF-15 now
     * @param zone    display zone
     * @return the rows, empty when nothing is snoozed
     */
    static List<SnoozeRows> of(Map<AlarmType, Instant> snoozes, Instant now, ZoneId zone) {
        List<SnoozeRows> rows = new ArrayList<>();
        for (AlarmType type : AlarmType.values()) {
            Instant until = snoozes.get(type);
            if (until == null || !until.isAfter(now)) {
                continue;
            }
            long millis = Duration.between(now, until).toMillis();
            int minutes = (int) Math.max(1, (millis + 59_999) / 60_000);
            rows.add(new SnoozeRows(type, until.atZone(zone).toLocalTime().withSecond(0).withNano(0), minutes));
        }
        return rows;
    }
}
