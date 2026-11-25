package app.lightscout.domain.dataloss;

import java.time.Instant;
import java.util.Objects;

/**
 * Data-loss classification (IF-02, CP-00A R-5).
 *
 * @param kind  the kind
 * @param cause the cause; non-null for {@link DataLossKind#DATA_LOSS}, otherwise null
 * @param since anchor: the last reading time, or the first-use anchor ({@code monitoringSince}); nullable
 */
public record DataLossStatus(DataLossKind kind, DataLossCause cause, Instant since) {

    /**
     * Creates a status.
     *
     * @throws NullPointerException if {@code kind} is null, or {@code cause} is null for {@code DATA_LOSS}
     */
    public DataLossStatus {
        Objects.requireNonNull(kind, "kind");
        if (kind == DataLossKind.DATA_LOSS) {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
