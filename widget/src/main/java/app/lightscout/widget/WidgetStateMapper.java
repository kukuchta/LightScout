package app.lightscout.widget;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.StatusSnapshot;

/**
 * Maps the shared IF-21 status snapshot to the S-12 presentation rules (CP-00A R-81).
 */
public final class WidgetStateMapper {

    private WidgetStateMapper() {
    }

    /**
     * Maps one status snapshot without reading stores or Android resources.
     *
     * @param snapshot status projection
     * @param now      current IF-15 instant
     * @return widget state
     */
    public static WidgetPresentation map(StatusSnapshot snapshot, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        if (!snapshot.configured()) {
            return new WidgetPresentation(WidgetPresentation.State.FIRST_USE, null,
                    WidgetPresentation.RangeLabel.NONE, null, false, snapshot.lowMgdl(), snapshot.highMgdl());
        }

        Reading latest = snapshot.latest();
        if (latest == null) {
            return new WidgetPresentation(WidgetPresentation.State.NO_READINGS, null,
                    WidgetPresentation.RangeLabel.NONE, null, false, snapshot.lowMgdl(), snapshot.highMgdl());
        }

        Duration age = Duration.between(latest.time(), now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        boolean stale = age.toMinutes() >= snapshot.noDataMinutes()
                || !snapshot.online()
                || snapshot.lastOutcome() == FetchOutcome.NO_INTERNET;

        WidgetPresentation.RangeLabel label;
        if (latest.sgv() <= snapshot.urgentLowMgdl()) {
            label = WidgetPresentation.RangeLabel.URGENT_LOW;
        } else if (latest.sgv() <= snapshot.lowMgdl()) {
            label = WidgetPresentation.RangeLabel.LOW;
        } else if (latest.sgv() >= snapshot.highMgdl()) {
            label = WidgetPresentation.RangeLabel.HIGH;
        } else {
            label = WidgetPresentation.RangeLabel.NONE;
        }
        // A delta next to a stale reading would suggest a fresh trend: only while the reading is fresh (as in S-10).
        Integer delta = age.toMinutes() >= snapshot.noDataMinutes() ? null : snapshot.deltaMgdl();
        return new WidgetPresentation(WidgetPresentation.State.CONTENT, latest, label, age, stale,
                snapshot.lowMgdl(), snapshot.highMgdl(), delta);
    }
}
