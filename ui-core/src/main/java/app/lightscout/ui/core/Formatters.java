package app.lightscout.ui.core;

import android.content.Context;
import android.content.res.Resources;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.TtsSpan;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.statistics.Period;

/**
 * CMP-31 shared formatters (CP-06 §7.1, §8.1; CP-00A R-12, R-82, R-83).
 *
 * <p>All text comes from {@code :ui-core} string resources; "now" and the zone are passed in by the caller, who
 * takes them from IF-15 {@code Clock} (T-1, T-2). Times follow the system 12/24-hour setting; the locale is the one
 * of the resource configuration. Safe on any thread (resource reads only).
 */
public final class Formatters {

    private Formatters() {
    }

    /**
     * Visible reading age (S-01 C-S01-10): "Just now" below 60 s (negative counts as 0), "{n} min ago" for 1 to
     * 59 min, "{h} h {m} min ago" from 60 min (floor); {@code null} gives "No readings".
     *
     * @param c   context
     * @param age reading age, {@code null} when there is no reading
     * @return the text
     */
    public static String readingAge(Context c, Duration age) {
        if (age == null) {
            return c.getString(R.string.s01_age_no_readings);
        }
        AgeText.Parts parts = AgeText.reading(age);
        switch (parts.kind()) {
            case JUST_NOW:
                return c.getString(R.string.s01_age_just_now);
            case MINUTES:
                return c.getString(R.string.s01_age_min, parts.minutes());
            default:
                return c.getString(R.string.s01_age_h_min, parts.hours(), parts.minutes());
        }
    }

    /**
     * Spoken reading age for TalkBack (DCR-04.6 to .8): "3 minutes ago", "1 hour 5 minutes ago"; same boundaries
     * as {@link #readingAge}; below 60 s "Just now"; {@code null} gives "No readings".
     *
     * @param c   context
     * @param age reading age, {@code null} when there is no reading
     * @return the spoken text
     */
    public static CharSequence readingAgeSpoken(Context c, Duration age) {
        if (age == null) {
            return c.getString(R.string.s01_age_no_readings);
        }
        AgeText.Parts parts = AgeText.reading(age);
        Resources r = c.getResources();
        switch (parts.kind()) {
            case JUST_NOW:
                return c.getString(R.string.s01_age_just_now);
            case MINUTES:
                return c.getString(R.string.a11y_age_ago, minutes(r, parts.minutes()));
            default:
                String hours = r.getQuantityString(R.plurals.a11y_hours, (int) parts.hours(), parts.hours());
                return c.getString(R.string.a11y_age_ago, hours + " " + minutes(r, parts.minutes()));
        }
    }

    /**
     * Widget age (S-12 C-S12-03): "{n} min" with the total minutes (floor, negative counts as 0; e.g. "75 min").
     *
     * @param c   context
     * @param age reading age, {@code null} gives an empty string (the widget shows no age)
     * @return the text
     */
    public static String widgetAge(Context c, Duration age) {
        if (age == null) {
            return "";
        }
        return c.getString(R.string.s12_age, AgeText.totalMinutes(age));
    }

    /**
     * Spoken trend word with a {@link TtsSpan} (M3-34): "rising quickly" … "falling quickly", "trend unknown" for
     * {@link Trend#UNKNOWN} or {@code null}.
     *
     * @param c context
     * @param t trend
     * @return the word, spanned with a TtsSpan carrying the same word
     */
    public static CharSequence trendWords(Context c, Trend t) {
        String word = c.getString(TrendResources.word(t));
        SpannableString spoken = new SpannableString(word);
        spoken.setSpan(new TtsSpan.TextBuilder(word).build(), 0, spoken.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spoken;
    }

    /**
     * Trend arrow glyph for text surfaces (↑↑ ↑ ↗ → ↘ ↓ ↓↓).
     *
     * @param c context
     * @param t trend
     * @return the glyph, or an empty string for {@link Trend#UNKNOWN} / {@code null} (no glyph)
     */
    public static String trendArrow(Context c, Trend t) {
        int id = TrendResources.arrow(t);
        return id == 0 ? "" : c.getString(id);
    }

    /**
     * Consumable age "{d} d {h} h" (T-3, DS:D-052): absolute duration, floor to whole hours.
     *
     * @param c   context
     * @param age consumable age, {@code null} gives an empty string (no change found)
     * @return the text, e.g. "2 d 5 h" for 53 h
     */
    public static String consumableAge(Context c, Duration age) {
        if (age == null) {
            return "";
        }
        long[] dh = AgeText.daysHours(age);
        return c.getString(R.string.s01_counter_value, dh[0], dh[1]);
    }

    /**
     * Time label (S-02, chart tooltip): "Today 14:32" / "Yesterday 21:10" / "Mon 12 Oct 08:05"; local dates are
     * decided in {@code z} (T-2).
     *
     * @param c   context
     * @param t   instant shown
     * @param now current instant (IF-15)
     * @param z   current zone (IF-15)
     * @return the label
     */
    public static String timeLabel(Context c, Instant t, Instant now, ZoneId z) {
        return TimeLabels.label(t, now, z, locale(c), timePattern(c),
                c.getString(R.string.fmt_time_today), c.getString(R.string.fmt_time_yesterday),
                c.getString(R.string.fmt_time_date), c.getString(R.string.pattern_day));
    }

    /**
     * Clock time "14:32" in the system 12/24-hour format.
     *
     * @param c context
     * @param t instant
     * @param z current zone
     * @return the time
     */
    public static String clockTime(Context c, Instant t, ZoneId z) {
        return TimeLabels.format(t, z, timePattern(c), locale(c));
    }

    /**
     * S-03 period text: "Sun 11 Oct" for Yesterday, "5 Oct – 12 Oct" for n days (R-78).
     *
     * @param c context
     * @param p period
     * @param z current zone
     * @return the label
     */
    public static String periodLabel(Context c, Period p, ZoneId z) {
        return TimeLabels.period(p.kind(), p.start(), p.end(), z, locale(c),
                c.getString(R.string.pattern_day), c.getString(R.string.pattern_day_month),
                c.getString(R.string.fmt_period_range));
    }

    /**
     * Insulin with unit for IOB and legacy centi callers (R-82): "1.5 U", "1.25 U", "2.0 U" (1–2 decimals,
     * trailing zero after the first decimal removed). Never pass a milli value here; treatment doses use
     * {@link #insulinMilli}.
     *
     * @param c     context
     * @param centi insulin in centi-units (0.01 U)
     * @return the text
     */
    public static String insulin(Context c, int centi) {
        return ValueText.withUnit(ValueText.insulinNumber(centi), c.getString(R.string.s02_suffix_u));
    }

    /**
     * Exact treatment dose with unit (R-91): up to three decimals, trailing zeros trimmed ("0.025 U", "1.234 U",
     * "1.25 U", "2 U").
     *
     * @param c     context
     * @param milli dose in milli-units (0.001 U)
     * @return the text
     */
    public static String insulinMilli(Context c, long milli) {
        return ValueText.withUnit(ValueText.insulinMilliNumber(milli), c.getString(R.string.s02_suffix_u));
    }

    /**
     * Marker / tooltip / similar-entry value in the marker form (R-82, Q-54, R-91): BG "{n}" (the tooltip's
     * "{n} mg/dL" uses {@code s04_value_mgdl} at the call site); carbs "{n} g"; bolus as {@link #insulinMilli} from
     * {@code Entry.insulinMilli} (centi * 10 for legacy values without milli); pump
     * alert the sanitised Nightscout text ("" if none); site change, sensor change, temp basal "".
     *
     * @param c context
     * @param e entry
     * @return the value text
     */
    public static String entryValue(Context c, Entry e) {
        return ValueText.entryValue(e, c.getString(R.string.s02_suffix_g), c.getString(R.string.s02_suffix_u));
    }

    private static String minutes(Resources r, long n) {
        return r.getQuantityString(R.plurals.a11y_minutes, (int) n, n);
    }

    private static Locale locale(Context c) {
        return c.getResources().getConfiguration().getLocales().get(0);
    }

    private static String timePattern(Context c) {
        boolean is24 = DateFormat.is24HourFormat(c);
        Locale locale = locale(c);
        String best;
        try {
            best = DateFormat.getBestDateTimePattern(locale, is24 ? "Hm" : "hm");
        } catch (RuntimeException e) {
            best = null;
        }
        return TimeLabels.safeClockPattern(best, is24, locale);
    }
}
