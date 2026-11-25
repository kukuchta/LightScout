package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared strings exactly per CP-06 §7.1 and CP-00A §5.2 / §5.3 (keys and verbatim text; design "{n}"
 * placeholders become positional format arguments) — CON-08, DoD "copy is verbatim".
 */
public class StringsResourceTest {

    private static Map<String, String> expected() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("s01_age_just_now", "Just now");
        m.put("s01_age_min", "%1$d min ago");
        m.put("s01_age_h_min", "%1$d h %2$d min ago");
        m.put("s01_age_no_readings", "No readings");
        m.put("s12_age", "%1$d min");
        m.put("s01_counter_value", "%1$d d %2$d h");
        m.put("s01_reading_empty", "—");
        m.put("glass_value_empty", "---");
        m.put("trend_double_up", "rising quickly");
        m.put("trend_single_up", "rising");
        m.put("trend_forty_five_up", "rising slowly");
        m.put("trend_flat", "steady");
        m.put("trend_forty_five_down", "falling slowly");
        m.put("trend_single_down", "falling");
        m.put("trend_double_down", "falling quickly");
        m.put("trend_unknown", "trend unknown");
        m.put("trend_arrow_double_up", "↑↑");
        m.put("trend_arrow_single_up", "↑");
        m.put("trend_arrow_forty_five_up", "↗");
        m.put("trend_arrow_flat", "→");
        m.put("trend_arrow_forty_five_down", "↘");
        m.put("trend_arrow_single_down", "↓");
        m.put("trend_arrow_double_down", "↓↓");
        m.put("fmt_time_today", "Today %1$s");
        m.put("fmt_time_yesterday", "Yesterday %1$s");
        m.put("fmt_time_date", "%1$s %2$s");
        m.put("pattern_day", "EEE d MMM");
        m.put("pattern_day_month", "d MMM");
        m.put("fmt_period_range", "%1$s – %2$s");
        m.put("a11y_age_ago", "%1$s ago");
        m.put("s02_suffix_mgdl", "mg/dL");
        m.put("s02_suffix_g", "g");
        m.put("s02_suffix_u", "U");
        m.put("s04_value_mgdl", "%1$d mg/dL");
        m.put("s06_snackbar_saved", "Connection saved");
        return m;
    }

    @Test
    public void everyKeyHasTheVerbatimText() {
        assertEquals(expected(), ResXml.strings());
    }

    @Test
    public void spokenAgePluralsMatchDcr04() {
        Map<String, String> minutes = new LinkedHashMap<>();
        minutes.put("one", "%1$d minute");
        minutes.put("other", "%1$d minutes");
        assertEquals(minutes, ResXml.plurals("a11y_minutes"));
        Map<String, String> hours = new LinkedHashMap<>();
        hours.put("one", "%1$d hour");
        hours.put("other", "%1$d hours");
        assertEquals(hours, ResXml.plurals("a11y_hours"));
    }

    @Test
    public void templatesRenderTheDesignExamples() {
        assertEquals("3 min ago", String.format(Locale.ENGLISH, ResXml.string("s01_age_min"), 3L));
        assertEquals("1 h 5 min ago", String.format(Locale.ENGLISH, ResXml.string("s01_age_h_min"), 1L, 5L));
        assertEquals("75 min", String.format(Locale.ENGLISH, ResXml.string("s12_age"), 75L));
        assertEquals("2 d 0 h", String.format(Locale.ENGLISH, ResXml.string("s01_counter_value"), 2L, 0L));
        assertEquals("3 minutes ago", String.format(Locale.ENGLISH, ResXml.string("a11y_age_ago"),
                String.format(Locale.ENGLISH, ResXml.plurals("a11y_minutes").get("other"), 3L)));
    }

    @Test
    public void glyphsAndPatternsAreNotTranslatableWhereFixed() {
        NodeList nodes = ResXml.parse("values/strings.xml").getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            String name = e.getAttribute("name");
            if (name.startsWith("trend_arrow_") || name.equals("s01_reading_empty")) {
                assertEquals(name, "false", e.getAttribute("translatable"));
            }
        }
    }

    @Test
    public void noBannedUiTerms() {
        Pattern banned = Pattern.compile("\\b(NS|SGV|treatment|mute|dismiss)\\b", Pattern.CASE_INSENSITIVE);
        for (Map.Entry<String, String> e : ResXml.strings().entrySet()) {
            assertFalse(e.getKey(), banned.matcher(e.getValue()).find());
        }
        assertTrue(ResXml.strings().size() > 0);
    }
}
