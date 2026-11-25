package app.lightscout.storage;

import static app.lightscout.storage.StorageTestSupport.t;
import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.port.DeviceStatusStore;

/**
 * IF-10 on a real SQLite: device status upsert / latest, latest-profile-only, TST-05-06 eviction, -07 clear.
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class DeviceStatusStoreTest {

    private Context context;
    private DeviceStatusStore store;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
        store = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger()).deviceStatus();
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    @Test
    public void tst0505_upsertAndLatest() {
        assertEquals(Optional.empty(), store.latest());
        store.upsert(List.of(new DeviceStatus("d1", t(-10), 150, "fake-pump"),
                new DeviceStatus("d2", t(-5), null, null)));
        assertEquals(Optional.of(new DeviceStatus("d2", t(-5), null, null)), store.latest());
        store.upsert(List.of(new DeviceStatus("d2", t(-5), 140, "fake-pump")));
        assertEquals(Optional.of(new DeviceStatus("d2", t(-5), 140, "fake-pump")), store.latest());
    }

    @Test
    public void tst0505_saveProfileReplacesTheLatestProfile() {
        assertEquals(Optional.empty(), store.latestProfile());
        Profile first = new Profile("p1", t(-1000), ZoneId.of("Europe/Warsaw"),
                List.of(new BasalStep(0, 100), new BasalStep(3600, 1250)));
        Profile second = new Profile("p2", t(-2000), null, List.of(new BasalStep(0, 875)));
        store.saveProfile(first);
        assertEquals(Optional.of(first), store.latestProfile());
        store.saveProfile(second);
        assertEquals("latest saved wins, older start or not", Optional.of(second), store.latestProfile());
    }

    @Test
    public void tst0506_evictBeforeRemovesOlderDeviceStatusOnly() {
        store.upsert(List.of(new DeviceStatus("old", t(-44_641), 1, null),
                new DeviceStatus("new", t(-44_640), 2, null)));
        store.saveProfile(new Profile("p1", t(-90_000), null, List.of()));
        store.evictBefore(t(-44_640));
        assertEquals("new", store.latest().orElseThrow().id());
        store.evictBefore(t(0));
        assertEquals(Optional.empty(), store.latest());
        assertEquals("p1", store.latestProfile().orElseThrow().id());
    }

    @Test
    public void tst0507_clearEmptiesDeviceStatusAndProfile() {
        store.upsert(List.of(new DeviceStatus("d1", t(0), 1, null)));
        store.saveProfile(new Profile("p1", t(0), null, List.of()));
        store.clear();
        assertEquals(Optional.empty(), store.latest());
        assertEquals(Optional.empty(), store.latestProfile());
    }
}
