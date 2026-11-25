package app.lightscout.domain.dataloss;

import app.lightscout.domain.FakeClock;
import app.lightscout.domain.port.FetchOutcome;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * TST-01 IF-02 rows (CP-01 §8.3 / §9; CP-00A R-61, R-67).
 */
public class DataLossClassifierTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(20);

    private final FakeClock clock = new FakeClock(Instant.parse("2026-10-15T12:00:00Z"));
    private final DataLossClassifier classifier = new DataLossClassifier();

    private DataLossStatus classifyAge(Duration age, FetchOutcome outcome) {
        return classifier.classify(clock.now(), clock.now().minus(age), null, outcome, THRESHOLD);
    }

    @Test
    public void nullLastReading_isFirstUse() {
        DataLossStatus s = classifier.classify(clock.now(), null, null, FetchOutcome.OK, THRESHOLD);
        assertEquals(DataLossKind.FIRST_USE, s.kind());
        assertNull(s.cause());
    }

    @Test
    public void age10Min_anyOutcome_isNone() {
        for (FetchOutcome outcome : FetchOutcome.values()) {
            DataLossStatus s = classifyAge(Duration.ofMinutes(10), outcome);
            assertEquals(outcome.name(), DataLossKind.NONE, s.kind());
            assertNull(s.cause());
        }
    }

    @Test
    public void age25Min_ok_isUploaderCause() {
        DataLossStatus s = classifyAge(Duration.ofMinutes(25), FetchOutcome.OK);
        assertEquals(DataLossKind.DATA_LOSS, s.kind());
        assertEquals(DataLossCause.UPLOADER, s.cause());
        assertEquals(clock.now().minus(Duration.ofMinutes(25)), s.since());
    }

    @Test
    public void age25Min_noInternet_isNoInternetCause() {
        assertEquals(DataLossCause.NO_INTERNET, classifyAge(Duration.ofMinutes(25), FetchOutcome.NO_INTERNET).cause());
    }

    @Test
    public void age25Min_serverStates_areNsNotRespondingCause() {
        FetchOutcome[] outcomes = {FetchOutcome.NS_NOT_RESPONDING, FetchOutcome.NOT_NIGHTSCOUT,
            FetchOutcome.TOKEN_REJECTED, FetchOutcome.SERVER_TOO_OLD};
        for (FetchOutcome outcome : outcomes) {
            assertEquals(outcome.name(), DataLossCause.NS_NOT_RESPONDING,
                    classifyAge(Duration.ofMinutes(25), outcome).cause());
        }
    }

    @Test
    public void r67_permissionMissingAndStorageError_areNsNotRespondingCause() {
        assertEquals(DataLossCause.NS_NOT_RESPONDING,
                classifyAge(Duration.ofMinutes(25), FetchOutcome.PERMISSION_MISSING).cause());
        assertEquals(DataLossCause.NS_NOT_RESPONDING,
                classifyAge(Duration.ofMinutes(25), FetchOutcome.STORAGE_ERROR).cause());
    }

    @Test
    public void thresholdBoundary_19m59sNone_20mDataLoss() {
        assertEquals(DataLossKind.NONE, classifyAge(Duration.ofMinutes(19).plusSeconds(59), FetchOutcome.OK).kind());
        assertEquals(DataLossKind.DATA_LOSS, classifyAge(Duration.ofMinutes(20), FetchOutcome.OK).kind());
    }

    @Test
    public void r61_firstUse_becomesDataLossAfterThresholdFromAnchor() {
        Instant anchor = clock.now().minus(Duration.ofMinutes(19).plusSeconds(59));
        DataLossStatus before = classifier.classify(clock.now(), null, anchor, FetchOutcome.OK, THRESHOLD);
        assertEquals(DataLossKind.FIRST_USE, before.kind());
        assertEquals(anchor, before.since());

        Instant anchor20 = clock.now().minus(THRESHOLD);
        DataLossStatus after = classifier.classify(clock.now(), null, anchor20, FetchOutcome.NO_INTERNET, THRESHOLD);
        assertEquals(DataLossKind.DATA_LOSS, after.kind());
        assertEquals(DataLossCause.NO_INTERNET, after.cause());
        assertEquals(anchor20, after.since());
    }

    @Test
    public void futureLastReadingWithinTolerance_isNone() {
        DataLossStatus s = classifier.classify(clock.now(), clock.now().plus(Duration.ofMinutes(5)), null,
                FetchOutcome.OK, THRESHOLD);
        assertEquals(DataLossKind.NONE, s.kind());
    }

    @Test
    public void nullOutcome_doesNotThrow_isNsNotResponding() {
        assertEquals(DataLossCause.NS_NOT_RESPONDING, classifyAge(Duration.ofMinutes(25), null).cause());
    }
}
