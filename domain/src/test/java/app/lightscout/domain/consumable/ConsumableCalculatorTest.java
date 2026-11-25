package app.lightscout.domain.consumable;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * TST-01 CONS-1…CONS-3 and the R-62 counter states (CP-01 §8.6 / §9).
 */
public class ConsumableCalculatorTest {

    private static final Instant T = Instant.parse("2026-10-10T08:00:00Z");

    private final ConsumableCalculator calculator = new ConsumableCalculator();

    @Test
    public void cons1_site3d_reminderInstants() {
        List<Instant> times = calculator.reminderTimes(T, 3);
        assertEquals(List.of(T.plus(Duration.ofHours(48)), T.plus(Duration.ofHours(72))), times);
        ConsumableStatus s = calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofHours(1)));
        assertEquals(T.plus(Duration.ofHours(48)), s.due24hAt());
        assertEquals(T.plus(Duration.ofHours(72)), s.dueAt());
    }

    @Test
    public void cons2_age_expired_noneFound() {
        ConsumableStatus at53h = calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofDays(2).plusHours(5)));
        assertEquals(Duration.ofHours(53), at53h.age());
        assertEquals(T, at53h.lastChange());

        ConsumableStatus expired = calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofHours(72)));
        assertEquals(ConsumableState.EXPIRED, expired.state());

        ConsumableStatus none = calculator.status(ConsumableKind.SENSOR, null, 10, T);
        assertEquals(ConsumableState.NONE_FOUND, none.state());
        assertEquals(ConsumableKind.SENSOR, none.kind());
        assertNull(none.lastChange());
        assertNull(none.age());
        assertNull(none.due24hAt());
        assertNull(none.dueAt());
    }

    @Test
    public void r62_counterStates_normalChangeSoonExpired() {
        assertEquals(ConsumableState.NORMAL,
                calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofHours(48)).minusSeconds(1)).state());
        assertEquals(ConsumableState.CHANGE_SOON,
                calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofHours(48))).state());
        assertEquals(ConsumableState.CHANGE_SOON,
                calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofHours(72)).minusSeconds(1)).state());
        assertEquals(ConsumableState.EXPIRED,
                calculator.status(ConsumableKind.SITE, T, 3, T.plus(Duration.ofHours(80))).state());
    }

    @Test
    public void r62_lifetime1Day_warningStartsAtChange() {
        assertEquals(ConsumableState.CHANGE_SOON, calculator.status(ConsumableKind.SITE, T, 1, T).state());
    }

    @Test
    public void cons3_t3_ageIsAbsoluteAcrossDstEnd() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant change = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, warsaw).toInstant();
        Instant now = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, warsaw).toInstant();
        ConsumableStatus s = calculator.status(ConsumableKind.SENSOR, change, 10, now);
        assertEquals(Duration.ofHours(25), s.age());
    }
}
