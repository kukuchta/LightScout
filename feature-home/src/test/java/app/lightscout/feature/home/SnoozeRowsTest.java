package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** „Snoozed” window rows (DCR-21, D-128): whole minutes rounded up, local end time, severity order. */
public class SnoozeRowsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    public void minutesAreRoundedUpAndNeverZero() {
        List<SnoozeRows> rows = SnoozeRows.of(Map.of(
                AlarmType.HIGH, NOW.plusSeconds(61),
                AlarmType.LOW, NOW.plusSeconds(1)), NOW, ZoneOffset.UTC);
        assertEquals(AlarmType.LOW, rows.get(0).type());
        assertEquals(1, rows.get(0).minutesLeft());
        assertEquals(2, rows.get(1).minutesLeft());
    }

    @Test
    public void exactMinuteIsNotRoundedUp() {
        List<SnoozeRows> rows = SnoozeRows.of(Map.of(AlarmType.HIGH, NOW.plusSeconds(600)), NOW, ZoneOffset.UTC);
        assertEquals(10, rows.get(0).minutesLeft());
        assertEquals(LocalTime.of(10, 10), rows.get(0).endsAt());
    }

    @Test
    public void expiredSnoozesAreDroppedAndSeverityOrderKept() {
        List<SnoozeRows> rows = SnoozeRows.of(Map.of(
                AlarmType.NO_DATA, NOW.plusSeconds(300),
                AlarmType.URGENT_LOW, NOW.plusSeconds(300),
                AlarmType.HIGH, NOW), NOW, ZoneOffset.UTC);
        assertEquals(List.of(AlarmType.URGENT_LOW, AlarmType.NO_DATA),
                rows.stream().map(SnoozeRows::type).toList());
        assertTrue(SnoozeRows.of(Map.of(), NOW, ZoneOffset.UTC).isEmpty());
    }
}
