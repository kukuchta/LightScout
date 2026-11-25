package app.lightscout.storage;

import org.junit.Test;

import java.time.Instant;

import app.lightscout.domain.port.StorageException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** DCR-46 (D-166): first-use instant stored once; the shown flag is sticky; failures are mapped (R-20). */
public class PrefsSupportPromptStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final RecordingLogger logger = new RecordingLogger();
    private final PrefsSupportPromptStore store = new PrefsSupportPromptStore(prefs, new StoreGuard(logger));

    @Test
    public void firstCallStoresNowAndLaterCallsKeepIt() {
        assertEquals(T0, store.firstUse(T0));
        assertEquals(T0, store.firstUse(T0.plusSeconds(86_400)));
        assertEquals(T0.toEpochMilli(), prefs.values.get(PrefsSupportPromptStore.KEY_FIRST_USE_MS));
    }

    @Test
    public void shownIsFalseUntilMarkedThenStays() {
        assertFalse(store.shown());
        store.markShown();
        assertTrue(store.shown());
        assertEquals(true, prefs.values.get(PrefsSupportPromptStore.KEY_SHOWN));
    }

    @Test
    public void markShownSurvivesANewStoreOverTheSamePrefs() {
        store.markShown();
        store.firstUse(T0);
        PrefsSupportPromptStore reopened = new PrefsSupportPromptStore(prefs, new StoreGuard(logger));
        assertTrue(reopened.shown());
        assertEquals(T0, reopened.firstUse(T0.plusSeconds(5)));
    }

    @Test
    public void failedCommitOnFirstUseIsMappedToStorageException() {
        prefs.failCommits = true;
        try {
            store.firstUse(T0);
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
    }

    @Test
    public void failedCommitOnMarkShownIsMappedToStorageException() {
        prefs.failCommits = true;
        try {
            store.markShown();
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
    }

    @Test
    public void failedReadIsMappedToStorageException() {
        prefs.failReads = true;
        try {
            store.shown();
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
    }
}
