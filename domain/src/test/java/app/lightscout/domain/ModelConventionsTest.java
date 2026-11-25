package app.lightscout.domain;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmDecision;
import app.lightscout.domain.alarm.AlarmInputs;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.model.BasalSegment;
import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.status.StatusSnapshot;

import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TST-01 T-1 (all model instants are {@link Instant}), TST-12 (no Android on the classpath) and the conventions of
 * CP-00A §0 / R-20 (collections copied, factories).
 */
public class ModelConventionsTest {

    private static final List<Class<? extends Record>> RECORDS = List.of(
            Reading.class, Entry.class, DeviceStatus.class, Profile.class, BasalSegment.class, Change.class,
            AlarmTypeState.class, AlarmState.class, AlarmView.class, AlarmInputs.class, AlarmDecision.class,
            DataLossStatus.class, CollectionCursor.class, MonitorState.class, Period.class, ConsumableStatus.class,
            ReminderNotice.class, ReminderState.class, EntryDraft.class, StatusSnapshot.class);

    private static final Set<Class<?>> FORBIDDEN_TIME_TYPES = Set.of(java.util.Date.class, java.util.Calendar.class,
            java.time.LocalDateTime.class, java.time.LocalDate.class, java.time.ZonedDateTime.class,
            java.time.OffsetDateTime.class);

    @Test
    public void t1_noLocalOrLegacyTimeTypesInModel_andTimeComponentsAreInstants() {
        List<String> timeLike = new ArrayList<>();
        for (Class<? extends Record> type : RECORDS) {
            for (RecordComponent c : type.getRecordComponents()) {
                assertFalse(type.getSimpleName() + "." + c.getName(), FORBIDDEN_TIME_TYPES.contains(c.getType()));
                String name = c.getName();
                boolean instantNamed = name.equals("time") || name.equals("now") || name.equals("start")
                        || name.equals("end") || name.endsWith("At") || name.endsWith("Since")
                        || name.endsWith("Until") || name.endsWith("Check") || name.endsWith("Time");
                if (instantNamed && !c.getType().isPrimitive()) {
                    timeLike.add(type.getSimpleName() + "." + name);
                    assertEquals(type.getSimpleName() + "." + name, Instant.class, c.getType());
                }
            }
        }
        assertTrue(timeLike.contains("Reading.time"));
        assertTrue(timeLike.contains("Entry.time"));
        assertTrue(timeLike.contains("Period.start"));
    }

    @Test
    public void tst12_noAndroidClassesOnTheClasspath() {
        try {
            Class.forName("android.os.Bundle");
            fail("android.os.Bundle must not be loadable from :domain");
        } catch (ClassNotFoundException expected) {
            // JDK only (R-1)
        }
    }

    @Test
    public void r71_keepRingingOnly_keepsRingingTypeAndVolume_dropsOthers() {
        Map<AlarmType, AlarmTypeState> perType = new EnumMap<>(AlarmType.class);
        AlarmTypeState ringing = new AlarmTypeState(Instant.EPOCH, null, 2, Instant.EPOCH);
        perType.put(AlarmType.LOW, ringing);
        perType.put(AlarmType.HIGH, new AlarmTypeState(null, Instant.EPOCH.plusSeconds(600), 0, null));
        AlarmState kept = new AlarmState(AlarmType.LOW, perType, 7).keepRingingOnly();
        assertEquals(AlarmType.LOW, kept.activeType());
        assertEquals(Map.of(AlarmType.LOW, ringing), kept.perType());
        assertEquals(Integer.valueOf(7), kept.savedUserAlarmVolume());

        AlarmState idle = new AlarmState(null, perType, null).keepRingingOnly();
        assertNull(idle.activeType());
        assertTrue(idle.perType().isEmpty());
        assertEquals(AlarmState.empty(), AlarmState.empty().keepRingingOnly());
    }

    @Test
    public void monitorStateInitial() {
        MonitorState s = MonitorState.initial();
        assertNull(s.monitoringSince());
        assertNull(s.lastCheckCompleted());
        assertEquals(FetchOutcome.OK, s.lastOutcome());
        assertEquals(60, s.arrivalLagMedianSec());
        assertTrue(s.recentLagSec().isEmpty());
        assertTrue(s.cursors().isEmpty());
    }

    @Test
    public void r20_resultFactories() {
        Result<String> ok = Result.ok("v");
        assertTrue(ok.ok());
        assertEquals("v", ok.value());
        assertEquals(FetchOutcome.OK, ok.outcome());
        assertNull(ok.detailCode());

        Result<String> rejected = Result.fail(FetchOutcome.OK, DetailCode.PRECONDITION_412);
        assertFalse(rejected.ok());
        assertNull(rejected.value());
        assertEquals(FetchOutcome.OK, rejected.outcome());
        assertEquals("PRECONDITION_412", rejected.detailCode());
    }

    @Test
    public void collectionsAreUnmodifiableCopies() {
        List<AlarmCommand> source = new ArrayList<>();
        source.add(new AlarmCommand.Stop(AlarmType.LOW));
        AlarmDecision d = new AlarmDecision(source, null);
        source.clear();
        assertEquals(1, d.commands().size());
        try {
            d.commands().add(new AlarmCommand.Stop(AlarmType.HIGH));
            fail("must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // R-1 conventions
        }
        List<Reading> items = new ArrayList<>();
        Page<Reading> page = new Page<>(items, false);
        items.add(new Reading("x", Instant.EPOCH, 100, null, null));
        assertTrue(page.items().isEmpty());
    }

    @Test
    public void permissionsHas() {
        Permissions p = new Permissions(Map.of(NsCollection.TREATMENTS, "crud", NsCollection.ENTRIES, "r"));
        assertTrue(p.has(NsCollection.TREATMENTS, 'd'));
        assertTrue(p.has(NsCollection.ENTRIES, 'r'));
        assertFalse(p.has(NsCollection.ENTRIES, 'c'));
        assertFalse(p.has(NsCollection.PROFILE, 'r'));
    }

    @Test
    public void eventCodeLevels() {
        assertEquals(EventCode.Level.WARN, EventCode.SYNC_FAIL.level());
        assertEquals(EventCode.Level.WARN, EventCode.JWT_REFRESH_FAIL.level());
        assertEquals(EventCode.Level.WARN, EventCode.FGS_START_DENIED.level());
        assertEquals(EventCode.Level.WARN, EventCode.EXACT_ALARM_DENIED.level());
        assertEquals(EventCode.Level.WARN, EventCode.REDIRECT_REFUSED.level());
        assertEquals(EventCode.Level.WARN, EventCode.RESPONSE_TOO_LARGE.level());
        assertEquals(EventCode.Level.WARN, EventCode.BOOT_NOT_HANDLED.level());
        assertEquals(EventCode.Level.ERROR, EventCode.STORAGE_ERROR.level());
        assertEquals(EventCode.Level.ERROR, EventCode.CRASH.level());
        assertEquals(EventCode.Level.INFO, EventCode.SYNC_OK.level());
        assertEquals(EventCode.Level.INFO, EventCode.ALARM_RING.level());
    }

    @Test
    public void alarmTypePriorityOrder() {
        assertEquals(List.of(AlarmType.URGENT_LOW, AlarmType.LOW, AlarmType.NO_DATA, AlarmType.NOT_RUNNING,
                AlarmType.HIGH), List.of(AlarmType.values()));
    }
}
