package app.lightscout.feature.entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.entry.EntryRules;
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
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.domain.port.WriteAck;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.common.ServerEpoch;
import app.lightscout.usecases.connection.OriginBoundTokenSource;
import app.lightscout.usecases.entry.DefaultEntryWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.Test;

/** Synthetic form → real writer → gateway/store doubles; does not prove Android SQLite or network execution. */
public class EntryPrecisionPathTest {

    @Test
    public void createEditSaveAndReloadKeepExactDoseFromRawFormText() {
        Instant now = Instant.parse("2030-01-01T12:00:00Z");
        Cache cache = new Cache();
        Gateway gateway = new Gateway();
        ConnectionStore connection = new ConnectionStore() {
            @Override public Optional<String> baseUrl() { return Optional.of("https://synthetic.example"); }
            @Override public Optional<char[]> token() { return Optional.empty(); }
            @Override public void save(String url, char[] token) { throw new AssertionError(); }
            @Override public void clear() { throw new AssertionError(); }
        };
        OriginBoundTokenSource binding = new OriginBoundTokenSource(connection);
        binding.bind(connection.baseUrl().orElseThrow());
        Connectivity online = new Connectivity() {
            @Override public boolean isOnline() { return true; }
            @Override public void addListener(Consumer<Boolean> listener) { }
        };
        MonitorControl monitor = new MonitorControl() {
            @Override public void requestTick(TickReason reason) { throw new AssertionError("Bolus must not tick"); }
            @Override public void reevaluateAlarms() { throw new AssertionError(); }
            @Override public void startMonitoring() { throw new AssertionError(); }
        };
        Logger logger = new Logger() {
            @Override public void event(EventCode code) { }
            @Override public void event(EventCode code, String detail) { }
            @Override public void crash(String exceptionClass, String topAppFrame) { throw new AssertionError(); }
            @Override public void debug(String tag, String message) { throw new AssertionError(); }
        };
        Fakes.FixedClock clock = new Fakes.FixedClock(now);
        DefaultEntryWriter writer = new DefaultEntryWriter(Fakes.DIRECT, gateway, cache, online, monitor,
                clock, logger, new ServerEpoch(), binding);
        String[] raw = {" 0,025 ", "1.234", "25"};
        int[] milli = {25, 1234, 25000};
        for (int i = 0; i < raw.length; i++) {
            EntryFormModel create = new EntryFormModel(null, EntryType.BOLUS, writer, new EntryRules(), clock,
                    new Fakes.MapStore(), Fakes.DIRECT, true);
            create.start();
            create.onTypeSelected(EntryType.BOLUS);
            create.onValueChanged(raw[i]);
            assertTrue(create.state().saveEnabled());
            String id = create.identifier();
            create.onSave();
            assertEquals(Long.valueOf(milli[i]), gateway.sent.insulinMilli());
            assertEquals(Long.valueOf(milli[i]), cache.get(id).orElseThrow().insulinMilli());
            assertEquals(now, gateway.sent.time());

            EntryFormModel edit = new EntryFormModel(id, writer, new EntryRules(), clock,
                    new Fakes.MapStore(), Fakes.DIRECT, true);
            edit.start();
            assertEquals(EntryFormModel.valueTextOf(EntryType.BOLUS, milli[i]), edit.state().valueText());
            assertFalse(edit.state().backGuard());
            int changed = milli[(i + 1) % milli.length];
            edit.onValueChanged(EntryFormModel.valueTextOf(EntryType.BOLUS, changed));
            edit.onSave();
            assertEquals(Long.valueOf(changed), gateway.sent.insulinMilli());
            assertEquals(1000L, gateway.expected);
            assertEquals(Long.valueOf(changed), cache.get(id).orElseThrow().insulinMilli());
            EntryFormModel reloaded = new EntryFormModel(id, writer, new EntryRules(), clock,
                    new Fakes.MapStore(), Fakes.DIRECT, true);
            reloaded.start();
            assertEquals(EntryFormModel.valueTextOf(EntryType.BOLUS, changed), reloaded.state().valueText());
            assertEquals(1, reloaded.state().similar().entry().insulinMilli().longValue());
        }
        assertEquals(3, gateway.creates);
        assertEquals(3, gateway.updates);
    }

    private static final class Cache implements EntryStore {
        private final Map<String, Entry> rows = new HashMap<>();
        @Override public void upsert(List<Entry> entries) { entries.forEach(e -> rows.put(e.id(), e)); }
        @Override public Optional<Entry> get(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public void delete(String id) { rows.remove(id); }
        @Override public List<Entry> range(Instant from, Instant to) {
            return List.of(new Entry("tiny-imported", EntryOrigin.TREATMENTS, EntryType.BOLUS, from.plusSeconds(60),
                    null, null, 0, 1L, null, null, null, "uploader", null, "Bolus", false, null));
        }
        @Override public Optional<Entry> latestOfType(EntryType type) { throw new AssertionError(); }
        @Override public void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> entries) {
            throw new AssertionError();
        }
        @Override public void evictBefore(Instant time) { throw new AssertionError(); }
        @Override public void clear() { throw new AssertionError(); }
    }

    private static final class Gateway implements NightscoutGateway {
        private Entry sent;
        private long expected;
        private int creates;
        private int updates;
        @Override public Result<WriteAck> create(Entry entry) {
            sent = entry;
            creates++;
            return Result.ok(new WriteAck(entry.id(), 1000L, false));
        }
        @Override public Result<WriteAck> update(Entry entry, long srvModified) {
            sent = entry;
            expected = srvModified;
            updates++;
            return Result.ok(new WriteAck(entry.id(), 2000L, false));
        }
        @Override public void configure(String url, TokenSource tokens) { throw new AssertionError(); }
        @Override public Result<ServerInfo> version(String url) { throw new AssertionError(); }
        @Override public Result<Permissions> permissions() { throw new AssertionError(); }
        @Override public Result<Map<NsCollection, Long>> lastModified() { throw new AssertionError(); }
        @Override public Result<Page<Reading>> readingsSince(long from, int limit) { throw new AssertionError(); }
        @Override public Result<Page<Entry>> meterBgSince(long from, int limit) { throw new AssertionError(); }
        @Override public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
            throw new AssertionError();
        }
        @Override public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
            throw new AssertionError();
        }
        @Override public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
            throw new AssertionError();
        }
        @Override public Result<List<DeviceStatus>> deviceStatusSince(Instant from) { throw new AssertionError(); }
        @Override public Result<Profile> latestProfile() { throw new AssertionError(); }
        @Override public Result<Page<Change>> history(NsCollection collection, long cursor, int limit) {
            throw new AssertionError();
        }
        @Override public Result<Entry> get(String id) { throw new AssertionError(); }
        @Override public Result<WriteAck> delete(String id) { throw new AssertionError(); }
    }
}
