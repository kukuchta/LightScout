package app.lightscout.usecases.alarm;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.model.Trend;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Ordered command application (🏛️ SS-02 assumption #2), Replace, Update, snooze (AL-9, R-29, R-35). */
public class AlarmStateTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant READING = NOW.minusSeconds(60);

    private static AlarmView view(AlarmType t) {
        return new AlarmView(t, 300, Trend.FLAT, READING, null, null, null, 15);
    }

    @Test
    public void stopThenStartInOneDecision_appliedInOrder_activeIsNewType() {
        AlarmState s = new AlarmState(AlarmType.HIGH,
                Map.of(AlarmType.HIGH, new AlarmTypeState(NOW.minusSeconds(300), null, 3, READING)), 5);
        AlarmState out = AlarmStateTransitions.afterCommands(s, List.of(new AlarmCommand.Stop(AlarmType.HIGH),
                new AlarmCommand.Start(AlarmType.NO_DATA, view(AlarmType.NO_DATA))), NOW);
        assertEquals(AlarmType.NO_DATA, out.activeType());
        assertEquals(NOW, out.perType().get(AlarmType.NO_DATA).ringingSince());
        assertNull(out.perType().get(AlarmType.HIGH).ringingSince());
        assertEquals(3, out.perType().get(AlarmType.HIGH).escalationStep());   // :alarm's field untouched
        assertEquals(Integer.valueOf(5), out.savedUserAlarmVolume());
    }

    @Test
    public void replace_movesRinging_updateChangesNothing() {
        AlarmState s = new AlarmState(AlarmType.LOW,
                Map.of(AlarmType.LOW, new AlarmTypeState(NOW.minusSeconds(300), null, 0, READING)), null);
        AlarmState out = AlarmStateTransitions.afterCommands(s, List.of(
                new AlarmCommand.Replace(AlarmType.LOW, AlarmType.URGENT_LOW, view(AlarmType.URGENT_LOW))), NOW);
        assertEquals(AlarmType.URGENT_LOW, out.activeType());
        assertEquals(READING, out.perType().get(AlarmType.URGENT_LOW).triggerReadingTime());
        assertNull(out.perType().get(AlarmType.LOW));
        assertEquals(out, AlarmStateTransitions.afterCommands(out,
                List.of(new AlarmCommand.Update(view(AlarmType.URGENT_LOW))), NOW.plusSeconds(60)));
    }

    @Test
    public void clearSnooze_dropsOnlySnoozedUntil_keepsRingingAndEscalation() {
        AlarmState s = new AlarmState(AlarmType.HIGH, Map.of(
                AlarmType.HIGH, new AlarmTypeState(NOW, null, 2, READING),
                AlarmType.LOW, new AlarmTypeState(null, NOW.plusSeconds(600), 0, null)), 4);
        AlarmState out = AlarmStateTransitions.afterCommands(s, List.of(new AlarmCommand.ClearSnooze(AlarmType.LOW),
                new AlarmCommand.ClearSnooze(AlarmType.HIGH)), NOW);
        assertNull(out.perType().get(AlarmType.LOW));
        assertEquals(new AlarmTypeState(NOW, null, 2, READING), out.perType().get(AlarmType.HIGH));
        assertEquals(AlarmType.HIGH, out.activeType());
        assertEquals(Integer.valueOf(4), out.savedUserAlarmVolume());
    }

    @Test
    public void snooze_otherTypeRinging_keepsActive() {
        AlarmState s = new AlarmState(AlarmType.HIGH,
                Map.of(AlarmType.HIGH, new AlarmTypeState(NOW, null, 0, READING)), null);
        AlarmState out = AlarmStateTransitions.afterSnooze(s, AlarmType.LOW, NOW.plusSeconds(900));
        assertEquals(AlarmType.HIGH, out.activeType());
        assertEquals(NOW.plusSeconds(900), out.perType().get(AlarmType.LOW).snoozedUntil());
    }
}
