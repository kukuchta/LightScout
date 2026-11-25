package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.TtsSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.statistics.PeriodKind;

/**
 * CMP-31 formatters with real resources (§9 trendWords, readingAge, consumable age, time labels).
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). Compiled in SS-08.
 */
@RunWith(AndroidJUnit4.class)
public class FormattersDeviceTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final Context c = DeviceContexts.themed(false);

    @Test
    public void readingAgeTexts() {
        assertEquals("No readings", Formatters.readingAge(c, null));
        assertEquals("Just now", Formatters.readingAge(c, Duration.ofSeconds(59)));
        assertEquals("3 min ago", Formatters.readingAge(c, Duration.ofMinutes(3).plusSeconds(30)));
        assertEquals("1 h 5 min ago", Formatters.readingAge(c, Duration.ofMinutes(65)));
        assertEquals("75 min", Formatters.widgetAge(c, Duration.ofMinutes(75)));
    }

    @Test
    public void spokenReadingAge() {
        assertEquals("3 minutes ago", Formatters.readingAgeSpoken(c, Duration.ofMinutes(3)).toString());
        assertEquals("1 minute ago", Formatters.readingAgeSpoken(c, Duration.ofMinutes(1)).toString());
        assertEquals("1 hour 5 minutes ago", Formatters.readingAgeSpoken(c, Duration.ofMinutes(65)).toString());
        assertEquals("Just now", Formatters.readingAgeSpoken(c, Duration.ZERO).toString());
        assertEquals("No readings", Formatters.readingAgeSpoken(c, null).toString());
    }

    @Test
    public void trendWordsCarryATtsSpanWithTheWord() {
        String[] words = {"rising quickly", "rising", "rising slowly", "steady", "falling slowly", "falling",
            "falling quickly", "trend unknown"};
        String[] arrows = {"↑↑", "↑", "↗", "→", "↘", "↓", "↓↓", ""};
        Trend[] trends = Trend.values();
        for (int i = 0; i < trends.length; i++) {
            CharSequence spoken = Formatters.trendWords(c, trends[i]);
            assertEquals(words[i], spoken.toString());
            TtsSpan[] spans = ((Spanned) spoken).getSpans(0, spoken.length(), TtsSpan.class);
            assertEquals(1, spans.length);
            assertEquals(TtsSpan.TYPE_TEXT, spans[0].getType());
            assertEquals(words[i], spans[0].getArgs().getString(TtsSpan.ARG_TEXT));
            assertEquals(arrows[i], Formatters.trendArrow(c, trends[i]));
        }
        assertEquals("trend unknown", Formatters.trendWords(c, null).toString());
    }

    @Test
    public void insulinAndEntryValues() {
        assertEquals("1.5 U", Formatters.insulin(c, 150));
        assertEquals("1.25 U", Formatters.insulin(c, 125));
        assertEquals("2.0 U", Formatters.insulin(c, 200));
        assertEquals("0.025 U", Formatters.insulinMilli(c, 25));
        assertEquals("1.234 U", Formatters.insulinMilli(c, 1234));
        assertEquals("1.25 U", Formatters.insulinMilli(c, 1250));
        assertEquals("2 U", Formatters.insulinMilli(c, 2000));
        Entry carbs = new Entry("id", EntryOrigin.values()[0], EntryType.CARBS, Instant.parse("2026-10-15T12:00:00Z"),
                null, 15, null, null, null, null, null, null, null, false, null);
        assertEquals("15 g", Formatters.entryValue(c, carbs));
        assertEquals("118 mg/dL", c.getString(R.string.s04_value_mgdl, 118));
    }

    @Test
    public void consumableAge() {
        assertEquals("2 d 5 h", Formatters.consumableAge(c, Duration.ofHours(53)));
    }

    @Test
    public void timeLabelsFollowTheSystemClockFormat() {
        Instant now = ZonedDateTime.of(2026, 10, 15, 20, 0, 0, 0, WARSAW).toInstant();
        Instant t = ZonedDateTime.of(2026, 10, 15, 14, 32, 0, 0, WARSAW).toInstant();
        String expectedTime = DateFormat.is24HourFormat(c) ? "14:32" : "2:32";
        assertTrue(Formatters.clockTime(c, t, WARSAW).startsWith(expectedTime));
        assertTrue(Formatters.timeLabel(c, t, now, WARSAW).startsWith("Today " + expectedTime));
        assertTrue(Formatters.timeLabel(c, t.minus(Duration.ofDays(1)), now, WARSAW).startsWith("Yesterday "));
        assertTrue(Formatters.timeLabel(c, t.minus(Duration.ofDays(3)), now, WARSAW).startsWith("Mon 12 Oct "));
    }

    /** IR-61: 12-hour zh-TW pattern (may contain B) never makes clockTime / timeLabel throw. */
    @Test
    public void twelveHourTraditionalChinesePatternIsSafe() {
        Locale zhTw = Locale.TRADITIONAL_CHINESE;
        String best = DateFormat.getBestDateTimePattern(zhTw, "hm");
        String safe = TimeLabels.safeClockPattern(best, false, zhTw);
        Instant t = ZonedDateTime.of(2026, 10, 15, 14, 32, 0, 0, WARSAW).toInstant();
        assertTrue(TimeLabels.format(t, WARSAW, safe, zhTw).length() > 0);
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        cfg.setLocale(zhTw);
        Context zh = c.createConfigurationContext(cfg);
        assertTrue(Formatters.clockTime(zh, t, WARSAW).length() > 0);
        assertTrue(Formatters.timeLabel(zh, t, t, WARSAW).length() > 0);
    }

    @Test
    public void periodLabels() {
        Instant start = ZonedDateTime.of(2026, 10, 11, 0, 0, 0, 0, WARSAW).toInstant();
        assertEquals("Sun 11 Oct", Formatters.periodLabel(c,
                new Period(PeriodKind.YESTERDAY, start, start.plus(Duration.ofDays(1))), WARSAW));
        Instant now = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, WARSAW).toInstant();
        assertEquals("5 Oct – 12 Oct", Formatters.periodLabel(c,
                new Period(PeriodKind.LAST_7_DAYS, now.minus(Duration.ofDays(7)), now), WARSAW));
    }
}
