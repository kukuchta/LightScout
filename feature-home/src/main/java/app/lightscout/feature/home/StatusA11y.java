package app.lightscout.feature.home;

import java.util.Locale;

/**
 * Spoken text of the S-01 reading group (CP-12 §7.11; CP-00A §5.3 {@code s01_status_a11y}; Q-86, Q-87). The
 * template takes optional fragments in a fixed order: the range part right after the value (only when a range label
 * is shown, with the §17 word {@code s01_range_*}), then IOB and delta. Pure Java so the composition is JVM-tested
 * against the resource templates.
 */
public final class StatusA11y {

    private StatusA11y() {
    }

    /**
     * Composes the reading-group text, e.g. „Glucose 52 milligrams per decilitre, URGENT LOW, falling, 3 minutes
     * ago”.
     *
     * @param locale          locale of the resource configuration
     * @param template        {@code s01_status_a11y} ({sgv} %1$d, range part %2$s, {trend} %3$s, {age} %4$s,
     *                        IOB part %5$s, delta part %6$s)
     * @param rangePart       {@code s01_status_a11y_range} (word %1$s)
     * @param iobPart         {@code s01_status_a11y_iob} ({iob} %1$s)
     * @param sgv             reading in mg/dL
     * @param rangeWordOrNull shown range label word, {@code null} when in range
     * @param trend           spoken trend word
     * @param age             spoken age
     * @param iobOrNull       IOB „x.x”, {@code null} when hidden
     * @param delta           localized delta label, or {@code null} before a reading is available
     * @return the text
     */
    public static String compose(Locale locale, String template, String rangePart, String iobPart, int sgv,
                                 String rangeWordOrNull, CharSequence trend, CharSequence age, String iobOrNull,
                                 String delta) {
        String range = rangeWordOrNull == null ? "" : String.format(locale, rangePart, rangeWordOrNull);
        String iob = iobOrNull == null ? "" : String.format(locale, iobPart, iobOrNull);
        return String.format(locale, template, sgv, range, trend, age, iob,
                delta == null ? "" : ", " + delta);
    }
}
