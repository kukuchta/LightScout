package app.lightscout.monitor;

import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.StatusSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Pure S-10 projection rules (CP-00A R-81). */
final class StatusNotificationContent {

    private StatusNotificationContent() {
    }

    interface Strings {
        String titleEmpty();
        String reading(int sgv, String signedDelta);
        String firstUse();
        String dataLoss(long minutes);
        String offline(long minutes);
        String errorNotResponding();
        String errorTokenRejected();
        String normal(String age);
        String alarmsOffSuffix();
    }

    interface Formatter {
        String age(Duration age);
        String arrow(Trend trend);
        String spokenTrend(Trend trend);
    }

    /**
     * @param headline the title without the arrow, for the layout that draws the arrow as an icon (DCR-37)
     * @param trend    the trend to draw as an icon, {@code null} when unknown
     */
    record Content(String title, String spokenTrend, String text, Integer iconSgv, Integer deltaMgdl,
                   String headline, Trend trend) {
        Content(String title, String spokenTrend, String text, Integer iconSgv, Integer deltaMgdl) {
            this(title, spokenTrend, text, iconSgv, deltaMgdl, title, null);
        }

        Content(String title, String spokenTrend, String text, Integer iconSgv) {
            this(title, spokenTrend, text, iconSgv, null, title, null);
        }
    }

    static Content create(StatusSnapshot snapshot, Instant now, ZoneId zone, Strings strings, Formatter formatter) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(strings, "strings");
        Objects.requireNonNull(formatter, "formatter");

        Reading latest = snapshot == null ? null : snapshot.latest();
        String arrow = latest == null ? "" : formatter.arrow(latest.trend());
        String spokenTrend = arrow.isEmpty() ? "" : formatter.spokenTrend(latest.trend());
        Integer iconSgv = isStale(snapshot, now) ? null : latest.sgv();
        // A delta next to a stale glucose value would suggest a fresh trend: show it only while the reading is fresh.
        Integer delta = iconSgv == null ? null : snapshot.deltaMgdl();
        String title = latest == null ? strings.titleEmpty() : title(latest.sgv(), delta, arrow, strings);
        String headline = latest == null ? title : title(latest.sgv(), delta, "", strings);
        Trend trend = latest == null || arrow.isEmpty() ? null : latest.trend();

        String text;
        if (snapshot == null) {
            text = strings.firstUse();
        } else if ((!snapshot.online() || snapshot.lastOutcome() == FetchOutcome.NO_INTERNET) && latest != null) {
            text = strings.offline(nonNegativeMinutes(Duration.between(latest.time(), now)));
        } else if (snapshot.lastOutcome() == FetchOutcome.TOKEN_REJECTED
                || snapshot.lastOutcome() == FetchOutcome.PERMISSION_MISSING
                || snapshot.connectionProblem() == FetchOutcome.PERMISSION_MISSING) {
            text = strings.errorTokenRejected();
        } else if (isNightscoutFailure(snapshot.lastOutcome())
                || isNightscoutFailure(snapshot.connectionProblem())) {
            text = strings.errorNotResponding();
        } else if (snapshot.dataLoss().kind() == DataLossKind.FIRST_USE) {
            text = strings.firstUse();
        } else if (snapshot.dataLoss().kind() == DataLossKind.DATA_LOSS) {
            Instant since = snapshot.dataLoss().since() != null ? snapshot.dataLoss().since()
                    : latest == null ? now : latest.time();
            text = strings.dataLoss(nonNegativeMinutes(Duration.between(since, now)));
        } else if (latest == null) {
            text = strings.firstUse();
        } else {
            text = strings.normal(formatter.age(Duration.between(latest.time(), now)));
        }

        if (snapshot != null && !snapshot.alarmsEnabled()) {
            text += strings.alarmsOffSuffix();
        }
        return new Content(title, spokenTrend, text, iconSgv, delta, headline, trend);
    }

    /** DCR-37: "127 -5 mg/dL →": reading, delta, one unit, then the arrow (last: it carries the spoken-trend span). */
    private static String title(int sgv, Integer delta, String arrow, Strings strings) {
        String signed = delta == null ? null : delta >= 0 ? "+" + delta : Integer.toString(delta);
        StringBuilder title = new StringBuilder(strings.reading(sgv, signed));
        if (!arrow.isEmpty()) {
            title.append(' ').append(arrow);
        }
        return title.toString();
    }

    static boolean isStale(StatusSnapshot snapshot, Instant now) {
        if (snapshot == null || snapshot.latest() == null) {
            return true;
        }
        Duration age = Duration.between(snapshot.latest().time(), now);
        return age.compareTo(Duration.ofMinutes(snapshot.noDataMinutes())) >= 0;
    }

    private static long nonNegativeMinutes(Duration age) {
        return Math.max(0L, age.toMinutes());
    }

    private static boolean isNightscoutFailure(FetchOutcome outcome) {
        return outcome == FetchOutcome.NS_NOT_RESPONDING
                || outcome == FetchOutcome.NOT_NIGHTSCOUT
                || outcome == FetchOutcome.SERVER_TOO_OLD;
    }
}
