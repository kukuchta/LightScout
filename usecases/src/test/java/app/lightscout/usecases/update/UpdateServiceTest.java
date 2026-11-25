package app.lightscout.usecases.update;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import app.lightscout.domain.port.UpdateSource;
import app.lightscout.domain.port.UpdateStore;
import app.lightscout.domain.update.AppVersion;
import app.lightscout.domain.update.UpdateOffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** DCR-49: the daily check, its failure handling, the once-per-version dialog and the S-04 row. */
public class UpdateServiceTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant NOON = Instant.parse("2026-11-26T12:00:00Z");
    private static final AppVersion V020 = new AppVersion(0, 2, 0);

    private final FakeStore store = new FakeStore();
    private final FakeSource source = new FakeSource();

    private UpdateService service(String versionName) {
        return new UpdateService(store, source, versionName);
    }

    @Test
    public void offersANewerVersionAfterTheFirstCheck() {
        source.answer = V020;
        Optional<UpdateOffer> offer = service("0.1.0").offerForDialog(NOON, UTC, true);
        assertEquals(V020, offer.orElseThrow().version());
        assertEquals(1, source.calls);
        assertEquals(NOON, store.lastCheck);
        assertEquals(V020, store.latest);
    }

    @Test
    public void asksGitHubOnlyOnceADay() {
        source.answer = V020;
        UpdateService service = service("0.1.0");
        service.offerForDialog(NOON, UTC, true);
        service.offerForDialog(NOON.plusSeconds(3600), UTC, true);
        assertEquals(1, source.calls);
        service.offerForDialog(NOON.plusSeconds(24 * 3600), UTC, true);
        assertEquals(2, source.calls);
    }

    @Test
    public void doesNotOfferTheSameVersionAgainOnceMarkedPrompted() {
        source.answer = V020;
        UpdateService service = service("0.1.0");
        UpdateOffer offer = service.offerForDialog(NOON, UTC, true).orElseThrow();
        service.markPrompted(offer);
        assertTrue(service.offerForDialog(NOON.plusSeconds(60), UTC, true).isEmpty());
        assertEquals(V020, store.prompted);
    }

    @Test
    public void aFailedCheckIsRememberedAndKeepsTheStoredVersion() {
        store.latest = V020;
        source.failure = new IOException("offline");
        service("0.1.0").offerForDialog(NOON, UTC, true);
        assertEquals(NOON, store.lastCheck);
        assertEquals(V020, store.latest);
    }

    @Test
    public void aRuntimeFailureOfTheSourceDoesNotEscape() {
        source.runtimeFailure = new IllegalStateException("bad reply");
        assertTrue(service("0.1.0").offerForDialog(NOON, UTC, true).isEmpty());
        assertEquals(NOON, store.lastCheck);
    }

    @Test
    public void nothingIsAskedInAMomentThatIsNotCalm() {
        source.answer = V020;
        assertTrue(service("0.1.0").offerForDialog(NOON, UTC, false).isEmpty());
        assertEquals(0, source.calls);
    }

    @Test
    public void aNightlyBuildNeverChecks() {
        source.answer = V020;
        UpdateService nightly = service("0.1.0-nightly.20261125.abc1234");
        assertTrue(nightly.offerForDialog(NOON, UTC, true).isEmpty());
        assertEquals(0, source.calls);
        store.latest = V020;
        assertTrue(nightly.available().isEmpty());
    }

    @Test
    public void nothingIsAskedWhenTheCheckIsOff() {
        source.answer = V020;
        store.enabled = false;
        UpdateService service = service("0.1.0");
        assertTrue(service.offerForDialog(NOON, UTC, true).isEmpty());
        assertEquals(0, source.calls);
        store.latest = V020;
        assertTrue(service.available().isEmpty());
    }

    @Test
    public void availableShowsTheStoredNewerVersionWithoutTheNetworkAndEvenAfterThePrompt() {
        store.latest = V020;
        store.prompted = V020;
        assertEquals(V020, service("0.1.0").available().orElseThrow().version());
        assertEquals(0, source.calls);
    }

    @Test
    public void availableIsEmptyWhenTheInstalledVersionIsCurrent() {
        store.latest = V020;
        assertTrue(service("0.2.0").available().isEmpty());
        assertTrue(service("0.3.0").available().isEmpty());
    }

    @Test
    public void noReleaseYetGivesNoOffer() {
        source.answer = null;
        assertTrue(service("0.1.0").offerForDialog(NOON, UTC, true).isEmpty());
        assertNull(store.latest);
        assertEquals(NOON, store.lastCheck);
    }

    @Test
    public void theChoiceIsStored() {
        UpdateService service = service("0.1.0");
        assertTrue(service.isEnabled());
        service.setEnabled(false);
        assertFalse(service.isEnabled());
        assertFalse(store.enabled);
    }

    private static final class FakeSource implements UpdateSource {
        AppVersion answer;
        IOException failure;
        RuntimeException runtimeFailure;
        int calls;

        @Override
        public AppVersion latestStable() throws IOException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            return answer;
        }
    }

    private static final class FakeStore implements UpdateStore {
        boolean enabled = true;
        Instant lastCheck;
        AppVersion latest;
        AppVersion prompted;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean value) {
            enabled = value;
        }

        @Override
        public Instant lastCheck() {
            return lastCheck;
        }

        @Override
        public AppVersion latest() {
            return latest;
        }

        @Override
        public AppVersion lastPrompted() {
            return prompted;
        }

        @Override
        public void recordCheck(Instant checkedAt, AppVersion named) {
            lastCheck = checkedAt;
            if (named != null) {
                latest = named;
            }
        }

        @Override
        public void markPrompted(AppVersion version) {
            prompted = version;
        }
    }
}
