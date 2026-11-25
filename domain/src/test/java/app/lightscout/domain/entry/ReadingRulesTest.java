package app.lightscout.domain.entry;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TST-01 DR-1 and M-9 reading rules (CP-01 §8.5, §8.2; CP-00A R-9).
 */
public class ReadingRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static Reading at(String id, Instant t) {
        return new Reading(id, t, 100, Trend.FLAT, "t");
    }

    @Test
    public void m9_futureDatedBoundary() {
        assertFalse(ReadingRules.isFutureDated(at("a", NOW.plus(Duration.ofMinutes(10))), NOW));
        assertTrue(ReadingRules.isFutureDated(at("b", NOW.plus(Duration.ofMinutes(10)).plusSeconds(1)), NOW));
    }

    @Test
    public void displayable_dropsFuture_sorts_dedupsAgainstLastKept() {
        Reading r0 = at("r0", NOW.minusSeconds(300));
        Reading r40 = at("r40", NOW.minusSeconds(260));
        Reading r80 = at("r80", NOW.minusSeconds(220));
        Reading future = at("f", NOW.plus(Duration.ofMinutes(12)));
        List<Reading> out = ReadingRules.displayable(Arrays.asList(r80, future, null, r40, r0), NOW);
        assertEquals(List.of(r0, r80), out);
    }

    @Test
    public void dr1_exactly60sApart_bothKept() {
        Reading a = at("a", NOW.minusSeconds(120));
        Reading b = at("b", NOW.minusSeconds(60));
        assertEquals(List.of(a, b), ReadingRules.displayable(List.of(b, a), NOW));
    }

    @Test
    public void latest_ignoresFutureDated() {
        Reading old = at("old", NOW.minus(Duration.ofMinutes(5)));
        Reading near = at("near", NOW.plus(Duration.ofMinutes(9)));
        Reading future = at("future", NOW.plus(Duration.ofMinutes(12)));
        assertEquals(near, ReadingRules.latest(List.of(old, future, near), NOW).orElseThrow());
        assertTrue(ReadingRules.latest(List.of(future), NOW).isEmpty());
        assertTrue(ReadingRules.latest(List.of(), NOW).isEmpty());
    }
}
