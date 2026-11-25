package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

/**
 * Q-54 value formats (R-82: insulin 1–2 decimals, marker form of {@code entryValue}) and Q-55 stroke roles of
 * {@link GlucosePalette}.
 */
public class ValueTextTest {

    private static final String G = ResXml.string("s02_suffix_g");
    private static final String U = ResXml.string("s02_suffix_u");

    private static Entry entry(EntryType type, Integer glucose, Integer carbs, Integer insulinCenti, String text) {
        return new Entry("id", EntryOrigin.values()[0], type, Instant.parse("2026-10-15T12:00:00Z"), glucose, carbs,
                insulinCenti, null, null, text, null, null, null, false, null);
    }

    @Test
    public void insulinNumbersPerR82() {
        assertEquals("1.5", ValueText.insulinNumber(150));
        assertEquals("1.25", ValueText.insulinNumber(125));
        assertEquals("2.0", ValueText.insulinNumber(200));
        assertEquals("0.05", ValueText.insulinNumber(5));
        assertEquals("0.1", ValueText.insulinNumber(10));
        assertEquals("25.0", ValueText.insulinNumber(2500));
        assertEquals("1.5 U", ValueText.withUnit(ValueText.insulinNumber(150), U));
    }

    @Test
    public void entryValueMarkerForms() {
        assertEquals("118", ValueText.entryValue(entry(EntryType.BG_CHECK, 118, null, null, null), G, U));
        assertEquals("15 g", ValueText.entryValue(entry(EntryType.CARBS, null, 15, null, null), G, U));
        assertEquals("1.5 U", ValueText.entryValue(entry(EntryType.BOLUS, null, null, 150, null), G, U));
        assertEquals("2 U", ValueText.entryValue(entry(EntryType.BOLUS, null, null, 200, null), G, U));
        assertEquals("Occlusion", ValueText.entryValue(entry(EntryType.PUMP_ALERT, null, null, null, "Occlusion"),
                G, U));
        assertEquals("", ValueText.entryValue(entry(EntryType.PUMP_ALERT, null, null, null, null), G, U));
        for (EntryType t : new EntryType[] {EntryType.SITE_CHANGE, EntryType.SENSOR_CHANGE, EntryType.TEMP_BASAL}) {
            assertEquals(t.name(), "", ValueText.entryValue(entry(t, 100, 10, 100, "x"), G, U));
        }
        assertEquals("", ValueText.entryValue(entry(EntryType.BG_CHECK, null, null, null, null), G, U));
        assertEquals("", ValueText.entryValue(null, G, U));
    }

    @Test
    public void exactMilliDoseNumbersPerR91() {
        assertEquals("0.025", ValueText.insulinMilliNumber(25));
        assertEquals("1.234", ValueText.insulinMilliNumber(1234));
        assertEquals("1.25", ValueText.insulinMilliNumber(1250));
        assertEquals("2", ValueText.insulinMilliNumber(2000));
        assertEquals("25", ValueText.insulinMilliNumber(25000));
        assertEquals("0.1", ValueText.insulinMilliNumber(100));
        assertEquals("1.005", ValueText.insulinMilliNumber(1005));
        assertEquals("-0.025", ValueText.insulinMilliNumber(-25));
    }

    @Test
    public void bolusEntryValueUsesAuthoritativeMilliNotRoundedCenti() {
        Entry exact = new Entry("id", EntryOrigin.values()[0], EntryType.BOLUS, Instant.parse("2026-10-15T12:00:00Z"),
                null, null, 123, 1234L, null, null, null, null, null, null, false, null);
        assertEquals("1.234 U", ValueText.entryValue(exact, G, U));
        Entry milliOnly = new Entry("id", EntryOrigin.values()[0], EntryType.BOLUS,
                Instant.parse("2026-10-15T12:00:00Z"), null, null, null, 25L, null, null, null, null, null, null, false,
                null);
        assertEquals("0.025 U", ValueText.entryValue(milliOnly, G, U));
        // Legacy centi-only entry (no milli) falls back to centi * 10.
        assertEquals("1.25 U", ValueText.entryValue(entry(EntryType.BOLUS, null, null, 125, null), G, U));
    }

    @Test
    public void tooltipBgFormTemplate() {
        assertEquals("118 mg/dL", String.format(ResXml.string("s04_value_mgdl"), 118));
        assertEquals("mg/dL", ResXml.string("s02_suffix_mgdl"));
    }

    @Test
    public void paletteExposesStrokeRoles() {
        List<String> names = new ArrayList<>();
        for (RecordComponent rc : GlucosePalette.class.getRecordComponents()) {
            names.add(rc.getName());
        }
        assertEquals(List.of("inRange", "low", "urgentLow", "high", "veryHigh", "threshold", "marker", "pumpAlert",
                "basal", "urgentLowStroke", "veryHighStroke", "basalEdge"), names);
        assertEquals(1.5f, GlucosePalette.STROKE_DP, 0f);
    }
}
