package app.lightscout.usecases.common;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.settings.Settings;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.page;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** C-8 slicing, server version / permission policy, settings copies. */
public class CommonHelpersTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    public void slices_atMost24h_coverWindow() {
        List<Instant[]> s = SlicedWindowFetcher.slices(T0, T0.plus(Duration.ofHours(50)));
        assertEquals(3, s.size());
        assertEquals(T0, s.get(0)[0]);
        assertEquals(T0.plus(Duration.ofHours(50)), s.get(2)[1]);
        for (Instant[] x : s) {
            assertTrue(Duration.between(x[0], x[1]).compareTo(Duration.ofHours(24)) <= 0);
        }
    }

    @Test
    public void fetch_incompleteSliceSplitInHalves_storeReplacesOnlyCompleteSlices() {
        List<String> log = new ArrayList<>();
        SlicedWindowFetcher.Fetched<String> f = SlicedWindowFetcher.fetch((from, to, l) -> {
            boolean full = Duration.between(from, to).compareTo(Duration.ofHours(24)) == 0;
            return page(!full, List.of("x" + from.getEpochSecond()));
        }, T0, T0.plus(Duration.ofHours(24)), 1000);
        assertEquals(3, f.slices().size());
        assertFalse(f.slices().get(0).complete());
        assertTrue(f.fullyComplete());
        SlicedWindowFetcher.store(f, sink(log));
        assertEquals(List.of("upsert 1", "replace 12h", "replace 12h"), log);
    }

    @Test
    public void fetch_oneHourSliceStillIncomplete_notSplit_noDelete() {
        List<String> log = new ArrayList<>();
        SlicedWindowFetcher.Fetched<String> f = SlicedWindowFetcher.fetch((from, to, l) -> page(false, List.of("x")),
                T0, T0.plus(Duration.ofHours(1)), 1000);
        assertEquals(1, f.slices().size());
        assertFalse(f.fullyComplete());
        SlicedWindowFetcher.store(f, sink(log));
        assertEquals(List.of("upsert 1"), log);
    }

    @Test
    public void fetch_failureStops_storeUpsertsOnly() {
        List<String> log = new ArrayList<>();
        int[] n = {0};
        SlicedWindowFetcher.Fetched<String> f = SlicedWindowFetcher.fetch((from, to, l) -> n[0]++ == 0
                        ? page(true, List.of("a")) : Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK),
                T0, T0.plus(Duration.ofHours(72)), 1000);
        assertEquals(2, n[0]);
        assertTrue(f.failed());
        assertEquals(DetailCode.NO_NETWORK, f.failureDetail());
        SlicedWindowFetcher.store(f, sink(log));
        assertEquals(List.of("upsert 1"), log);
    }

    private static SlicedWindowFetcher.WindowSink<String> sink(List<String> log) {
        return new SlicedWindowFetcher.WindowSink<>() {
            @Override
            public void upsert(List<String> items) {
                log.add("upsert " + items.size());
            }

            @Override
            public void replaceWindow(Instant from, Instant to, List<String> items) {
                log.add("replace " + Duration.between(from, to).toHours() + "h");
            }
        };
    }

    @Test
    public void statisticsCardSettingDefaultsOnAndTogglesThroughTheEditor() {
        app.lightscout.domain.settings.Settings d = app.lightscout.domain.settings.Settings.defaults();
        assertTrue(d.statisticsCardOnHome());
        app.lightscout.domain.settings.Settings off = SettingsEditor.withChange(d,
                new app.lightscout.domain.settings.SettingChange(
                        app.lightscout.domain.settings.SettingKey.STATISTICS_CARD, 0, null));
        assertFalse(off.statisticsCardOnHome());
        // other edits keep the choice
        assertFalse(SettingsEditor.withAlarmsEnabled(off, false).statisticsCardOnHome());
        assertFalse(SettingsEditor.withLowEnabled(off, false).statisticsCardOnHome());
        assertTrue(SettingsEditor.withChange(off, new app.lightscout.domain.settings.SettingChange(
                app.lightscout.domain.settings.SettingKey.STATISTICS_CARD, 1, null)).statisticsCardOnHome());
    }

    @Test
    public void serverPolicy_versionAndApi() {
        assertTrue(ServerPolicy.checkServer(new ServerInfo("15.0.9", "3.0.0")).ok());
        assertTrue(ServerPolicy.checkServer(new ServerInfo("15.0.9-dev", "3")).ok());
        assertTrue(ServerPolicy.checkServer(new ServerInfo("15.1.0", "3")).ok());
        assertEquals(FetchOutcome.SERVER_TOO_OLD, ServerPolicy.checkServer(new ServerInfo("15.0.8", "3.0.0")).outcome());
        assertEquals(FetchOutcome.SERVER_TOO_OLD, ServerPolicy.checkServer(new ServerInfo("15.0.0", "3.0.0")).outcome());
        Result<ServerInfo> old = ServerPolicy.checkServer(new ServerInfo("14.2.0", "3.0.0"));
        assertEquals(FetchOutcome.SERVER_TOO_OLD, old.outcome());
        assertEquals(DetailCode.VERSION_TOO_OLD, old.detailCode());
        assertEquals(DetailCode.NOT_API_V3, ServerPolicy.checkServer(new ServerInfo("15.0.9", null)).detailCode());
        assertEquals(DetailCode.NOT_API_V3, ServerPolicy.checkServer(new ServerInfo("15.0.9", "1.0")).detailCode());
        assertEquals(DetailCode.NO_VERSION, ServerPolicy.checkServer(new ServerInfo("dev", "3.0")).detailCode());
        assertArrayEquals(new int[] {15, 0, 0}, ServerPolicy.parseVersion("15"));
        assertNull(ServerPolicy.parseVersion(""));
    }

    @Test
    public void serverPolicy_permissions() {
        assertEquals(DetailCode.MISSING_WRITE, ServerPolicy.checkPermissions(new Permissions(Map.of(
                NsCollection.ENTRIES, "r", NsCollection.TREATMENTS, "cr"))).detailCode());
        assertEquals(DetailCode.MISSING_READ, ServerPolicy.checkPermissions(new Permissions(Map.of(
                NsCollection.ENTRIES, "", NsCollection.TREATMENTS, "crud"))).detailCode());
        assertTrue(ServerPolicy.checkPermissions(new Permissions(Map.of(
                NsCollection.ENTRIES, "r", NsCollection.TREATMENTS, "crud"))).ok());
    }

    @Test
    public void settingsEditor_changesOnlyTheSwitch() {
        Settings d = Settings.defaults();
        Settings off = SettingsEditor.withAlarmsEnabled(d, false);
        assertFalse(off.alarmsEnabled());
        assertEquals(d, SettingsEditor.withAlarmsEnabled(off, true));
        assertFalse(SettingsEditor.withLowEnabled(d, false).lowEnabled());
        assertFalse(SettingsEditor.withHighEnabled(d, false).highEnabled());
        assertEquals(d.targetRange(), SettingsEditor.withHighEnabled(d, false).targetRange());
    }
}
