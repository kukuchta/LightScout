package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;

import app.lightscout.domain.model.Trend;

/**
 * §9 "trendWords": each of the 7 directions maps to the arrow glyph and the spoken word of CP-06 §7.1 in the
 * order DoubleUp … DoubleDown; UNKNOWN (NOT COMPUTABLE, RATE OUT OF RANGE, NONE, empty — mapped by CP-01) and
 * {@code null} give "trend unknown" and no arrow / icon. The TtsSpan itself is checked by the instrumented
 * {@code FormattersDeviceTest}.
 */
public class TrendResourcesTest {

    private static final String[][] TABLE = {
        {"DOUBLE_UP", "rising quickly", "↑↑"},
        {"SINGLE_UP", "rising", "↑"},
        {"FORTY_FIVE_UP", "rising slowly", "↗"},
        {"FLAT", "steady", "→"},
        {"FORTY_FIVE_DOWN", "falling slowly", "↘"},
        {"SINGLE_DOWN", "falling", "↓"},
        {"DOUBLE_DOWN", "falling quickly", "↓↓"},
    };

    @Test
    public void sevenDirectionsGiveWordAndGlyph() {
        for (String[] row : TABLE) {
            Trend t = Trend.valueOf(row[0]);
            assertEquals(row[0], row[1], ResXml.string(name(R.string.class, TrendResources.word(t))));
            assertEquals(row[0], row[2], ResXml.string(name(R.string.class, TrendResources.arrow(t))));
        }
    }

    @Test
    public void unknownAndNullGiveTrendUnknownWithoutGlyphOrIcon() {
        for (Trend t : new Trend[] {Trend.UNKNOWN, null}) {
            assertEquals("trend unknown", ResXml.string(name(R.string.class, TrendResources.word(t))));
            assertEquals(0, TrendResources.arrow(t));
            assertEquals(0, TrendIcons.drawableFor(t));
        }
    }

    @Test
    public void everyDirectionHasItsOwnIcon() {
        Map<Trend, String> icons = new EnumMap<>(Trend.class);
        for (String[] row : TABLE) {
            Trend t = Trend.valueOf(row[0]);
            assertNotEquals(0, TrendIcons.drawableFor(t));
            icons.put(t, name(R.drawable.class, TrendIcons.drawableFor(t)));
        }
        assertEquals("ic_trend_double_up", icons.get(Trend.DOUBLE_UP));
        assertEquals("ic_trend_single_up", icons.get(Trend.SINGLE_UP));
        assertEquals("ic_trend_forty_five_up", icons.get(Trend.FORTY_FIVE_UP));
        assertEquals("ic_trend_flat", icons.get(Trend.FLAT));
        assertEquals("ic_trend_forty_five_down", icons.get(Trend.FORTY_FIVE_DOWN));
        assertEquals("ic_trend_single_down", icons.get(Trend.SINGLE_DOWN));
        assertEquals("ic_trend_double_down", icons.get(Trend.DOUBLE_DOWN));
    }

    /** Resource id to its key name through the generated R class. */
    static String name(Class<?> rClass, int id) {
        try {
            for (Field f : rClass.getFields()) {
                if (f.getType() == int.class && f.getInt(null) == id) {
                    return f.getName();
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        throw new AssertionError("no resource with id " + id);
    }
}
