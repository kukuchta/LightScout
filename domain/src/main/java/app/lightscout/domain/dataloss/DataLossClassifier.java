package app.lightscout.domain.dataloss;

import app.lightscout.domain.port.FetchOutcome;

import java.time.Duration;
import java.time.Instant;

/**
 * Data-loss detection and three-way cause (CMP-03, IF-02 amended; 05 §9.1, DS:D-024; CP-00A R-5, R-61, R-67).
 *
 * <ul>
 *   <li>With a last reading: {@code now − lastReadingTime < threshold} → {@link DataLossKind#NONE}; otherwise
 *       {@link DataLossKind#DATA_LOSS} with {@code since = lastReadingTime}.</li>
 *   <li>Without any reading: {@link DataLossKind#FIRST_USE} until {@code now − firstUseSince ≥ threshold}, then
 *       {@code DATA_LOSS} with {@code since = firstUseSince} (R-61). Without an anchor it stays FIRST_USE.</li>
 *   <li>Cause (R-67): OK → {@link DataLossCause#UPLOADER}; NO_INTERNET → {@link DataLossCause#NO_INTERNET};
 *       every other outcome → {@link DataLossCause#NS_NOT_RESPONDING}.</li>
 * </ul>
 */
public final class DataLossClassifier {

    /**
     * Creates the stateless rule component.
     */
    public DataLossClassifier() {
        // no state
    }

    /**
     * Classifies the data-loss state.
     *
     * @param now                    evaluation instant
     * @param lastReadingTimeOrNull  latest reading time excluding future-dated readings (M-9, M-13); nullable
     * @param firstUseSinceOrNull    first-use anchor {@code monitoringSince}; nullable
     * @param lastOutcome            outcome of the last tick; a {@code null} is treated like a non-OK outcome
     * @param threshold              data-loss threshold („No data alarm after”)
     * @return the status
     */
    public DataLossStatus classify(Instant now, Instant lastReadingTimeOrNull, Instant firstUseSinceOrNull,
                                   FetchOutcome lastOutcome, Duration threshold) {
        if (lastReadingTimeOrNull == null) {
            if (firstUseSinceOrNull == null || !reached(now, firstUseSinceOrNull, threshold)) {
                return new DataLossStatus(DataLossKind.FIRST_USE, null, firstUseSinceOrNull);
            }
            return new DataLossStatus(DataLossKind.DATA_LOSS, causeOf(lastOutcome), firstUseSinceOrNull);
        }
        if (!reached(now, lastReadingTimeOrNull, threshold)) {
            return new DataLossStatus(DataLossKind.NONE, null, null);
        }
        return new DataLossStatus(DataLossKind.DATA_LOSS, causeOf(lastOutcome), lastReadingTimeOrNull);
    }

    private static boolean reached(Instant now, Instant anchor, Duration threshold) {
        return Duration.between(anchor, now).compareTo(threshold) >= 0;
    }

    private static DataLossCause causeOf(FetchOutcome outcome) {
        if (outcome == FetchOutcome.OK) {
            return DataLossCause.UPLOADER;
        }
        if (outcome == FetchOutcome.NO_INTERNET) {
            return DataLossCause.NO_INTERNET;
        }
        return DataLossCause.NS_NOT_RESPONDING;
    }
}
