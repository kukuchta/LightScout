package app.lightscout.feature.home;

import app.lightscout.domain.model.Reading;

import java.time.Instant;
import java.util.List;

/** Calculates the Home delta from the immediately preceding distinct-time cached reading. */
public final class GlucoseDelta {

    private GlucoseDelta() {
    }

    /**
     * Returns current glucose minus its immediate distinct-time predecessor when their gap is 4–6 minutes.
     * A nearer reading outside that interval prevents searching farther back.
     */
    public static Integer calculate(Reading current, List<Reading> cachedReadings) {
        return app.lightscout.domain.status.ReadingDelta.calculate(current, cachedReadings);
    }

    /** Signed integer text for the localized {@code s01_delta} resource. */
    public static String signedText(int deltaMgdl) {
        return deltaMgdl > 0 ? "+" + deltaMgdl : Integer.toString(deltaMgdl);
    }
}
