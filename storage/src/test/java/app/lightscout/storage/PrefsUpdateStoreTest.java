package app.lightscout.storage;

import org.junit.Test;

import java.time.Instant;

import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.update.AppVersion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** DCR-49: the update check choice defaults to on; check time, newest version and the offered one persist. */
public class PrefsUpdateStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-26T12:00:00Z");
    private static final AppVersion V020 = new AppVersion(0, 2, 0);
    private static final AppVersion V030 = new AppVersion(0, 3, 0);

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final RecordingLogger logger = new RecordingLogger();
    private final PrefsUpdateStore store = new PrefsUpdateStore(prefs, new StoreGuard(logger));

    @Test
    public void startsOnWithNothingKnown() {
        assertTrue(store.enabled());
        assertNull(store.lastCheck());
        assertNull(store.latest());
        assertNull(store.lastPrompted());
    }

    @Test
    public void theChoiceIsStored() {
        store.setEnabled(false);
        assertFalse(store.enabled());
        assertEquals(false, prefs.values.get(PrefsUpdateStore.KEY_ENABLED));
        store.setEnabled(true);
        assertTrue(store.enabled());
    }

    @Test
    public void aCheckStoresTheTimeAndTheNamedVersion() {
        store.recordCheck(T0, V020);
        assertEquals(T0, store.lastCheck());
        assertEquals(V020, store.latest());
        assertEquals("0.2.0", prefs.values.get(PrefsUpdateStore.KEY_LATEST));
    }

    @Test
    public void aFailedCheckKeepsTheStoredVersionButStoresTheTime() {
        store.recordCheck(T0, V020);
        store.recordCheck(T0.plusSeconds(86_400), null);
        assertEquals(T0.plusSeconds(86_400), store.lastCheck());
        assertEquals(V020, store.latest());
    }

    @Test
    public void thePromptedVersionIsStoredAndMovesForward() {
        store.markPrompted(V020);
        assertEquals(V020, store.lastPrompted());
        store.markPrompted(V030);
        assertEquals(V030, store.lastPrompted());
    }

    @Test
    public void stateSurvivesANewStoreOverTheSamePrefs() {
        store.setEnabled(false);
        store.recordCheck(T0, V020);
        store.markPrompted(V020);
        PrefsUpdateStore reopened = new PrefsUpdateStore(prefs, new StoreGuard(logger));
        assertFalse(reopened.enabled());
        assertEquals(T0, reopened.lastCheck());
        assertEquals(V020, reopened.latest());
        assertEquals(V020, reopened.lastPrompted());
    }

    @Test
    public void aStoredTextThatIsNotAVersionReadsAsUnknown() {
        prefs.values.put(PrefsUpdateStore.KEY_LATEST, "nightly-abc");
        prefs.values.put(PrefsUpdateStore.KEY_PROMPTED, "");
        assertNull(store.latest());
        assertNull(store.lastPrompted());
    }

    @Test
    public void failedCommitsAreMappedToStorageException() {
        prefs.failCommits = true;
        try {
            store.recordCheck(T0, V020);
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
        try {
            store.setEnabled(false);
            fail("expected StorageException");
        } catch (StorageException expected) {
            // mapped like every store failure (R-20)
        }
        try {
            store.markPrompted(V020);
            fail("expected StorageException");
        } catch (StorageException expected) {
            // mapped like every store failure (R-20)
        }
    }

    @Test
    public void failedReadsAreMappedToStorageException() {
        prefs.failReads = true;
        try {
            store.enabled();
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
        try {
            store.latest();
            fail("expected StorageException");
        } catch (StorageException expected) {
            // mapped like every store failure (R-20)
        }
    }
}
