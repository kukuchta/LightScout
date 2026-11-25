package app.lightscout.usecases.status;

import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/** BUG-02 / Q-50: {@code READINESS_MISSING:<count>} on the first projection and on change only (R-13). */
public class ReadinessLoggingTest {

    private static List<String> readinessEvents(Harness h) {
        return h.logger.events.stream().filter(e -> e.startsWith("READINESS_MISSING")).toList();
    }

    @Test
    public void firstProjectionLogsOnce_unchangedLaterTicksDoNot() {
        Harness h = new Harness();
        h.readiness.missing = 2;
        h.tick();
        assertEquals(List.of("READINESS_MISSING:2"), readinessEvents(h));
        h.clock.advanceMinutes(1);
        h.tick();
        h.status.refresh();
        h.monitorExecutor.runAll();
        assertEquals(List.of("READINESS_MISSING:2"), readinessEvents(h));
    }

    @Test
    public void changeLogsOnce() {
        Harness h = new Harness();
        h.readiness.missing = 1;
        h.tick();
        h.readiness.missing = 0;
        h.clock.advanceMinutes(1);
        h.tick();
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(List.of("READINESS_MISSING:1", "READINESS_MISSING:0"), readinessEvents(h));
    }

    @Test
    public void noOtherLogLinesAdded() {
        Harness h = new Harness();
        h.tick();
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(List.of("READINESS_MISSING:0", "SYNC_OK", "SYNC_OK"), h.logger.events);
    }
}
