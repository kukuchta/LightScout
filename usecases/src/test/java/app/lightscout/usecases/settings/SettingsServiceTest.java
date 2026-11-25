package app.lightscout.usecases.settings;

import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.entry.ValidationField;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.entry.Violation;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.usecases.common.SettingsEditor;
import app.lightscout.usecases.common.SettingsWriter;
import app.lightscout.usecases.fakes.Harness;
import app.lightscout.usecases.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** SET-1 (S-04 rules at their boundaries, code + parameters), SET-2 / ASR-17, R-30 lock + read-your-write, listener. */
public class SettingsServiceTest {

    @org.junit.Test
    public void themeModeIsStoredWithoutReevaluatingAlarmsAndKeepsOtherFields() {
        assertEquals(app.lightscout.domain.settings.ThemeMode.SYSTEM, store.value.themeMode());
        assertTrue(update(SettingKey.THEME_MODE, 2).valid());
        assertEquals(app.lightscout.domain.settings.ThemeMode.DARK, store.value.themeMode());
        assertTrue(update(SettingKey.THEME_MODE, 1).valid());
        assertEquals(app.lightscout.domain.settings.ThemeMode.LIGHT, store.value.themeMode());
        assertEquals(0, monitorControl.reevaluations);
        assertTrue(update(SettingKey.STATISTICS_CARD, 0).valid());
        assertEquals("another setting keeps the theme", app.lightscout.domain.settings.ThemeMode.LIGHT,
                store.value.themeMode());
        assertTrue(update(SettingKey.LOW_SNOOZE, 20).valid());
        assertEquals(app.lightscout.domain.settings.ThemeMode.LIGHT, store.value.themeMode());
        assertEquals(1, monitorControl.reevaluations);
    }

    @org.junit.Test
    public void statisticsCardIsStoredWithoutReevaluatingAlarms() {
        assertTrue(update(SettingKey.STATISTICS_CARD, 0).valid());
        assertFalse(store.value.statisticsCardOnHome());
        assertEquals(0, monitorControl.reevaluations);
        assertTrue(update(SettingKey.STATISTICS_CARD, 1).valid());
        assertTrue(store.value.statisticsCardOnHome());
        assertEquals(0, monitorControl.reevaluations);
        assertTrue(update(SettingKey.LOW_SNOOZE, 20).valid());
        assertEquals(1, monitorControl.reevaluations);
        assertTrue("another setting keeps the card value", store.value.statisticsCardOnHome());
    }

    private Fakes.FakeSettingsStore store;
    private Fakes.FakeMonitorControl monitorControl;
    private DefaultSettingsService service;

    @Before
    public void setUp() {
        store = new Fakes.FakeSettingsStore();
        monitorControl = new Fakes.FakeMonitorControl();
        service = new DefaultSettingsService(store, new SettingsWriter(store), monitorControl);
    }

    private ValidationResult update(SettingKey key, int value) {
        return service.update(new SettingChange(key, value, null));
    }

    private static Violation only(ValidationResult r) {
        assertEquals(1, r.violations().size());
        assertEquals(ValidationField.SETTING, r.violations().get(0).field());
        return r.violations().get(0);
    }

    private void assertInvalid(ValidationResult r, ValidationCode code, List<Integer> params) {
        Violation v = only(r);
        assertEquals(code, v.code());
        assertEquals(params, v.params());
    }

    private void withSettings(int urgentLow, int low, int high) {
        Settings d = Settings.defaults();
        store.value = new Settings(true, true, low, urgentLow, true, high, d.noDataMinutes(), d.lowSnoozeMin(),
                d.urgentLowSnoozeMin(), d.highSnoozeMin(), d.noDataSnoozeMin(), d.siteLifetimeDays(),
                d.sensorLifetimeDays(), d.targetRange());
    }

    @Test
    public void set1_urgentLowBoundaries() {
        // defaults: Low 70
        assertInvalid(update(SettingKey.URGENT_LOW_THRESHOLD, 39), ValidationCode.URGENT_LOW_RANGE, List.of(70));
        assertTrue(update(SettingKey.URGENT_LOW_THRESHOLD, 40).valid());
        withSettings(55, 90, 250);
        assertTrue(update(SettingKey.URGENT_LOW_THRESHOLD, 80).valid());          // 80 < Low 90
        withSettings(55, 80, 250);
        assertInvalid(update(SettingKey.URGENT_LOW_THRESHOLD, 80), ValidationCode.URGENT_LOW_RANGE, List.of(80));
        assertInvalid(update(SettingKey.URGENT_LOW_THRESHOLD, 81), ValidationCode.URGENT_LOW_RANGE, List.of(80));
    }

    @Test
    public void set1_lowBoundaries() {
        withSettings(40, 70, 400);
        assertTrue(update(SettingKey.LOW_THRESHOLD, 41).valid());                   // newly allowed below 55
        assertTrue(update(SettingKey.LOW_THRESHOLD, 120).valid());
        assertTrue(update(SettingKey.LOW_THRESHOLD, 121).valid());                  // newly allowed above 120
        assertTrue(update(SettingKey.LOW_THRESHOLD, 399).valid());
        assertInvalid(update(SettingKey.LOW_THRESHOLD, 400), ValidationCode.LOW_RANGE, List.of(40, 400));
        assertInvalid(update(SettingKey.LOW_THRESHOLD, 40), ValidationCode.LOW_RANGE, List.of(40, 400));
    }

    @Test
    public void set1_highBoundaries() {
        withSettings(55, 70, 250);
        assertTrue(update(SettingKey.HIGH_THRESHOLD, 71).valid());                  // newly allowed below 120
        assertInvalid(update(SettingKey.HIGH_THRESHOLD, 70), ValidationCode.HIGH_RANGE, List.of(70));
        assertTrue(update(SettingKey.HIGH_THRESHOLD, 400).valid());
        assertInvalid(update(SettingKey.HIGH_THRESHOLD, 401), ValidationCode.HIGH_RANGE, List.of(70));
    }

    @Test
    public void set1_durationsAndLifetimes() {
        assertInvalid(update(SettingKey.NO_DATA_MINUTES, 9), ValidationCode.NO_DATA_RANGE, List.of());
        assertTrue(update(SettingKey.NO_DATA_MINUTES, 10).valid());
        assertTrue(update(SettingKey.NO_DATA_MINUTES, 60).valid());
        assertInvalid(update(SettingKey.NO_DATA_MINUTES, 61), ValidationCode.NO_DATA_RANGE, List.of());
        for (SettingKey k : List.of(SettingKey.LOW_SNOOZE, SettingKey.URGENT_LOW_SNOOZE, SettingKey.HIGH_SNOOZE,
                SettingKey.NO_DATA_SNOOZE)) {
            assertInvalid(update(k, 4), ValidationCode.SNOOZE_RANGE, List.of());
            assertTrue(update(k, 5).valid());
            assertTrue(update(k, 120).valid());
            assertInvalid(update(k, 121), ValidationCode.SNOOZE_RANGE, List.of());
        }
        assertInvalid(update(SettingKey.SITE_LIFETIME, 0), ValidationCode.SITE_LIFETIME_RANGE, List.of());
        assertTrue(update(SettingKey.SITE_LIFETIME, 1).valid());
        assertTrue(update(SettingKey.SITE_LIFETIME, 7).valid());
        assertInvalid(update(SettingKey.SITE_LIFETIME, 8), ValidationCode.SITE_LIFETIME_RANGE, List.of());
        assertTrue(update(SettingKey.SENSOR_LIFETIME, 30).valid());
        assertInvalid(update(SettingKey.SENSOR_LIFETIME, 31), ValidationCode.SENSOR_LIFETIME_RANGE, List.of());
    }

    @Test
    public void set1_targetRange() {
        assertTrue(service.update(new SettingChange(SettingKey.TARGET_RANGE, 54, 120)).valid());
        assertInvalid(service.update(new SettingChange(SettingKey.TARGET_RANGE, 141, 150)),
                ValidationCode.TARGET_RANGE, List.of());                           // From > 140
        assertInvalid(service.update(new SettingChange(SettingKey.TARGET_RANGE, 100, 100)),
                ValidationCode.TARGET_RANGE, List.of());                           // From not < To
        assertInvalid(service.update(new SettingChange(SettingKey.TARGET_RANGE, 54, 251)),
                ValidationCode.TARGET_RANGE, List.of());
    }

    @Test
    public void set2_validChange_committedBeforeReturn_readYourWrite_thenReevaluateOnce() {
        List<Integer> reevaluatedAfterPut = new ArrayList<>();
        monitorControl.onReevaluate = () -> reevaluatedAfterPut.add(store.puts.size());
        ValidationResult r = update(SettingKey.LOW_THRESHOLD, 80);
        assertTrue(r.valid());
        assertEquals(1, store.puts.size());               // committed on the calling thread before return
        assertEquals(80, service.get().lowMgdl());        // read-your-write
        assertEquals(1, monitorControl.reevaluations);
        assertEquals(List.of(1), reevaluatedAfterPut);    // ASR-17: re-evaluation requested after the commit
    }

    @Test
    public void set2_invalidChange_nothingStored_noReevaluate() {
        assertFalse(update(SettingKey.LOW_THRESHOLD, 250).valid());   // equal to the current High threshold
        assertTrue(store.puts.isEmpty());
        assertEquals(0, monitorControl.reevaluations);
    }

    @Test
    public void set2_targetRangeAndSnoozeApplied() {
        service.update(new SettingChange(SettingKey.TARGET_RANGE, 80, 160));
        update(SettingKey.HIGH_SNOOZE, 90);
        assertEquals(new TargetRange(80, 160), store.value.targetRange());
        assertEquals(90, store.value.highSnoozeMin());
        assertEquals(2, monitorControl.reevaluations);
    }

    @Test
    public void lock_serialisesWrites_crossFieldRulesHoldUnderConcurrency() throws Exception {
        for (int i = 0; i < 50; i++) {
            withSettings(55, 70, 250);
            CountDownLatch start = new CountDownLatch(1);
            Thread a = new Thread(() -> {
                await(start);
                update(SettingKey.LOW_THRESHOLD, 120);
            });
            Thread b = new Thread(() -> {
                await(start);
                update(SettingKey.HIGH_THRESHOLD, 120);
            });
            a.start();
            b.start();
            start.countDown();
            a.join();
            b.join();
            // whichever ran second was validated against the first one's committed value
            assertTrue(store.value.lowMgdl() < store.value.highMgdl());
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void storeFailure_neverThrows_nothingApplied() {
        store.fail = true;
        assertEquals(Settings.defaults(), service.get());
        assertTrue(update(SettingKey.LOW_THRESHOLD, 80).valid());   // validated against defaults, not stored
        assertTrue(store.puts.isEmpty());
        assertEquals(0, monitorControl.reevaluations);
    }

    @Test
    public void listener_firesOnStoredChange_includingSwitches_onWriterThread() {
        List<Settings> seen = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        service.addListener(s -> {
            seen.add(s);
            threads.add(Thread.currentThread());
        });
        update(SettingKey.NO_DATA_MINUTES, 30);
        // an IF-25 switch writes ST-02 directly (on the monitor executor)
        Fakes.QueueExecutor monitorExecutor = new Fakes.QueueExecutor();
        monitorExecutor.execute(() -> store.put(SettingsEditor.withAlarmsEnabled(store.value, false)));
        monitorExecutor.runAll();
        assertEquals(2, seen.size());
        assertEquals(30, seen.get(0).noDataMinutes());
        assertFalse(seen.get(1).alarmsEnabled());
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), threads);
    }

    @Test
    public void ir44_concurrentSwitchAndThreshold_sharedWriter_noLostUpdateEitherWay() throws Exception {
        for (int i = 0; i < 6; i++) {
            Harness h = new Harness();
            DefaultSettingsService shared = new DefaultSettingsService(h.settings, h.settingsWriter, h.monitorControl);
            h.settings.getDelayMs = 20;   // both read-modify-writes overlap without the shared lock
            boolean switchFirst = i % 2 == 0;
            h.actions.setAlarmsEnabled(false);   // queued on the monitor executor (IF-25)
            Thread threshold = new Thread(() -> shared.update(new SettingChange(SettingKey.LOW_THRESHOLD, 80, null)));
            Thread monitor = new Thread(h.monitorExecutor::runAll);
            if (switchFirst) {
                monitor.start();
                threshold.start();
            } else {
                threshold.start();
                monitor.start();
            }
            threshold.join();
            monitor.join();
            assertEquals(80, h.settings.value.lowMgdl());
            assertFalse(h.settings.value.alarmsEnabled());
        }
    }
}
