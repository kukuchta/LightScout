package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import app.lightscout.domain.port.StorageException;

/**
 * IF-60 DE/CE split (CP-04 §8.8, CP-00A R-39, R-66): JVM part of TST-05-13 (DE readable while locked, CE never
 * opened) and TST-05-14 (exact DE key set).
 */
public class PrefsAppFlagsStoreTest {

    private final FakeSharedPreferences boot = new FakeSharedPreferences();
    private final FakeSharedPreferences runtime = new FakeSharedPreferences();
    private final RecordingLogger logger = new RecordingLogger();
    private final AtomicInteger ceOpens = new AtomicInteger();
    private boolean unlocked = true;

    private final Supplier<SharedPreferences> runtimeSupplier = () -> {
        ceOpens.incrementAndGet();
        return runtime;
    };

    private final PrefsAppFlagsStore store =
            new PrefsAppFlagsStore(boot, runtimeSupplier, () -> unlocked, logger, new StoreGuard(logger));

    @Test
    public void defaultsAreFalseAndZero() {
        assertFalse(store.disclaimerAccepted());
        assertFalse(store.onboardingCompleted());
        assertFalse(store.monitoringConfigured());
        assertEquals(0L, store.handledBootCount());
    }

    @Test
    public void deFlagsRoundTripAndLiveOnlyInTheBootFile() {
        store.setMonitoringConfigured(true);
        store.setHandledBootCount(42L);
        assertTrue(store.monitoringConfigured());
        assertEquals(42L, store.handledBootCount());
        assertEquals(0, ceOpens.get());
        assertTrue(runtime.values.isEmpty());
        store.setMonitoringConfigured(false);
        assertFalse(store.monitoringConfigured());
    }

    @Test
    public void bootFileHoldsExactlyTheThreeAllowedKeys() {
        store.setMonitoringConfigured(true);
        store.setHandledBootCount(7L);
        store.setDisclaimerAccepted();
        store.setOnboardingCompleted(true);
        assertEquals(Set.of("monitoringConfigured", "handledBootCount", "schema"), boot.getAll().keySet());
    }

    @Test
    public void ceFlagsRoundTripInTheRuntimeFile() {
        store.setDisclaimerAccepted();
        store.setOnboardingCompleted(true);
        assertTrue(store.disclaimerAccepted());
        assertTrue(store.onboardingCompleted());
        assertEquals(true, runtime.values.get(PrefsAppFlagsStore.DISCLAIMER_ACCEPTED));
        assertEquals(true, runtime.values.get(PrefsAppFlagsStore.ONBOARDING_COMPLETED));
        assertEquals(1, runtime.values.get(PrefsSchema.KEY));
        store.setOnboardingCompleted(false);
        assertFalse(store.onboardingCompleted());
    }

    @Test
    public void whileLockedDeFlagsWorkAndCeIsNeverOpened() {
        boot.values.put(PrefsAppFlagsStore.MONITORING_CONFIGURED, true);
        boot.values.put(PrefsAppFlagsStore.HANDLED_BOOT_COUNT, 3L);
        runtime.values.put(PrefsAppFlagsStore.DISCLAIMER_ACCEPTED, true);
        unlocked = false;

        assertTrue(store.monitoringConfigured());
        assertEquals(3L, store.handledBootCount());
        store.setHandledBootCount(4L);
        assertEquals(4L, store.handledBootCount());

        assertFalse("CE getter returns false while locked", store.disclaimerAccepted());
        assertFalse(store.onboardingCompleted());
        store.setDisclaimerAccepted();
        store.setOnboardingCompleted(true);

        assertEquals(0, ceOpens.get());
        assertFalse(runtime.values.containsKey(PrefsAppFlagsStore.ONBOARDING_COMPLETED));
        assertEquals(List.of("CE_ACCESS_LOCKED", "CE_ACCESS_LOCKED", "CE_ACCESS_LOCKED", "CE_ACCESS_LOCKED"),
                logger.lines);
    }

    @Test
    public void afterUnlockTheCeFlagsAreReadAgain() {
        runtime.values.put(PrefsAppFlagsStore.DISCLAIMER_ACCEPTED, true);
        unlocked = false;
        assertFalse(store.disclaimerAccepted());
        unlocked = true;
        assertTrue(store.disclaimerAccepted());
    }

    @Test
    public void deWriteFailureFailsWithStorageException() {
        boot.failCommits = true;
        StorageException e = assertThrows(StorageException.class, () -> store.setMonitoringConfigured(true));
        assertEquals("boot.write", e.op());
        assertEquals(List.of("STORAGE_ERROR:boot.write"), logger.lines);
    }

    @Test
    public void ceWriteFailureFailsWithStorageException() {
        runtime.failCommits = true;
        StorageException e = assertThrows(StorageException.class, store::setDisclaimerAccepted);
        assertEquals("flags.write", e.op());
    }
}
