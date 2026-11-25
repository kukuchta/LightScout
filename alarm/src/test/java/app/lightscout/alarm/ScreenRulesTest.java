package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.dataloss.DataLossCause;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * S-09 / S-11 pure rules: dark window 22:00–07:00 (DS:D-061), snooze key policy (A-S09-1, F1), {@code alarmType}
 * extra validation (SR-22), title / colour / cause mapping (GIR-10, DS:D-024), unique PendingIntent request codes.
 */
public class ScreenRulesTest {

    @Test
    public void darkWindow_boundaries() {
        assertTrue(DarkWindow.isDark(LocalTime.of(22, 0)));
        assertTrue(DarkWindow.isDark(LocalTime.of(23, 59)));
        assertTrue(DarkWindow.isDark(LocalTime.MIDNIGHT));
        assertTrue(DarkWindow.isDark(LocalTime.of(6, 59, 59)));
        assertFalse(DarkWindow.isDark(LocalTime.of(7, 0)));
        assertFalse(DarkWindow.isDark(LocalTime.of(12, 0)));
        assertFalse(DarkWindow.isDark(LocalTime.of(21, 59, 59)));
    }

    @Test
    public void darkWindow_usesThePhoneZone() {
        Instant t = Instant.parse("2026-10-15T20:30:00Z");
        assertFalse(DarkWindow.isDark(t, ZoneId.of("UTC")));          // 20:30
        assertTrue(DarkWindow.isDark(t, ZoneId.of("Europe/Warsaw")));  // 22:30 CEST
    }

    @Test
    public void volumeKeysSnooze_onFirstDownOnly() {
        assertTrue(SnoozeKeys.snoozes(SnoozeKeys.KEYCODE_VOLUME_UP, SnoozeKeys.ACTION_DOWN, 0));
        assertTrue(SnoozeKeys.snoozes(SnoozeKeys.KEYCODE_VOLUME_DOWN, SnoozeKeys.ACTION_DOWN, 0));
        assertFalse(SnoozeKeys.snoozes(SnoozeKeys.KEYCODE_VOLUME_DOWN, 1, 0));   // ACTION_UP
        assertFalse(SnoozeKeys.snoozes(SnoozeKeys.KEYCODE_VOLUME_UP, SnoozeKeys.ACTION_DOWN, 3));   // auto-repeat
        assertTrue(SnoozeKeys.consumes(SnoozeKeys.KEYCODE_VOLUME_UP));
    }

    @Test
    public void powerKeyAndOtherKeys_neverSnooze() {
        assertFalse(SnoozeKeys.snoozes(SnoozeKeys.KEYCODE_POWER, SnoozeKeys.ACTION_DOWN, 0));
        assertFalse(SnoozeKeys.consumes(SnoozeKeys.KEYCODE_POWER));
        assertFalse(SnoozeKeys.snoozes(4, SnoozeKeys.ACTION_DOWN, 0));   // KEYCODE_BACK: handled by the F17 callback
        assertFalse(SnoozeKeys.snoozes(164, SnoozeKeys.ACTION_DOWN, 0)); // KEYCODE_VOLUME_MUTE
    }

    @Test
    public void alarmTypeExtra_unknownOrMissing_isIgnored() {
        assertEquals(AlarmType.URGENT_LOW, AlarmTypeExtra.parse("URGENT_LOW"));
        assertEquals(AlarmType.NOT_RUNNING, AlarmTypeExtra.parse("NOT_RUNNING"));
        assertNull(AlarmTypeExtra.parse(null));
        assertNull(AlarmTypeExtra.parse(""));
        assertNull(AlarmTypeExtra.parse("urgent_low"));
        assertNull(AlarmTypeExtra.parse("LOW; DROP"));
        assertNull(AlarmTypeExtra.parse("0"));
        assertEquals("alarmType", AlarmTypeExtra.NAME);
    }

    @Test
    public void titleColours_perGir10() {
        assertEquals(AlarmTexts.TitleStyle.BADGE_ERROR_CONTAINER, AlarmTexts.titleStyle(AlarmType.URGENT_LOW));
        assertEquals(AlarmTexts.TitleStyle.ERROR, AlarmTexts.titleStyle(AlarmType.LOW));
        assertEquals(AlarmTexts.TitleStyle.ERROR, AlarmTexts.titleStyle(AlarmType.NO_DATA));
        assertEquals(AlarmTexts.TitleStyle.ERROR, AlarmTexts.titleStyle(AlarmType.NOT_RUNNING));
        assertEquals(AlarmTexts.TitleStyle.WARNING, AlarmTexts.titleStyle(AlarmType.HIGH));
    }

    @Test
    public void titlesAndKinds() {
        assertEquals(R.string.s09_title_urgent_low, AlarmTexts.titleRes(AlarmType.URGENT_LOW));
        assertEquals(R.string.s09_title_low, AlarmTexts.titleRes(AlarmType.LOW));
        assertEquals(R.string.s09_title_high, AlarmTexts.titleRes(AlarmType.HIGH));
        assertEquals(R.string.s09_title_no_data, AlarmTexts.titleRes(AlarmType.NO_DATA));
        assertEquals(R.string.s09_title_not_running, AlarmTexts.titleRes(AlarmType.NOT_RUNNING));
        assertEquals(AlarmTexts.NotificationKind.GLUCOSE, AlarmTexts.kind(AlarmType.HIGH));
        assertEquals(AlarmTexts.NotificationKind.NO_DATA, AlarmTexts.kind(AlarmType.NO_DATA));
        assertEquals(AlarmTexts.NotificationKind.WATCHDOG, AlarmTexts.kind(AlarmType.NOT_RUNNING));
        assertTrue(AlarmTexts.showsReading(AlarmType.LOW));
        assertFalse(AlarmTexts.showsReading(AlarmType.NO_DATA));
        assertFalse(AlarmTexts.showsReading(AlarmType.NOT_RUNNING));
    }

    @Test
    public void causeLines_theOnlyThree() {
        assertEquals(R.string.s09_cause_no_internet, AlarmTexts.causeRes(DataLossCause.NO_INTERNET));
        assertEquals(R.string.s09_cause_ns_not_responding, AlarmTexts.causeRes(DataLossCause.NS_NOT_RESPONDING));
        assertEquals(R.string.s09_cause_uploader, AlarmTexts.causeRes(DataLossCause.UPLOADER));
        assertEquals(0, AlarmTexts.causeRes(null));
    }

    @Test
    public void minutesAndAge() {
        Instant now = Instant.parse("2026-10-15T02:37:59Z");
        assertEquals(23, AlarmTexts.minutesSince(Instant.parse("2026-10-15T02:14:00Z"), now));
        assertEquals(0, AlarmTexts.minutesSince(now.plusSeconds(60), now));
        assertEquals(Duration.ofMinutes(2), AlarmTexts.age(now.minusSeconds(120), now));
        assertNull(AlarmTexts.age(null, now));
    }

    @Test
    public void pendingIntentRequestCodes_uniquePerPurposeAndType() {
        Set<Integer> codes = new HashSet<>();
        for (AlarmIntents.Purpose p : AlarmIntents.Purpose.values()) {
            for (AlarmType t : AlarmType.values()) {
                assertTrue(codes.add(AlarmIntents.requestCode(p, t)));
            }
        }
        assertEquals(AlarmIntents.Purpose.values().length * AlarmType.values().length, codes.size());
    }
}
