package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.status.TickReason;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen-on with stale data (06 §4, R-37) and WF-11 network regained → immediate tick (ADR-052).
 */
public class TriggerPolicyTest {

    private final ScreenOnPolicy screen = new ScreenOnPolicy();
    private final Instant now = Fakes.T0;

    @Test
    public void screenOn_freshData_noTick() {
        assertFalse(screen.shouldTick(Fakes.snapshot(Fakes.reading(now.minusSeconds(120)), DataLossKind.NONE), now));
    }

    @Test
    public void screenOn_ageAtLeast5Min_tick() {
        assertTrue(screen.shouldTick(Fakes.snapshot(Fakes.reading(now.minus(Duration.ofMinutes(5))),
                DataLossKind.NONE), now));
    }

    @Test
    public void screenOn_dataLossOrNoReadingOrNoSnapshot_tick() {
        assertTrue(screen.shouldTick(Fakes.snapshot(Fakes.reading(now), DataLossKind.DATA_LOSS), now));
        assertTrue(screen.shouldTick(Fakes.snapshot(null, DataLossKind.FIRST_USE), now));
        assertTrue(screen.shouldTick(null, now));
    }

    @Test
    public void q57_placeholderTitle_emptyOnlyWithoutReading_evenWhenStale() {
        assertEquals(null, PlaceholderStatusNotificationBuilder.latestOrNull(null));
        assertEquals(null, PlaceholderStatusNotificationBuilder.latestOrNull(Fakes.snapshot(null, DataLossKind.FIRST_USE)));
        app.lightscout.domain.model.Reading old = Fakes.reading(now.minus(Duration.ofHours(3)));
        assertEquals(old, PlaceholderStatusNotificationBuilder.latestOrNull(Fakes.snapshot(old, DataLossKind.DATA_LOSS)));
    }

    @Test
    public void connectivity_regained_ticksOnce_lossDoesNot() {
        List<TickReason> ticks = new ArrayList<>();
        MonitorControl control = new MonitorControl() {
            @Override
            public void requestTick(TickReason r) {
                ticks.add(r);
            }

            @Override
            public void reevaluateAlarms() {
                // unused
            }

            @Override
            public void startMonitoring() {
                // unused
            }
        };
        ConnectivityTrigger trigger = new ConnectivityTrigger(control, true);
        trigger.accept(true);            // no change
        trigger.accept(false);           // lost: nothing
        trigger.accept(true);            // regained
        trigger.accept(true);            // duplicate
        assertEquals(List.of(TickReason.CONNECTIVITY_REGAINED), ticks);
    }
}
