package app.lightscout.platform.connectivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.lightscout.platform.RecordingLogger;

/**
 * IF-19 state logic (CP-05 §8.5): listeners fire on changes only; {@code NET_LOST} / {@code NET_REGAINED} logged
 * per transition; a failing listener does not stop the others.
 */
public class OnlineStateTrackerTest {

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    public void initialStateIsReportedWithoutEvent() {
        OnlineStateTracker tracker = new OnlineStateTracker(true, logger);
        assertTrue(tracker.isOnline());
        assertTrue(logger.events.isEmpty());
    }

    @Test
    public void lossAndRegainNotifyListenersAndLog() {
        OnlineStateTracker tracker = new OnlineStateTracker(true, logger);
        List<Boolean> seen = new ArrayList<>();
        tracker.addListener(seen::add);

        tracker.update(false);
        assertFalse(tracker.isOnline());
        tracker.update(true);

        assertEquals(List.of(false, true), seen);
        assertEquals(List.of("NET_LOST", "NET_REGAINED"), logger.events);
    }

    @Test
    public void unchangedStateDoesNotNotify() {
        OnlineStateTracker tracker = new OnlineStateTracker(false, logger);
        List<Boolean> seen = new ArrayList<>();
        tracker.addListener(seen::add);

        tracker.update(false);
        tracker.update(false);

        assertTrue(seen.isEmpty());
        assertTrue(logger.events.isEmpty());
    }

    @Test
    public void failingListenerDoesNotStopOthers() {
        OnlineStateTracker tracker = new OnlineStateTracker(true, logger);
        List<Boolean> seen = new ArrayList<>();
        tracker.addListener(online -> {
            throw new IllegalStateException("listener bug");
        });
        tracker.addListener(seen::add);

        tracker.update(false);

        assertEquals(List.of(false), seen);
    }
}
