package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

/**
 * TST-03 mapper tests for the {@code treatments} collection (CP-03 §9.2 TM-05…TM-15, TM-18; CP-00A R-40).
 */
public class TreatmentsMapperTest {

    private static final Instant T0 = Instant.ofEpochMilli(Fakes.T0);

    private static List<Entry> map(String fixture) {
        return TreatmentsMapper.map(Fixtures.resultArray(fixture)).items();
    }

    private static Entry one(String json) throws Exception {
        List<Entry> entries = TreatmentsMapper.map(new JSONObject(json));
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    /** TM-05 (M-2, UG-04): v1 carbs-only „<none>” → CARBS, time from created_at, not own, no app. */
    @Test
    public void tm05_v1CarbsOnlyDocument() throws Exception {
        List<Entry> entries = map("FX-05-v1-carbs.json");
        assertEquals(1, entries.size());
        Entry e = entries.get(0);
        assertEquals("fx05-0001", e.id());
        assertEquals(EntryOrigin.TREATMENTS, e.origin());
        assertEquals(EntryType.CARBS, e.type());
        assertEquals(Integer.valueOf(20), e.carbs());
        assertEquals(T0, e.time());
        assertFalse(e.own());
        assertNull(e.app());
        assertEquals("CareLink Follow", e.enteredBy());
        assertEquals("<none>", e.eventTypeRaw());
        assertNull(e.srvModifiedMs());
    }

    /** TM-06 (M-5, R-40): insulin float32 artefacts are rounded to 0.01 U (centi). */
    @Test
    public void tm06_insulinRoundedToCentiUnits() throws Exception {
        List<Entry> entries = map("FX-06-v1-insulin-float32.json");
        assertEquals(EntryType.BOLUS, entries.get(0).type());
        assertEquals(Integer.valueOf(125), entries.get(0).insulinCenti());
        assertEquals(Integer.valueOf(30), entries.get(1).insulinCenti());
    }

    @Test
    public void dcr09_importedBolusKeepsCentiRulesAndAddsMilliTooltipPrecision() throws Exception {
        Entry noisy = one("{\"identifier\":\"n\",\"eventType\":\"Correction Bolus\","
                + "\"insulin\":2.37499999987,\"created_at\":\"2030-01-01T00:00:00Z\"}");
        assertEquals(Integer.valueOf(237), noisy.insulinCenti());
        assertEquals(Long.valueOf(2375), noisy.insulinMilli());

        assertImportedDose("1.0000021", 100, 1000);
        assertImportedDose("1.30000032", 130, 1300);
        assertImportedDose("2.450000023", 245, 2450);
    }

    @Test
    public void inferredSubCentiBolusIsRetainedForDisplay() throws Exception {
        List<Entry> entries = TreatmentsMapper.map(new JSONObject("{\"identifier\":\"tiny\","
                + "\"eventType\":\"<none>\",\"insulin\":0.001,\"created_at\":\"2030-01-01T00:00:00Z\"}"));
        assertEquals(1, entries.size());
        assertEquals(EntryType.BOLUS, entries.get(0).type());
        assertEquals(Integer.valueOf(0), entries.get(0).insulinCenti());
        assertEquals(Long.valueOf(1), entries.get(0).insulinMilli());
    }

    private static void assertImportedDose(String dose, int expectedCenti, long expectedMilli) throws Exception {
        Entry entry = one("{\"identifier\":\"dose\",\"eventType\":\"Bolus\",\"insulin\":" + dose
                + ",\"created_at\":\"2030-01-01T00:00:00Z\"}");
        assertEquals(Integer.valueOf(expectedCenti), entry.insulinCenti());
        assertEquals(Long.valueOf(expectedMilli), entry.insulinMilli());
    }

    /** UG-03 (FX-07): two documents with the same created_at stay two entries with their own ids. */
    @Test
    public void sameTimestampPairStaysTwoEntries() throws Exception {
        List<Entry> entries = map("FX-07-same-timestamp-pair.json");
        assertEquals(2, entries.size());
        assertEquals("fx07-carbs", entries.get(0).id());
        assertEquals(EntryType.CARBS, entries.get(0).type());
        assertEquals("fx07-insulin", entries.get(1).id());
        assertEquals(Integer.valueOf(210), entries.get(1).insulinCenti());
    }

    /** TM-07 (M-2): „<none>” with carbs and insulin → {id}#carbs (CARBS) + {id}#insulin (BOLUS), not own. */
    @Test
    public void tm07_noneWithBothValuesSplitsIntoTwoReadOnlyEntries() throws Exception {
        List<Entry> entries = map("FX-08-none-carbs-and-insulin.json");
        assertEquals(2, entries.size());
        assertEquals("fx08-0001#carbs", entries.get(0).id());
        assertEquals(EntryType.CARBS, entries.get(0).type());
        assertEquals(Integer.valueOf(30), entries.get(0).carbs());
        assertNull(entries.get(0).insulinCenti());
        assertEquals("fx08-0001#insulin", entries.get(1).id());
        assertEquals(EntryType.BOLUS, entries.get(1).type());
        assertEquals(Integer.valueOf(250), entries.get(1).insulinCenti());
        assertFalse(entries.get(0).own());
        assertFalse(entries.get(1).own());
    }

    /** A split document is never own, even if it claims app = LightScout (LightScout writes single-type docs). */
    @Test
    public void splitIsNeverOwn() throws Exception {
        List<Entry> entries = TreatmentsMapper.map(new JSONObject("{\"identifier\":\"s\",\"eventType\":\"<none>\","
                + "\"created_at\":\"2030-01-01T00:00:00Z\",\"carbs\":5,\"insulin\":1,\"app\":\"LightScout\","
                + "\"srvModified\":5}"));
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).own());
        assertNull(entries.get(1).srvModifiedMs());
    }

    /** TM-08 (M-1): Meal Bolus with carbs + insulin → carbs part CARBS, insulin part BOLUS. */
    @Test
    public void tm08_mealBolusSplits() throws Exception {
        List<Entry> entries = map("FX-11-meal-bolus.json");
        assertEquals(List.of("fx11-0001#carbs", "fx11-0001#insulin"), entries.stream().map(Entry::id).toList());
        assertEquals(EntryType.CARBS, entries.get(0).type());
        assertEquals(Integer.valueOf(40), entries.get(0).carbs());
        assertEquals(EntryType.BOLUS, entries.get(1).type());
        assertEquals(Integer.valueOf(320), entries.get(1).insulinCenti());
        assertEquals("Meal Bolus", entries.get(1).eventTypeRaw());
    }

    /** M-1: Meal Bolus with only one value is one entry with the plain id. */
    @Test
    public void mealBolusWithOneValueIsSingle() throws Exception {
        Entry e = one("{\"identifier\":\"mb\",\"eventType\":\"Meal Bolus\",\"insulin\":1.5,"
                + "\"created_at\":\"2030-01-01T00:00:00Z\"}");
        assertEquals("mb", e.id());
        assertEquals(EntryType.BOLUS, e.type());
    }

    /** TM-09 (05 §4.2, UG-04): created_at → date → timestamp precedence. */
    @Test
    public void tm09_timePrecedence() throws Exception {
        assertEquals(T0, one("{\"identifier\":\"t1\",\"eventType\":\"Site Change\","
                + "\"created_at\":\"2030-01-01T00:00:00.000Z\",\"date\":1000,\"timestamp\":2000}").time());
        assertEquals(Instant.ofEpochMilli(1000), one("{\"identifier\":\"t2\",\"eventType\":\"Site Change\","
                + "\"date\":1000,\"timestamp\":2000}").time());
        assertEquals(Instant.ofEpochMilli(2000), one("{\"identifier\":\"t3\",\"eventType\":\"Site Change\","
                + "\"timestamp\":2000}").time());
        assertEquals(T0, one("{\"identifier\":\"t4\",\"eventType\":\"Site Change\","
                + "\"created_at\":\"2030-01-01T02:00:00.000+02:00\"}").time());
    }

    /** TM-10 (M-1): Sensor Start / Sensor Change → SENSOR_CHANGE, Site Change → SITE_CHANGE, BG Check + glucose. */
    @Test
    public void tm10_knownTypes() throws Exception {
        List<Entry> entries = map("FX-12-known-types.json");
        assertEquals(List.of(EntryType.SENSOR_CHANGE, EntryType.SITE_CHANGE, EntryType.SENSOR_CHANGE,
                EntryType.BG_CHECK), entries.stream().map(Entry::type).toList());
        assertEquals(Integer.valueOf(98), entries.get(3).glucose());
    }

    /** M-1: Correction Bolus, Bolus → BOLUS; Carb Correction → CARBS (FX-09, enteredBy kept raw for M-6). */
    @Test
    public void bolusAndCarbCorrection() throws Exception {
        assertEquals(EntryType.BOLUS, one("{\"identifier\":\"b1\",\"eventType\":\"Correction Bolus\","
                + "\"insulin\":0.5,\"created_at\":\"2030-01-01T00:00:00Z\"}").type());
        assertEquals(EntryType.BOLUS, one("{\"identifier\":\"b2\",\"eventType\":\"Bolus\","
                + "\"insulin\":0.5,\"created_at\":\"2030-01-01T00:00:00Z\"}").type());
        Entry careportal = map("FX-09-careportal-carbs.json").get(0);
        assertEquals(EntryType.CARBS, careportal.type());
        assertEquals(Integer.valueOf(12), careportal.carbs());
        assertEquals("undefined", careportal.enteredBy());
    }

    /** TM-11 (M-4, 05 §4.2): own document → own, srvModified set; a foreign doc's srvModified is not used. */
    @Test
    public void tm11_ownershipAndSrvModified() throws Exception {
        List<Entry> entries = map("FX-10-own-v3.json");
        Entry own = entries.get(0);
        assertTrue(own.own());
        assertEquals("LightScout", own.app());
        assertEquals(Long.valueOf(1_893_456_001_234L), own.srvModifiedMs());
        assertEquals(Integer.valueOf(118), own.glucose());
        Entry foreign = entries.get(1);
        assertFalse(foreign.own());
        assertNull(foreign.srvModifiedMs());
    }

    /** TM-12 (ADR-031, R-40): Temp Basal rate from absolute (else rate) × 1000 rounded; percent only → null. */
    @Test
    public void tm12_tempBasal() throws Exception {
        List<Entry> entries = map("FX-13-temp-basal.json");
        assertEquals(3, entries.size());
        for (Entry e : entries) {
            assertEquals(EntryType.TEMP_BASAL, e.type());
        }
        assertEquals(Integer.valueOf(850), entries.get(0).basalRateMilli());
        assertEquals(Integer.valueOf(30), entries.get(0).durationMin());
        assertEquals(Integer.valueOf(1235), entries.get(1).basalRateMilli());
        assertEquals(Integer.valueOf(45), entries.get(1).durationMin());
        assertNull(entries.get(2).basalRateMilli());
        assertEquals(Integer.valueOf(60), entries.get(2).durationMin());
    }

    /** TM-13 (UG-10): Pump Alert and Announcement with notes → PUMP_ALERT, text = notes; without notes → hidden. */
    @Test
    public void tm13_pumpAlerts() throws Exception {
        List<Entry> entries = map("FX-14-pump-alerts.json");
        assertEquals(2, entries.size());
        assertEquals(EntryType.PUMP_ALERT, entries.get(0).type());
        assertEquals("Synthetic alert text A", entries.get(0).text());
        assertEquals(EntryType.PUMP_ALERT, entries.get(1).type());
        assertEquals("Synthetic alert text B", entries.get(1).text());
    }

    /** TM-14 (M-7): unknown eventType without values not returned; unknown fields ignored; with carbs → CARBS. */
    @Test
    public void tm14_unknownTypes() throws Exception {
        Mapped<Entry> m = TreatmentsMapper.map(Fixtures.resultArray("FX-15-unknown.json"));
        assertEquals(0, m.skipped());
        assertEquals(1, m.items().size());
        assertEquals("fx15-unknown-with-carbs", m.items().get(0).id());
        assertEquals(EntryType.CARBS, m.items().get(0).type());
    }

    /** R-40 (G-11): missing / „<none>” / unknown eventType with glucose only → BG_CHECK (read-only). */
    @Test
    public void glucoseOnlyIsBgCheck() throws Exception {
        Entry none = one("{\"identifier\":\"g1\",\"eventType\":\"<none>\",\"glucose\":105,"
                + "\"created_at\":\"2030-01-01T00:00:00Z\"}");
        assertEquals(EntryType.BG_CHECK, none.type());
        assertEquals(Integer.valueOf(105), none.glucose());
        assertEquals(EntryType.BG_CHECK, one("{\"identifier\":\"g2\",\"glucose\":105,"
                + "\"created_at\":\"2030-01-01T00:00:00Z\"}").type());
        assertEquals(EntryType.BG_CHECK, one("{\"identifier\":\"g3\",\"eventType\":\"Note\",\"glucose\":105,"
                + "\"created_at\":\"2030-01-01T00:00:00Z\"}").type());
    }

    /** TM-15 (M-8, SR-29): invalid treatments skipped and counted, valid ones returned. */
    @Test
    public void tm15_malformedTreatmentsSkippedAndCounted() throws Exception {
        Mapped<Entry> m = TreatmentsMapper.map(Fixtures.resultArray("FX-16-malformed-treatments.json"));
        assertEquals(List.of("fx16t-ok", "fx16t-ok-2"), m.items().stream().map(Entry::id).toList());
        assertEquals(5, m.skipped());
    }

    /** TM-18 (M-11, SR-29): enteredBy / text ≤ 200 chars, no control characters. */
    @Test
    public void tm18_hostileFreeTextIsCappedAndStripped() throws Exception {
        Entry e = map("FX-19-hostile-text.json").get(0);
        for (String s : new String[] {e.enteredBy(), e.text()}) {
            assertTrue(s.length() <= 200);
            s.codePoints().forEach(cp -> {
                assertFalse("control char " + cp, Character.isISOControl(cp));
                assertFalse("format char " + cp, Character.getType(cp) == Character.FORMAT); // IR-28, IR-35
            });
        }
        assertTrue(e.enteredBy().startsWith("UploaderName With[31mEscape <script>"));
        assertTrue(e.text().startsWith("Line1 Line2  <b>bold</b>"));
    }

    /** isValid:false documents in a search are not shown. */
    @Test
    public void invalidatedDocumentsAreNotShown() throws Exception {
        Mapped<Entry> m = TreatmentsMapper.map(new org.json.JSONArray().put(new JSONObject(
                "{\"identifier\":\"x\",\"eventType\":\"Site Change\",\"created_at\":\"2030-01-01T00:00:00Z\","
                        + "\"isValid\":false}")));
        assertTrue(m.items().isEmpty());
        assertEquals(0, m.skipped());
    }
}
