package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * CP-07 §9 „JVM — escalation schedule” (D-023): start volume = max(user, 40 %); +10 % at every 15 s; capped at
 * 100 % after ≈ 90 s. Vibration from t = 0 is covered in {@link RingEngineTest}.
 */
public class EscalationScheduleTest {

    @Test
    public void start_isFortyPercentFloor_whenUserIsLower() {
        // max 15: 40 % = 6
        assertEquals(6, EscalationSchedule.level(2, 1, 15, 0));
        assertEquals(6, EscalationSchedule.level(0, 0, 15, 0));
    }

    @Test
    public void start_isUserVolume_whenAboveFortyPercent() {
        assertEquals(10, EscalationSchedule.level(10, 1, 15, 0));
    }

    @Test
    public void plusTenPercentPerStep_untilMax() {
        // max 10 makes 10 % = one index
        int[] expected = {4, 5, 6, 7, 8, 9, 10, 10, 10};
        for (int step = 0; step < expected.length; step++) {
            assertEquals("step " + step, expected[step], EscalationSchedule.level(0, 0, 10, step));
        }
    }

    @Test
    public void reachesMaxAfterNinetySeconds_fromFloor() {
        assertEquals(6, EscalationSchedule.lastStep(0, 0, 10));
        assertEquals(90_000L, EscalationSchedule.lastStep(0, 0, 10) * EscalationSchedule.STEP_MS);
        // a coarse 7-index alarm stream reaches its maximum one step earlier (indices round up), never later
        assertEquals(5, EscalationSchedule.lastStep(0, 1, 7));
        assertEquals(java.util.List.of(3, 4, 5, 5, 6, 7), java.util.List.of(EscalationSchedule.level(0, 1, 7, 0),
                EscalationSchedule.level(0, 1, 7, 1), EscalationSchedule.level(0, 1, 7, 2),
                EscalationSchedule.level(0, 1, 7, 3), EscalationSchedule.level(0, 1, 7, 4),
                EscalationSchedule.level(0, 1, 7, 5)));
    }

    @Test
    public void stepIntervalIsFifteenSeconds() {
        assertEquals(15_000L, EscalationSchedule.STEP_MS);
    }

    @Test
    public void neverAboveMax_neverBelowMin() {
        assertEquals(15, EscalationSchedule.level(15, 1, 15, 3));
        assertEquals(15, EscalationSchedule.level(99, 1, 15, 0));
        assertEquals(3, EscalationSchedule.level(0, 3, 4, 0));   // 40 % of 4 = 2 → stream minimum 3
        assertEquals(0, EscalationSchedule.lastStep(15, 1, 15));
    }

    @Test
    public void userAboveFloor_risesTenPercentFromUser() {
        // user 60 % of 10 → 6, 7, 8, 9, 10
        assertEquals(6, EscalationSchedule.level(6, 0, 10, 0));
        assertEquals(7, EscalationSchedule.level(6, 0, 10, 1));
        assertEquals(10, EscalationSchedule.level(6, 0, 10, 4));
        assertEquals(4, EscalationSchedule.lastStep(6, 0, 10));
    }

    @Test
    public void fallbackGain_fortyToHundredPercent() {
        assertEquals(0.4f, EscalationSchedule.fallbackGain(0), 1e-6);
        assertEquals(0.5f, EscalationSchedule.fallbackGain(1), 1e-6);
        assertEquals(1.0f, EscalationSchedule.fallbackGain(6), 1e-6);
        assertEquals(1.0f, EscalationSchedule.fallbackGain(9), 1e-6);
        assertEquals(6, EscalationSchedule.fallbackLastStep());
    }
}
