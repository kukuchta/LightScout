package app.lightscout.usecases.fakes;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.domain.port.WriteAck;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static app.lightscout.usecases.fakes.Fakes.page;

/** Hand-written fakes for the CP-02b use cases (writes, connection, onboarding, history). No Mockito. */
public final class Cp02bFakes {

    private Cp02bFakes() {
    }

    /** Gateway fake for writes, verification and history; records every call in order. */
    public static final class FakeGateway implements NightscoutGateway {
        public final List<String> calls = new ArrayList<>();
        public final List<Entry> created = new ArrayList<>();
        public final List<Object[]> updated = new ArrayList<>();
        public final List<String> deletedIds = new ArrayList<>();
        public final List<Instant[]> readingsWindowCalls = new ArrayList<>();
        public final List<Instant[]> meterBgWindowCalls = new ArrayList<>();
        public final List<Instant[]> entriesWindowCalls = new ArrayList<>();
        /** Base URL and token seen by each {@code configure}; the token is read once at configure time. */
        public final List<String> configuredUrls = new ArrayList<>();
        public final List<String> tokensSentAtPermissions = new ArrayList<>();
        public TokenSource tokens;
        public String baseUrl;
        /** Runs inside {@code configure} (ordering checks). */
        public Runnable onConfigure = () -> { };

        public Function<String, Result<ServerInfo>> version = u -> Result.ok(new ServerInfo("15.0.9", "3.0.4"));
        public Supplier<Result<Permissions>> permissions = () -> Result.ok(new Permissions(Map.of(
                NsCollection.ENTRIES, "r", NsCollection.TREATMENTS, "crud")));
        public Function<Entry, Result<WriteAck>> create = e -> Result.ok(new WriteAck(e.id(), 5_000L, false));
        public Function<String, Result<Entry>> get = id -> Result.fail(FetchOutcome.OK, "GONE_404_410");
        public BiFunction<Entry, Long, Result<WriteAck>> update = (e, ms) -> Result.ok(new WriteAck(e.id(), 6_000L, false));
        public Function<String, Result<WriteAck>> delete = id -> Result.ok(new WriteAck(id, 0L, false));
        public Fakes.Window<Reading> readingsWindow = (f, t, l) -> page(true, List.of());
        public Fakes.Window<Entry> meterBgWindow = (f, t, l) -> page(true, List.of());
        public Fakes.Window<Entry> entriesWindow = (f, t, l) -> page(true, List.of());

        public long count(String method) {
            return calls.stream().filter(c -> c.equals(method)).count();
        }

        /** Calls that send the token (everything after the version check). */
        public long tokenCalls() {
            return calls.stream().filter(c -> !c.equals("version") && !c.startsWith("configure")).count();
        }

        @Override
        public void configure(String baseUrl, TokenSource tokens) {
            calls.add("configure");
            configuredUrls.add(baseUrl);
            this.baseUrl = baseUrl;
            this.tokens = tokens;
            onConfigure.run();
        }

        @Override
        public Result<ServerInfo> version(String baseUrl) {
            calls.add("version");
            return version.apply(baseUrl);
        }

        @Override
        public Result<Permissions> permissions() {
            calls.add("permissions");
            Optional<char[]> t = tokens == null ? Optional.empty() : tokens.token();
            tokensSentAtPermissions.add(t.map(String::new).orElse(null));
            t.ifPresent(a -> Arrays.fill(a, '\0'));   // the caller of token() wipes (IF-61)
            return permissions.get();
        }

        @Override
        public Result<Map<NsCollection, Long>> lastModified() {
            throw new AssertionError("not used by CP-02b");
        }

        @Override
        public Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit) {
            throw new AssertionError("not used by CP-02b");
        }

        @Override
        public Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit) {
            throw new AssertionError("not used by CP-02b");
        }

        @Override
        public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
            calls.add("readingsWindow");
            readingsWindowCalls.add(new Instant[] {from, to});
            return readingsWindow.fetch(from, to, limit);
        }

        @Override
        public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
            calls.add("meterBgWindow");
            meterBgWindowCalls.add(new Instant[] {from, to});
            return meterBgWindow.fetch(from, to, limit);
        }

        @Override
        public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
            calls.add("entriesWindow");
            entriesWindowCalls.add(new Instant[] {from, to});
            return entriesWindow.fetch(from, to, limit);
        }

        @Override
        public Result<List<DeviceStatus>> deviceStatusSince(Instant from) {
            throw new AssertionError("not used by CP-02b");
        }

        @Override
        public Result<Profile> latestProfile() {
            throw new AssertionError("not used by CP-02b");
        }

        @Override
        public Result<Page<Change>> history(NsCollection c, long cursorMs, int limit) {
            throw new AssertionError("not used by CP-02b");
        }

        @Override
        public Result<WriteAck> create(Entry e) {
            calls.add("create");
            created.add(e);
            return create.apply(e);
        }

        @Override
        public Result<Entry> get(String id) {
            calls.add("get");
            return get.apply(id);
        }

        @Override
        public Result<WriteAck> update(Entry e, long expectedSrvModifiedMs) {
            calls.add("update");
            updated.add(new Object[] {e, expectedSrvModifiedMs});
            return update.apply(e, expectedSrvModifiedMs);
        }

        @Override
        public Result<WriteAck> delete(String id) {
            calls.add("delete");
            deletedIds.add(id);
            return delete.apply(id);
        }
    }

    /** Connection store fake; records saves (the token as a string copy) and token reads. */
    public static final class FakeConnectionStore implements ConnectionStore {
        public String url;
        public char[] token;
        public final List<String[]> saves = new ArrayList<>();
        public int tokenReads;
        public boolean fail;

        @Override
        public Optional<String> baseUrl() {
            if (fail) {
                throw new StorageException("connection.baseUrl", new RuntimeException("fake"));
            }
            return Optional.ofNullable(url);
        }

        @Override
        public void save(String baseUrl, char[] t) {
            if (fail) {
                throw new StorageException("connection.save", new RuntimeException("fake"));
            }
            saves.add(new String[] {baseUrl, new String(t)});
            url = baseUrl;
            token = t.clone();
        }

        @Override
        public void clear() {
            url = null;
            token = null;
        }

        @Override
        public Optional<char[]> token() {
            tokenReads++;
            return token == null ? Optional.empty() : Optional.of(token.clone());
        }
    }

    /** App flags fake with a failure switch. */
    public static final class FakeAppFlags implements AppFlagsStore {
        public boolean disclaimerAccepted;
        public boolean onboardingCompleted;
        public boolean monitoringConfigured;
        public boolean fail;
        /** Fails only the CE flags (disclaimerAccepted, onboardingCompleted). */
        public boolean failCe;

        private void checkCe() {
            if (failCe) {
                throw new StorageException("flags.ce", new RuntimeException("fake"));
            }
            check();
        }

        private void check() {
            if (fail) {
                throw new StorageException("flags", new RuntimeException("fake"));
            }
        }

        @Override
        public boolean disclaimerAccepted() {
            checkCe();
            return disclaimerAccepted;
        }

        @Override
        public void setDisclaimerAccepted() {
            checkCe();
            disclaimerAccepted = true;
        }

        @Override
        public boolean onboardingCompleted() {
            checkCe();
            return onboardingCompleted;
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            checkCe();
            onboardingCompleted = v;
        }

        @Override
        public boolean monitoringConfigured() {
            check();
            return monitoringConfigured;
        }

        @Override
        public void setMonitoringConfigured(boolean v) {
            check();
            monitoringConfigured = v;
        }

        @Override
        public long handledBootCount() {
            return 0;
        }

        @Override
        public void setHandledBootCount(long c) {
        }
    }

    /** Callback that records every result. */
    public static final class Recorder<T> implements app.lightscout.domain.port.Callback<T> {
        public final List<Result<T>> results = new ArrayList<>();

        @Override
        public void onResult(Result<T> result) {
            results.add(result);
        }

        public Result<T> only() {
            if (results.size() != 1) {
                throw new AssertionError("expected one callback, got " + results.size());
            }
            return results.get(0);
        }
    }
}
