package app.lightscout.usecases.fakes;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.domain.port.ReadinessSource;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.domain.port.WriteAck;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.TickReason;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** Hand-written fakes for every port used by CP-02a (no Mockito — CP-00 §2). */
public final class Fakes {

    private Fakes() {
    }

    // ---------------------------------------------------------------- test data

    public static Reading reading(String id, Instant time, int sgv) {
        return new Reading(id, time, sgv, app.lightscout.domain.model.Trend.FLAT, "cgm");
    }

    public static Entry treatment(String id, Instant time, EntryType type) {
        return new Entry(id, EntryOrigin.TREATMENTS, type, time, null, null, null, null, null, null,
                "uploader", "xdrip", null, false, 1L);
    }

    public static Entry ownTreatment(String id, Instant time, EntryType type) {
        return new Entry(id, EntryOrigin.TREATMENTS, type, time, null, 10, null, null, null, null,
                "LightScout", "LightScout", null, true, 1L);
    }

    public static Entry mbg(String id, Instant time, int glucose) {
        return new Entry(id, EntryOrigin.ENTRIES_MBG, EntryType.BG_CHECK, time, glucose, null, null, null, null,
                null, null, null, null, false, 1L);
    }

    public static <T> Result<Page<T>> page(boolean complete, List<T> items) {
        return Result.ok(new Page<>(items, complete));
    }

    // ---------------------------------------------------------------- clock / executor / logger

    public static final class FakeClock implements Clock {
        public Instant now;
        public ZoneId zone = ZoneOffset.UTC;
        /** When set, the milliseconds since the system start (otherwise the epoch milliseconds of {@link #now}). */
        public Long elapsedSinceBootMs;

        public FakeClock(Instant now) {
            this.now = now;
        }

        public void advanceMinutes(long m) {
            now = now.plusSeconds(60 * m);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return elapsedSinceBootMs != null ? elapsedSinceBootMs : now.toEpochMilli();
        }

        @Override
        public ZoneId zone() {
            return zone;
        }
    }

    /** Executor that only queues; {@link #runAll()} drains the queue on the test thread. */
    public static final class QueueExecutor implements java.util.concurrent.Executor {
        public final Deque<Runnable> queue = new ArrayDeque<>();
        public boolean running;

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        public int runAll() {
            int n = 0;
            while (!queue.isEmpty()) {
                running = true;
                try {
                    queue.poll().run();
                } finally {
                    running = false;
                }
                n++;
            }
            return n;
        }
    }

    public static final class FakeLogger implements Logger {
        public final List<String> events = new ArrayList<>();

        @Override
        public void event(EventCode code) {
            events.add(code.name());
        }

        @Override
        public void event(EventCode code, String detail) {
            events.add(code.name() + ":" + detail);
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
            events.add("CRASH");
        }

        /** Debug lines as {@code <tag> <message>}, kept apart from the event lines. */
        public final List<String> debug = new ArrayList<>();

        @Override
        public void debug(String tag, String message) {
            debug.add(tag + " " + message);
        }
    }

    // ---------------------------------------------------------------- gateway

    /** Window handler (from, to, limit) → result. */
    public interface Window<T> {
        Result<Page<T>> fetch(Instant from, Instant to, int limit);
    }

    /** History handler (collection, cursor, limit) → result. */
    public interface History {
        Result<Page<Change>> fetch(NsCollection c, long cursorMs, int limit);
    }

    public static final class FakeGateway implements NightscoutGateway {
        public final List<String> calls = new ArrayList<>();
        public final List<Instant[]> entriesWindowCalls = new ArrayList<>();
        public final List<Instant[]> readingsWindowCalls = new ArrayList<>();
        public final List<Long> readingsSinceCalls = new ArrayList<>();
        public final List<long[]> historyCalls = new ArrayList<>();

        public Supplier<Result<Map<NsCollection, Long>>> lastModified = () -> Result.ok(Map.of());
        public LongFunction<Result<Page<Reading>>> readingsSince = ms -> page(true, List.of());
        public LongFunction<Result<Page<Entry>>> meterBgSince = ms -> page(true, List.of());
        public Window<Reading> readingsWindow = (f, t, l) -> page(true, List.of());
        public Window<Entry> meterBgWindow = (f, t, l) -> page(true, List.of());
        public Window<Entry> entriesWindow = (f, t, l) -> page(true, List.of());
        /** Run after every window call (DCR-45 tests: lets a call cost elapsed time). */
        public Runnable afterWindowCall = () -> { };
        public Function<Instant, Result<List<DeviceStatus>>> deviceStatusSince = f -> Result.ok(List.of());
        public Supplier<Result<Profile>> latestProfile = () -> Result.ok(null);
        public History history = (c, cur, l) -> page(true, List.of());
        public Function<String, Result<ServerInfo>> version = u -> Result.ok(new ServerInfo("15.0.9", "3.0.4"));
        public Supplier<Result<Permissions>> permissions = () -> Result.ok(new Permissions(Map.of(
                NsCollection.ENTRIES, "r", NsCollection.TREATMENTS, "crud")));

        public long count(String method) {
            return calls.stream().filter(c -> c.equals(method)).count();
        }

        /** Calls other than lastModified. */
        public List<String> fetchCalls() {
            return calls.stream().filter(c -> !c.equals("lastModified")).toList();
        }

        @Override
        public void configure(String baseUrl, TokenSource tokens) {
            throw new AssertionError("configure is out of the sync cycle's scope");
        }

        @Override
        public Result<ServerInfo> version(String baseUrl) {
            calls.add("version");
            return version.apply(baseUrl);
        }

        @Override
        public Result<Permissions> permissions() {
            calls.add("permissions");
            return permissions.get();
        }

        @Override
        public Result<Map<NsCollection, Long>> lastModified() {
            calls.add("lastModified");
            return lastModified.get();
        }

        @Override
        public Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit) {
            calls.add("readingsSince");
            readingsSinceCalls.add(fromMsExclusive);
            return readingsSince.apply(fromMsExclusive);
        }

        @Override
        public Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit) {
            calls.add("meterBgSince");
            return meterBgSince.apply(fromMsExclusive);
        }

        @Override
        public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
            calls.add("readingsWindow");
            readingsWindowCalls.add(new Instant[] {from, to});
            Result<Page<Reading>> r = readingsWindow.fetch(from, to, limit);
            afterWindowCall.run();
            return r;
        }

        @Override
        public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
            calls.add("meterBgWindow");
            Result<Page<Entry>> r = meterBgWindow.fetch(from, to, limit);
            afterWindowCall.run();
            return r;
        }

        @Override
        public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
            calls.add("entriesWindow");
            entriesWindowCalls.add(new Instant[] {from, to});
            Result<Page<Entry>> r = entriesWindow.fetch(from, to, limit);
            afterWindowCall.run();
            return r;
        }

        @Override
        public Result<List<DeviceStatus>> deviceStatusSince(Instant from) {
            calls.add("deviceStatusSince");
            return deviceStatusSince.apply(from);
        }

        @Override
        public Result<Profile> latestProfile() {
            calls.add("latestProfile");
            return latestProfile.get();
        }

        @Override
        public Result<Page<Change>> history(NsCollection c, long cursorMs, int limit) {
            calls.add("history");
            historyCalls.add(new long[] {c.ordinal(), cursorMs, limit});
            return history.fetch(c, cursorMs, limit);
        }

        @Override
        public Result<WriteAck> create(Entry e) {
            throw new AssertionError("writes are out of the sync cycle's scope");
        }

        @Override
        public Result<Entry> get(String id) {
            throw new AssertionError("get is out of the sync cycle's scope");
        }

        @Override
        public Result<WriteAck> update(Entry e, long expectedSrvModifiedMs) {
            throw new AssertionError("writes are out of the sync cycle's scope");
        }

        @Override
        public Result<WriteAck> delete(String id) {
            throw new AssertionError("writes are out of the sync cycle's scope");
        }
    }

    // ---------------------------------------------------------------- stores

    /** Base for stores that can be told to fail. */
    public abstract static class FailingStore {
        public boolean fail;

        protected void check(String op) {
            if (fail) {
                throw new StorageException(op, new RuntimeException("fake"));
            }
        }
    }

    public static final class FakeReadingStore extends FailingStore implements ReadingStore {
        public final Map<String, Reading> rows = new LinkedHashMap<>();
        public final List<Instant[]> replaceWindowCalls = new ArrayList<>();
        public final List<Instant> evictCalls = new ArrayList<>();

        public void put(Reading... rs) {
            for (Reading r : rs) {
                rows.put(r.id(), r);
            }
        }

        @Override
        public void upsert(List<Reading> r) {
            check("readings.upsert");
            for (Reading x : r) {
                rows.put(x.id(), x);
            }
        }

        @Override
        public List<Reading> range(Instant from, Instant to) {
            check("readings.range");
            return rows.values().stream().filter(r -> !r.time().isBefore(from) && r.time().isBefore(to))
                    .sorted(Comparator.comparing(Reading::time)).toList();
        }

        @Override
        public Optional<Reading> latestNotAfter(Instant t) {
            check("readings.latest");
            return rows.values().stream().filter(r -> !r.time().isAfter(t)).max(Comparator.comparing(Reading::time));
        }

        @Override
        public void replaceWindow(Instant from, Instant to, List<Reading> set) {
            check("readings.replaceWindow");
            replaceWindowCalls.add(new Instant[] {from, to});
            Set<String> ids = new java.util.HashSet<>();
            set.forEach(r -> ids.add(r.id()));
            rows.values().removeIf(r -> !r.time().isBefore(from) && r.time().isBefore(to) && !ids.contains(r.id()));
            upsert(set);
        }

        @Override
        public void evictBefore(Instant t) {
            check("readings.evict");
            evictCalls.add(t);
            rows.values().removeIf(r -> r.time().isBefore(t));
        }

        @Override
        public void clear() {
            check("readings.clear");
            rows.clear();
        }
    }

    public static final class FakeEntryStore extends FailingStore implements EntryStore {
        public final Map<String, Entry> rows = new LinkedHashMap<>();
        public final List<String> deleted = new ArrayList<>();
        public final List<Object[]> replaceWindowCalls = new ArrayList<>();
        public final List<Instant> evictCalls = new ArrayList<>();
        public int upsertCalls;

        public void put(Entry... es) {
            for (Entry e : es) {
                rows.put(e.id(), e);
            }
        }

        @Override
        public void upsert(List<Entry> e) {
            check("entries.upsert");
            upsertCalls++;
            for (Entry x : e) {
                rows.put(x.id(), x);
            }
        }

        @Override
        public void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> set) {
            check("entries.replaceWindow");
            replaceWindowCalls.add(new Object[] {origin, from, to});
            Set<String> ids = new java.util.HashSet<>();
            set.forEach(e -> ids.add(e.id()));
            rows.values().removeIf(e -> e.origin() == origin && !e.time().isBefore(from) && e.time().isBefore(to)
                    && !ids.contains(e.id()));
            for (Entry x : set) {
                rows.put(x.id(), x);
            }
        }

        @Override
        public List<Entry> range(Instant from, Instant to) {
            check("entries.range");
            return rows.values().stream().filter(e -> !e.time().isBefore(from) && e.time().isBefore(to)).toList();
        }

        @Override
        public Optional<Entry> get(String id) {
            check("entries.get");
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Entry> latestOfType(EntryType t) {
            check("entries.latestOfType");
            return rows.values().stream().filter(e -> e.type() == t).max(Comparator.comparing(Entry::time));
        }

        @Override
        public void delete(String id) {
            check("entries.delete");
            deleted.add(id);
            rows.remove(id);
        }

        @Override
        public void evictBefore(Instant t) {
            check("entries.evict");
            evictCalls.add(t);
            rows.values().removeIf(e -> e.time().isBefore(t));
        }

        @Override
        public void clear() {
            rows.clear();
        }
    }

    public static final class FakeDeviceStatusStore extends FailingStore implements DeviceStatusStore {
        public final List<DeviceStatus> rows = new ArrayList<>();
        public final List<Profile> profiles = new ArrayList<>();
        public final List<Instant> evictCalls = new ArrayList<>();

        @Override
        public void upsert(List<DeviceStatus> d) {
            check("ds.upsert");
            rows.addAll(d);
        }

        @Override
        public Optional<DeviceStatus> latest() {
            check("ds.latest");
            return rows.stream().max(Comparator.comparing(DeviceStatus::time));
        }

        @Override
        public void saveProfile(Profile p) {
            check("ds.saveProfile");
            profiles.add(p);
        }

        @Override
        public Optional<Profile> latestProfile() {
            return profiles.isEmpty() ? Optional.empty() : Optional.of(profiles.get(profiles.size() - 1));
        }

        @Override
        public void evictBefore(Instant t) {
            check("ds.evict");
            evictCalls.add(t);
        }

        @Override
        public void clear() {
            rows.clear();
        }
    }

    public static final class FakeSettingsStore extends FailingStore implements SettingsStore {
        public volatile Settings value = Settings.defaults();
        public final List<Settings> puts = new ArrayList<>();
        public final List<Runnable> listeners = new ArrayList<>();
        public QueueExecutor requiredExecutor;
        /** Delay inside {@code get} to widen read-modify-write races (added for SS-06 IR-44). */
        public volatile long getDelayMs;

        @Override
        public Settings get() {
            check("settings.get");
            Settings v = value;
            if (getDelayMs > 0) {
                try {
                    Thread.sleep(getDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return v;
        }

        @Override
        public void put(Settings s) {
            if (requiredExecutor != null && !requiredExecutor.running) {
                throw new AssertionError("settings written off the monitor executor");
            }
            check("settings.put");
            value = s;
            puts.add(s);
            listeners.forEach(Runnable::run);
        }

        @Override
        public void addListener(Runnable onChange) {
            listeners.add(onChange);
        }
    }

    public static final class FakeMonitorStateStore implements MonitorStateStore {
        public MonitorState value = MonitorState.initial();
        public final List<MonitorState> puts = new ArrayList<>();
        public boolean failGet;
        public boolean failPut;
        public int gets;

        @Override
        public MonitorState get() {
            gets++;
            if (failGet) {
                throw new StorageException("monitor.get", new RuntimeException("fake"));
            }
            return value;
        }

        @Override
        public void put(MonitorState s) {
            if (failPut) {
                throw new StorageException("monitor.put", new RuntimeException("fake"));
            }
            if (gets == 0) {
                throw new AssertionError("put without a preceding get (R-34)");
            }
            value = s;
            puts.add(s);
        }

        @Override
        public void clear() {
            value = MonitorState.initial();
        }
    }

    public static final class FakeAlarmStateStore extends FailingStore implements AlarmStateStore {
        public AlarmState value = AlarmState.empty();
        public final List<AlarmState> puts = new ArrayList<>();
        public QueueExecutor requiredExecutor;
        public boolean failPut;

        @Override
        public AlarmState get() {
            check("alarm.get");
            return value;
        }

        @Override
        public void put(AlarmState s) {
            if (requiredExecutor != null && !requiredExecutor.running) {
                throw new AssertionError("alarm state written off the monitor executor");
            }
            check("alarm.put");
            if (failPut) {
                throw new StorageException("alarm.put", new RuntimeException("fake"));
            }
            value = s;
            puts.add(s);
        }

        @Override
        public void clear() {
            value = AlarmState.empty();
        }
    }

    public static final class FakeReminderStateStore extends FailingStore implements ReminderStateStore {
        public final Map<ConsumableKind, ReminderState> rows = new EnumMap<>(ConsumableKind.class);

        @Override
        public Optional<ReminderState> get(ConsumableKind k) {
            check("reminder.get");
            return Optional.ofNullable(rows.get(k));
        }

        @Override
        public void put(ReminderState s) {
            check("reminder.put");
            rows.put(s.kind(), s);
        }

        @Override
        public void clear() {
            rows.clear();
        }
    }

    public static final class FakeNotifiedAlertStore extends FailingStore implements NotifiedAlertStore {
        public final Map<String, Instant> rows = new HashMap<>();
        public final List<Instant> evictCalls = new ArrayList<>();

        @Override
        public boolean contains(String entryId) {
            check("notified.contains");
            return rows.containsKey(entryId);
        }

        @Override
        public void add(String entryId, Instant entryTime) {
            check("notified.add");
            rows.put(entryId, entryTime);
        }

        @Override
        public void evictBefore(Instant t) {
            evictCalls.add(t);
            rows.values().removeIf(i -> i.isBefore(t));
        }

        @Override
        public void clear() {
            rows.clear();
        }
    }

    public static final class FakeConnectionStore implements ConnectionStore {
        public Optional<String> baseUrl = Optional.of("https://ns.example.org");

        @Override
        public Optional<String> baseUrl() {
            return baseUrl;
        }

        @Override
        public void save(String url, char[] token) {
            throw new AssertionError("not used by the sync cycle");
        }

        @Override
        public void clear() {
            throw new AssertionError("not used by the sync cycle");
        }

        @Override
        public Optional<char[]> token() {
            throw new AssertionError("the sync cycle never reads the token");
        }
    }

    public static final class FakeAppFlags implements AppFlagsStore {
        public boolean onboardingCompleted = true;

        @Override
        public boolean disclaimerAccepted() {
            return true;
        }

        @Override
        public void setDisclaimerAccepted() {
        }

        @Override
        public boolean onboardingCompleted() {
            return onboardingCompleted;
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            onboardingCompleted = v;
        }

        @Override
        public boolean monitoringConfigured() {
            return true;
        }

        @Override
        public void setMonitoringConfigured(boolean v) {
        }

        @Override
        public long handledBootCount() {
            return 0;
        }

        @Override
        public void setHandledBootCount(long c) {
        }
    }

    // ---------------------------------------------------------------- runtime ports

    public static final class FakeAlarmController implements AlarmController {
        public final List<List<AlarmCommand>> applied = new ArrayList<>();
        public Consumer<List<AlarmCommand>> onApply = c -> { };

        @Override
        public void apply(List<AlarmCommand> commands) {
            onApply.accept(commands);
            applied.add(List.copyOf(commands));
        }

        public List<AlarmCommand> last() {
            return applied.isEmpty() ? null : applied.get(applied.size() - 1);
        }

        public List<AlarmCommand> all() {
            List<AlarmCommand> all = new ArrayList<>();
            applied.forEach(all::addAll);
            return all;
        }
    }

    public static final class FakeReminderScheduler implements ReminderScheduler {
        public final List<ReminderNotice> posted = new ArrayList<>();
        public final List<ConsumableKind> cancelled = new ArrayList<>();

        @Override
        public void post(ReminderNotice n) {
            posted.add(n);
        }

        @Override
        public void cancel(ConsumableKind k) {
            cancelled.add(k);
        }
    }

    public static final class FakePumpAlertNotifier implements PumpAlertNotifier {
        public final List<Entry> posted = new ArrayList<>();

        @Override
        public void post(Entry alert) {
            posted.add(alert);
        }
    }

    public static final class FakeConnectivity implements Connectivity {
        public boolean online = true;
        public final List<Consumer<Boolean>> listeners = new ArrayList<>();

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public void addListener(Consumer<Boolean> l) {
            listeners.add(l);
        }

        public void set(boolean on) {
            online = on;
            listeners.forEach(l -> l.accept(on));
        }
    }

    public static final class FakeReadiness implements ReadinessSource {
        public int missing;

        @Override
        public int missingRequiredCount() {
            return missing;
        }
    }

    public static final class FakeMonitorControl implements MonitorControl {
        public final List<TickReason> ticks = new ArrayList<>();
        public int reevaluations;
        public Runnable onReevaluate = () -> { };

        @Override
        public void requestTick(TickReason r) {
            ticks.add(r);
        }

        @Override
        public void reevaluateAlarms() {
            reevaluations++;
            onReevaluate.run();
        }

        @Override
        public void startMonitoring() {
            starts++;
        }

        /** Number of {@code startMonitoring()} calls (added for CP-02b OnboardingState tests). */
        public int starts;
    }
}
