package app.lightscout.feature.home;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.status.StatusProjector;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fakes for the S-01 JVM tests (no Mockito — CP-00 §2).
 */
final class Fakes {

    /** Fixed test instant. */
    static final Instant T0 = Instant.parse("2026-10-16T12:00:00Z");

    private Fakes() {
    }

    /** IF-15 with a settable instant. */
    static final class FakeClock implements Clock {

        Instant now = T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return now.toEpochMilli();
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("Europe/Warsaw");
        }
    }

    /** Manual {@link HomeModel.Timer}: records pending tasks with their delays; the test runs them. */
    static final class FakeTimer implements HomeModel.Timer {

        final java.util.Map<Runnable, Long> pending = new java.util.LinkedHashMap<>();

        @Override
        public void postDelayed(Runnable task, long delayMs) {
            pending.put(task, delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            pending.remove(task);
        }

        /** Runs every pending task posted with the given delay. */
        void runAll(long delayMs) {
            for (Runnable r : new ArrayList<>(pending.keySet())) {
                if (pending.get(r) != null && pending.get(r) == delayMs) {
                    pending.remove(r);
                    r.run();
                }
            }
        }

        boolean has(long delayMs) {
            return pending.containsValue(delayMs);
        }
    }

    /** IF-21 recording refresh / retry and listeners. */
    static final class FakeStatusRepository implements StatusRepository {

        StatusSnapshot current;
        final List<StatusListener> listeners = new ArrayList<>();
        int refreshes;
        int retries;

        FakeStatusRepository(StatusSnapshot current) {
            this.current = current;
        }

        @Override
        public StatusSnapshot current() {
            return current;
        }

        @Override
        public void addListener(StatusListener l) {
            listeners.add(l);
        }

        @Override
        public void removeListener(StatusListener l) {
            listeners.remove(l);
        }

        @Override
        public void refresh() {
            refreshes++;
        }

        @Override
        public void retry() {
            retries++;
        }
    }

    /** IF-25 recording calls as „method:arg:on” strings. */
    static final class FakeAlarmActions implements AlarmActions {

        final List<String> calls = new ArrayList<>();

        @Override
        public void snooze(AlarmType t) {
            calls.add("snooze:" + t);
        }

        @Override
        public void setAlarmsEnabled(boolean on) {
            calls.add("alarms:" + on);
        }

        @Override
        public void setAlarmTypeEnabled(AlarmType t, boolean on) {
            calls.add("type:" + t + ":" + on);
        }
    }

    /** IF-29 recording calls. */
    static final class RecordingNavigator implements Navigator {

        final List<String> calls = new ArrayList<>();

        @Override
        public void openEntry(String identifierOrNull) {
            calls.add("entry:" + identifierOrNull);
        }

        @Override
        public void openNewEntry(app.lightscout.domain.model.EntryType initialType) {
            calls.add("newEntry:" + initialType);
        }

        @Override
        public void openStatistics() {
            calls.add("statistics");
        }

        @Override
        public void openSettings() {
            calls.add("settings");
        }

        @Override
        public void openConnection(ConnectionMode mode) {
            calls.add("connection:" + mode);
        }

        @Override
        public void openReadiness(ReadinessMode mode) {
            calls.add("readiness:" + mode);
        }

        @Override
        public void finishOnboarding() {
            calls.add("finishOnboarding");
        }

        @Override
        public void finishWithResult(UiMessage message) {
            calls.add("result:" + message);
        }
    }

    /**
     * Snapshot builder; {@code bannerTop} is derived with the real CP-02 rule
     * ({@link StatusProjector#bannerTop}) so the tests follow the projection S-01 receives.
     */
    static final class Snap {

        Reading latest = reading(120, Duration.ofMinutes(2), Trend.FLAT);
        Instant now = T0;
        Boolean overdue;
        DataLossStatus dataLoss = new DataLossStatus(DataLossKind.NONE, null, null);
        FetchOutcome lastOutcome = FetchOutcome.OK;
        FetchOutcome connectionProblem;
        Instant lastCheckCompleted = T0.minusSeconds(30);
        boolean online = true;
        boolean fetching;
        boolean alarmsEnabled = true;
        boolean lowEnabled = true;
        boolean highEnabled = true;
        int urgentLow = 55;
        int low = 70;
        int high = 250;
        int noDataMinutes = 20;
        app.lightscout.domain.alarm.AlarmType ringing;
        java.util.Map<app.lightscout.domain.alarm.AlarmType, Instant> snoozes = java.util.Map.of();
        app.lightscout.domain.status.CheckCycle cycle;

        Snap cycle(app.lightscout.domain.status.CheckCycle value) {
            cycle = value;
            return this;
        }
        Integer iobCenti;
        int readinessMissing;
        ConsumableStatus siteStatus = none(ConsumableKind.SITE);
        ConsumableStatus sensorStatus = none(ConsumableKind.SENSOR);

        static Reading reading(int sgv, Duration age, Trend trend) {
            return new Reading("r-" + sgv, T0.minus(age), sgv, trend, "test");
        }

        Snap latest(Reading r) {
            latest = r;
            return this;
        }

        Snap noReading() {
            latest = null;
            return this;
        }

        Snap firstUse() {
            dataLoss = new DataLossStatus(DataLossKind.FIRST_USE, null, null);
            return this;
        }

        Snap dataLoss(DataLossCause cause, Instant since) {
            dataLoss = new DataLossStatus(DataLossKind.DATA_LOSS, cause, since);
            return this;
        }

        Snap outcome(FetchOutcome o) {
            lastOutcome = o;
            return this;
        }

        Snap problem(FetchOutcome o) {
            connectionProblem = o;
            return this;
        }

        Snap site(ConsumableState state, Duration age) {
            siteStatus = consumable(ConsumableKind.SITE, state, age);
            return this;
        }

        Snap sensor(ConsumableState state, Duration age) {
            sensorStatus = consumable(ConsumableKind.SENSOR, state, age);
            return this;
        }

        StatusSnapshot build() {
            Instant next = latest == null ? null : latest.time().plus(Duration.ofMinutes(5));
            boolean isOverdue = overdue != null ? overdue : next != null && now.isAfter(next);
            Duration age = latest == null ? null : Duration.between(latest.time(), now);
            return new StatusSnapshot(latest, age, next, isOverdue, dataLoss, lastOutcome, connectionProblem,
                    lastCheckCompleted, lastCheckCompleted, online, fetching, true,
                    alarmsEnabled, lowEnabled, highEnabled, urgentLow, low, high, noDataMinutes,
                    ringing, iobCenti, siteStatus, sensorStatus, readinessMissing,
                    StatusProjector.bannerTop(alarmsEnabled, lowEnabled, highEnabled, readinessMissing,
                            lastOutcome, connectionProblem, dataLoss, online, latest),
                    now, snoozes, null, true, cycle);
        }

        private static ConsumableStatus none(ConsumableKind kind) {
            return new ConsumableStatus(kind, ConsumableState.NONE_FOUND, null, null, null, null);
        }

        private static ConsumableStatus consumable(ConsumableKind kind, ConsumableState state, Duration age) {
            Instant changed = age == null ? null : T0.minus(age);
            int lifetimeDays = kind == ConsumableKind.SITE ? 3 : 10;
            Instant due24hAt = changed == null ? null
                    : changed.plus(Duration.ofDays(lifetimeDays - 1L));
            Instant dueAt = changed == null ? null : changed.plus(Duration.ofDays(lifetimeDays));
            return new ConsumableStatus(kind, state, changed, age, due24hAt, dueAt);
        }
    }
}
