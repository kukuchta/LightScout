package app.lightscout.alarm;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TtsSpan;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.model.Trend;
import app.lightscout.ui.core.Formatters;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Renders the S-09 and S-11 alarm copy (§7.1, §7.2, §7.4; CP-00A R-80, R-82, R-83) from an {@code AlarmView}. All text
 * comes from string resources; ages, times and trend words come from CMP-31 {@link Formatters}. Trend arrows in
 * notification titles carry a {@link TtsSpan} with the spoken trend word (M3-34). Safe on any thread.
 */
final class AlarmTextRenderer {

    private final Context context;

    /**
     * Creates the renderer.
     *
     * @param context a context with the app resources
     */
    AlarmTextRenderer(Context context) {
        this.context = context;
    }

    /**
     * C-S09-01 title.
     *
     * @param v view
     * @return „Urgent low” … „LightScout is not running”
     */
    String title(AlarmView v) {
        return context.getString(AlarmTexts.titleRes(v.type()));
    }

    /**
     * C-S09-02 reading: the glucose value, or „—” for No data / Not running (or a missing value).
     *
     * @param v view
     * @return the text
     */
    String reading(AlarmView v) {
        if (AlarmTexts.showsReading(v.type()) && v.sgv() != null) {
            return String.valueOf(v.sgv());
        }
        return context.getString(app.lightscout.ui.core.R.string.s01_reading_empty);
    }

    /**
     * Tells whether the reading has a value (unit and trend shown).
     *
     * @param v view
     * @return {@code true} for glucose alarms with a value
     */
    boolean hasReading(AlarmView v) {
        return AlarmTexts.showsReading(v.type()) && v.sgv() != null;
    }

    /**
     * C-S09-03 first line: reading age, „No new readings for {n} min” or „LightScout stopped checking at {time}”.
     *
     * @param v    view
     * @param now  now (IF-15)
     * @param zone zone (IF-15)
     * @return the text, or {@code null} when the No data anchor is unknown
     */
    String ageLine(AlarmView v, Instant now, ZoneId zone) {
        switch (AlarmTexts.kind(v.type())) {
            case NO_DATA:
                Instant since = v.dataLossSince() != null ? v.dataLossSince() : v.readingTime();
                if (since == null) {
                    return null;
                }
                return context.getString(R.string.s09_no_new_readings, AlarmTexts.minutesSince(since, now));
            case WATCHDOG:
                return context.getString(R.string.s09_stopped_checking, clock(v.lastCheck(), zone));
            default:
                return Formatters.readingAge(context, AlarmTexts.age(v.readingTime(), now));
        }
    }

    /**
     * C-S09-03 cause line (No data only, DS:D-024).
     *
     * @param v view
     * @return the cause text, or {@code null}
     */
    String causeLine(AlarmView v) {
        if (v.type() != AlarmType.NO_DATA) {
            return null;
        }
        int res = AlarmTexts.causeRes(v.cause());
        return res == 0 ? null : context.getString(res);
    }

    /**
     * C-S09-04 / C-S11-02 label „Snooze {n} min”.
     *
     * @param v view
     * @return the label
     */
    String snoozeLabel(AlarmView v) {
        return context.getString(R.string.s09_snooze, v.snoozeMinutes());
    }

    /**
     * C-S11-02 action label „Snooze {n} min”.
     *
     * @param v view
     * @return the label
     */
    String snoozeAction(AlarmView v) {
        return context.getString(R.string.s11_snooze_action, v.snoozeMinutes());
    }

    /**
     * TalkBack announcement on open (GIR-7): „Alarm. Urgent low, 52 milligrams per decilitre, falling”; No data /
     * Not running: „Alarm. {title}, {age or cause line}” (CP-00A §5.3).
     *
     * @param v    view
     * @param now  now
     * @param zone zone
     * @return the announcement
     */
    String announcement(AlarmView v, Instant now, ZoneId zone) {
        if (hasReading(v)) {
            return context.getString(R.string.s09_open_a11y, title(v), v.sgv(), trendWord(v.trend()));
        }
        StringBuilder detail = new StringBuilder();
        String age = ageLine(v, now, zone);
        if (age != null) {
            detail.append(age);
        }
        String cause = causeLine(v);
        if (cause != null) {
            if (detail.length() > 0) {
                detail.append(", ");
            }
            detail.append(cause);
        }
        return context.getString(R.string.s09_open_a11y_status, title(v), detail.toString());
    }

    /**
     * C-S11-01 title: „Urgent low · 52 mg/dL ↓” · „No data · 23 min” · „LightScout is not running”.
     *
     * @param v   view
     * @param now now
     * @return the title; a trend arrow is spoken as its word (TtsSpan)
     */
    CharSequence notificationTitle(AlarmView v, Instant now) {
        switch (AlarmTexts.kind(v.type())) {
            case NO_DATA:
                Instant since = v.dataLossSince() != null ? v.dataLossSince() : v.readingTime();
                if (since == null) {
                    return title(v);
                }
                return context.getString(R.string.s11_no_data_title, AlarmTexts.minutesSince(since, now));
            case WATCHDOG:
                return context.getString(R.string.s11_watchdog_title);
            default:
                return glucoseTitle(v);
        }
    }

    /**
     * C-S11-01 text: „Snooze or open LightScout” · No data cause line · „Last check 02:14. Open the app …”.
     *
     * @param v    view
     * @param zone zone
     * @return the text
     */
    String notificationText(AlarmView v, ZoneId zone) {
        switch (AlarmTexts.kind(v.type())) {
            case NO_DATA:
                String cause = causeLine(v);
                return cause != null ? cause : context.getString(R.string.s11_alarm_text);
            case WATCHDOG:
                return context.getString(R.string.s11_watchdog_text, clock(v.lastCheck(), zone));
            default:
                return context.getString(R.string.s11_alarm_text);
        }
    }

    private CharSequence glucoseTitle(AlarmView v) {
        String sgv = reading(v);
        String arrow = Formatters.trendArrow(context, v.trend());
        String text = context.getString(R.string.s11_alarm_title, title(v), sgv, arrow).trim();
        if (arrow.isEmpty()) {
            return text;
        }
        SpannableStringBuilder b = new SpannableStringBuilder(text);
        int start = text.lastIndexOf(arrow);
        if (start >= 0) {
            b.setSpan(new TtsSpan.TextBuilder(trendWord(v.trend())).build(), start, start + arrow.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private String trendWord(Trend t) {
        return Formatters.trendWords(context, t).toString();
    }

    private String clock(Instant t, ZoneId zone) {
        if (t == null) {
            return context.getString(app.lightscout.ui.core.R.string.s01_reading_empty);
        }
        return Formatters.clockTime(context, t, zone);
    }
}
