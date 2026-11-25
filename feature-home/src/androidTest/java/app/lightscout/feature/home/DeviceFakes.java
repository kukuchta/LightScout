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
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.status.StatusProjector;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Device-test fakes for S-01 (no Mockito — CP-00 §2). Listener calls arrive on a single background thread like
 * {@code monitorExecutor} (CP-00A R-25), so the {@code :ui-core} LiveData adapter path is exercised.
 */
final class DeviceFakes {

    private DeviceFakes() {
    }

    /** IF-15 on the wall clock (snapshots are built relative to it). */
    static final class SystemClockFake implements Clock {

        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public long elapsedRealtimeMs() {
            return android.os.SystemClock.elapsedRealtime();
        }

        @Override
        public ZoneId zone() {
            return ZoneId.systemDefault();
        }
    }

    /**
     * IF-21: settable snapshot; refresh / retry counted. {@code refresh()} also re-publishes the current snapshot,
     * as the production repository re-projects and publishes (so a snapshot set while stopped reaches the UI).
     */
    static final class FakeStatusRepository implements StatusRepository {

        final List<StatusListener> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger refreshes = new AtomicInteger();
        final AtomicInteger retries = new AtomicInteger();
        private final ExecutorService monitor = Executors.newSingleThreadExecutor();
        volatile StatusSnapshot snapshot = new Snap().build();

        @Override
        public StatusSnapshot current() {
            return snapshot;
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
            refreshes.incrementAndGet();
            publish(snapshot);
        }

        @Override
        public void retry() {
            retries.incrementAndGet();
        }

        /** Publishes a new snapshot to every listener on the fake monitor thread. */
        void publish(StatusSnapshot s) {
            snapshot = s;
            monitor.execute(() -> {
                for (StatusListener l : listeners) {
                    l.onStatus(s);
                }
            });
        }
    }

    /** IF-25 recording calls. */
    static final class FakeAlarmActions implements AlarmActions {

        final List<String> calls = new CopyOnWriteArrayList<>();

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

    /** Snapshot builder relative to the wall clock; {@code bannerTop} by the real CP-02 rule. */
    static final class Snap {

        private final Instant now = Instant.now();
        Reading latest = reading(142, Duration.ofMinutes(3).plusSeconds(10), Trend.FORTY_FIVE_UP);
        DataLossStatus dataLoss = new DataLossStatus(DataLossKind.NONE, null, null);
        FetchOutcome lastOutcome = FetchOutcome.OK;
        FetchOutcome connectionProblem;
        Instant lastCheckCompleted = now.plusSeconds(1);
        boolean online = true;
        boolean fetching;
        boolean alarmsEnabled = true;
        boolean lowEnabled = true;
        boolean highEnabled = true;
        Integer iobCenti;
        int readinessMissing;
        ConsumableStatus siteStatus = none(ConsumableKind.SITE);
        ConsumableStatus sensorStatus = none(ConsumableKind.SENSOR);

        Reading reading(int sgv, Duration age, Trend trend) {
            return new Reading("r-" + sgv, now.minus(age), sgv, trend, "test");
        }

        Snap noReading() {
            latest = null;
            return this;
        }

        Snap firstUse() {
            dataLoss = new DataLossStatus(DataLossKind.FIRST_USE, null, null);
            return this;
        }

        Snap dataLoss(Duration since) {
            dataLoss = new DataLossStatus(DataLossKind.DATA_LOSS, DataLossCause.UPLOADER, now.minus(since));
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

        Snap cachedOnly() {
            lastCheckCompleted = now.minusSeconds(3600);
            return this;
        }

        StatusSnapshot build() {
            Instant next = latest == null ? null : latest.time().plus(Duration.ofMinutes(5));
            return new StatusSnapshot(latest, latest == null ? null : Duration.between(latest.time(), now), next,
                    next != null && now.isAfter(next), dataLoss, lastOutcome, connectionProblem,
                    lastCheckCompleted, lastCheckCompleted, online, fetching, true,
                    alarmsEnabled, lowEnabled, highEnabled, 55, 70, 250, 20,
                    null, iobCenti, siteStatus, sensorStatus, readinessMissing,
                    StatusProjector.bannerTop(alarmsEnabled, lowEnabled, highEnabled, readinessMissing,
                            lastOutcome, connectionProblem, dataLoss, online, latest),
                    now);
        }

        private static ConsumableStatus none(ConsumableKind kind) {
            return new ConsumableStatus(kind, ConsumableState.NONE_FOUND, null, null, null, null);
        }

        private ConsumableStatus consumable(ConsumableKind kind, ConsumableState state, Duration age) {
            Instant changed = age == null ? null : now.minus(age);
            // Home derives warning/expired from the due instants, so the fake must carry them.
            Instant due24h = state == ConsumableState.CHANGE_SOON ? now.minus(Duration.ofHours(1))
                    : state == ConsumableState.EXPIRED ? now.minus(Duration.ofHours(25))
                    : changed == null ? null : now.plus(Duration.ofDays(1));
            Instant due = state == ConsumableState.CHANGE_SOON ? now.plus(Duration.ofHours(23))
                    : state == ConsumableState.EXPIRED ? now.minus(Duration.ofHours(1))
                    : changed == null ? null : now.plus(Duration.ofDays(2));
            return new ConsumableStatus(kind, state, changed, age, due24h, due);
        }
    }
}
