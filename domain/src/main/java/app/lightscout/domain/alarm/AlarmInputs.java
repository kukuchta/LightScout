package app.lightscout.domain.alarm;

import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.settings.Settings;

import java.time.Instant;
import java.util.Objects;

/**
 * Inputs of one alarm evaluation (IF-01, CP-00A R-4).
 *
 * @param now                the evaluation instant
 * @param latest             M-9-filtered latest reading; nullable
 * @param futureDatedLow     newest reading dated after now + 10 min with {@code sgv ≤ low}, evaluated as age 0
 *                           (04 §8 M-9 amended); nullable
 * @param settings           current settings
 * @param dataLoss           IF-02 result
 * @param watchdogStale      IF-03 result (R-68)
 * @param lastCheckCompleted last completed check; nullable
 * @param state              current alarm state
 */
public record AlarmInputs(Instant now,
                          Reading latest,
                          Reading futureDatedLow,
                          Settings settings, DataLossStatus dataLoss,
                          boolean watchdogStale,
                          Instant lastCheckCompleted, AlarmState state) {

    /**
     * Creates the inputs.
     *
     * @throws NullPointerException if a non-nullable component is null
     */
    public AlarmInputs {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(dataLoss, "dataLoss");
        Objects.requireNonNull(state, "state");
    }
}
