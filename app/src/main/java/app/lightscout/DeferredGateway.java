package app.lightscout;

import androidx.annotation.VisibleForTesting;
import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.domain.port.WriteAck;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * IF-07 whose {@code :nightscout} implementation is created on the <b>first gateway call</b>, not when the container
 * wires it (BUG-14; same rule as {@link DeferredStores}, CP-15 §8.1 „each component is built on first use”).
 *
 * <p>Why: creating {@code OkHttpNightscoutGateway} builds its {@code OkHttpClient}, which initialises an
 * {@code SSLContext} — a slow call. The features obtain IF-22…IF-25 from the container on the main thread
 * (ViewModel factories, {@code ConnectionFragment.onCreate}), where no slow work is allowed (CP-00 §4; the debug
 * StrictMode kills the app, BUG-04 / D-055). Gateway methods are blocking and always run on {@code monitorExecutor}
 * or {@code ioExecutor} (CP-00 §4), so the real gateway is created there.
 *
 * <p>One wrapper stands for exactly one gateway instance: the Q-46 split (monitoring vs probe gateway) and the
 * shared-instance rules hold for the wrappers the container hands out. Every call is delegated unchanged.
 */
final class DeferredGateway implements NightscoutGateway {

    private final DeferredStores.Memo<NightscoutGateway> gateway;

    /**
     * Creates the wrapper; the factory runs at most once, on the thread of the first gateway call.
     *
     * @param factory creates the real gateway
     */
    DeferredGateway(Supplier<NightscoutGateway> factory) {
        this.gateway = new DeferredStores.Memo<>(factory);
    }

    /**
     * Returns the real gateway, creating it on the calling thread if needed (tests only; never on the main thread).
     *
     * @return the delegate
     */
    @VisibleForTesting
    NightscoutGateway delegate() {
        return gateway.get();
    }

    @Override
    public void configure(String baseUrl, TokenSource tokens) {
        gateway.get().configure(baseUrl, tokens);
    }

    @Override
    public Result<ServerInfo> version(String baseUrl) {
        return gateway.get().version(baseUrl);
    }

    @Override
    public Result<Permissions> permissions() {
        return gateway.get().permissions();
    }

    @Override
    public Result<Map<NsCollection, Long>> lastModified() {
        return gateway.get().lastModified();
    }

    @Override
    public Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit) {
        return gateway.get().readingsSince(fromMsExclusive, limit);
    }

    @Override
    public Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit) {
        return gateway.get().meterBgSince(fromMsExclusive, limit);
    }

    @Override
    public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
        return gateway.get().readingsWindow(from, to, limit);
    }

    @Override
    public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
        return gateway.get().meterBgWindow(from, to, limit);
    }

    @Override
    public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
        return gateway.get().entriesWindow(from, to, limit);
    }

    @Override
    public Result<List<DeviceStatus>> deviceStatusSince(Instant from) {
        return gateway.get().deviceStatusSince(from);
    }

    @Override
    public Result<Profile> latestProfile() {
        return gateway.get().latestProfile();
    }

    @Override
    public Result<Page<Change>> history(NsCollection c, long cursorMs, int limit) {
        return gateway.get().history(c, cursorMs, limit);
    }

    @Override
    public Result<WriteAck> create(Entry e) {
        return gateway.get().create(e);
    }

    @Override
    public Result<Entry> get(String id) {
        return gateway.get().get(id);
    }

    @Override
    public Result<WriteAck> update(Entry e, long expectedSrvModifiedMs) {
        return gateway.get().update(e, expectedSrvModifiedMs);
    }

    @Override
    public Result<WriteAck> delete(String id) {
        return gateway.get().delete(id);
    }
}
