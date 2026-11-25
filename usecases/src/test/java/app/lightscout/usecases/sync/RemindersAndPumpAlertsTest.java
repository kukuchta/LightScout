package app.lightscout.usecases.sync;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderStage;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.EntryType;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static app.lightscout.usecases.fakes.Fakes.treatment;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** WF-06 (+ R-70 / D-037 first sight, T-3, DS:A-26) and WF-07 inside the cycle (CP-02 §9). */
public class RemindersAndPumpAlertsTest {

    /** Site lifetime 3 d: due24hAt = change + 2 d, dueAt = change + 3 d. */
    @Test
    public void wf06_due24hReached_onePost_nextTickNone() {
        Harness h = new Harness();
        Instant change = NOW.minus(Duration.ofDays(2)).plus(Duration.ofMinutes(5));   // due24h in 5 min
        app.lightscout.domain.model.Entry site = treatment("s1", change, EntryType.SITE_CHANGE);
        h.entries.put(site);
        // the server keeps the change (the daily SY-4 reconcile runs when the clock passes 03:00)
        h.gateway.entriesWindow = (f, t, l) -> app.lightscout.usecases.fakes.Fakes.page(true,
                !change.isBefore(f) && change.isBefore(t) ? List.of(site) : List.of());
        h.tick();
        assertTrue(h.reminders.posted.isEmpty());
        ReminderState st = h.reminderState.rows.get(ConsumableKind.SITE);
        assertEquals(change.plus(Duration.ofDays(2)), st.due24hAt());
        assertEquals(change.plus(Duration.ofDays(3)), st.dueAt());
        h.clock.advanceMinutes(6);
        h.tick();
        assertEquals(1, h.reminders.posted.size());
        assertEquals(ReminderStage.DUE_TOMORROW, h.reminders.posted.get(0).stage());
        assertEquals("s1", h.reminders.posted.get(0).basedOnEntryId());
        assertEquals(3, h.reminders.posted.get(0).lifetimeDays());
        h.clock.advanceMinutes(5);
        h.tick();
        assertEquals(1, h.reminders.posted.size());
        h.clock.now = change.plus(Duration.ofDays(3));
        h.tick();
        assertEquals(2, h.reminders.posted.size());
        assertEquals(ReminderStage.DUE_NOW, h.reminders.posted.get(1).stage());
    }

    @Test
    public void wf06_newSiteChange_cancelsOldAndPlansNew() {
        Harness h = new Harness();
        Instant old = NOW.minus(Duration.ofDays(2)).minus(Duration.ofHours(1));
        h.entries.put(treatment("s1", old, EntryType.SITE_CHANGE));
        h.tick();
        assertEquals(1, h.reminders.posted.size());
        h.entries.put(treatment("s2", NOW.plusSeconds(30), EntryType.SITE_CHANGE));
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(List.of(ConsumableKind.SITE), h.reminders.cancelled);
        ReminderState st = h.reminderState.rows.get(ConsumableKind.SITE);
        assertEquals("s2", st.basedOnEntryId());
        assertEquals(NOW.plusSeconds(30).plus(Duration.ofDays(3)), st.dueAt());
        assertEquals(1, h.reminders.posted.size());
    }

    @Test
    public void r70_firstSight_atMostOneNotice_dueNowOnlyIfLessThan24hPast() {
        Harness h = new Harness();   // sensor lifetime 10 d
        h.entries.put(treatment("n1", NOW.minus(Duration.ofDays(10)).minus(Duration.ofHours(3)),
                EntryType.SENSOR_CHANGE));
        h.tick();
        assertEquals(1, h.reminders.posted.size());
        assertEquals(ReminderStage.DUE_NOW, h.reminders.posted.get(0).stage());

        Harness h2 = new Harness();
        h2.entries.put(treatment("n2", NOW.minus(Duration.ofDays(12)), EntryType.SENSOR_CHANGE));
        h2.tick();
        assertTrue("dueAt more than 24 h ago → no notice", h2.reminders.posted.isEmpty());
        assertTrue(h2.reminderState.rows.get(ConsumableKind.SENSOR).notifiedAt());
        h2.clock.advanceMinutes(5);
        h2.tick();
        assertTrue(h2.reminders.posted.isEmpty());
    }

    @Test
    public void d022_remindersIgnoreAlarmsSwitch_andStoreFailureDoesNotStopTick() {
        Harness h = new Harness();
        h.reminderState.fail = true;
        h.entries.put(treatment("s1", NOW.minus(Duration.ofDays(2)).minus(Duration.ofHours(1)),
                EntryType.SITE_CHANGE));
        // IR-40: an IF-63 failure marks the tick STORAGE_ERROR, the tick still completes (SR-37)
        assertEquals(app.lightscout.domain.port.FetchOutcome.STORAGE_ERROR, h.tick().outcome());
        assertTrue(h.logger.events.contains("SYNC_FAIL:STORAGE_ERROR:STORAGE"));
        assertEquals(1, h.alarmController.applied.size());
        assertTrue(h.reminders.posted.isEmpty());   // persisted before posting: nothing posted without state
    }

    @Test
    public void wf07_newPumpAlert30MinOld_posted_sameIdNextTick_none() {
        Harness h = new Harness();
        h.entries.put(treatment("pa1", NOW.minus(Duration.ofMinutes(30)), EntryType.PUMP_ALERT));
        h.tick();
        assertEquals(1, h.pumpAlerts.posted.size());
        assertEquals("pa1", h.pumpAlerts.posted.get(0).id());
        assertTrue(h.notified.rows.containsKey("pa1"));
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(1, h.pumpAlerts.posted.size());
    }

    @Test
    public void wf07_pumpAlert2hOld_notPosted() {
        Harness h = new Harness();
        h.entries.put(treatment("pa2", NOW.minus(Duration.ofHours(2)), EntryType.PUMP_ALERT));
        h.tick();
        assertTrue(h.pumpAlerts.posted.isEmpty());
        assertTrue(h.notified.rows.isEmpty());
    }

    @Test
    public void wf07_reevaluateAlsoChecksPumpAlerts() {
        Harness h = new Harness();
        h.entries.put(treatment("pa3", NOW.minus(Duration.ofMinutes(1)), EntryType.PUMP_ALERT));
        h.cycle.reevaluate();
        assertEquals(1, h.pumpAlerts.posted.size());
    }
}
