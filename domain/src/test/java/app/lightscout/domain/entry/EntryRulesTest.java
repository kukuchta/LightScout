package app.lightscout.domain.entry;

import app.lightscout.domain.FakeClock;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TST-01 ENT-1…ENT-6 (CP-01 §8.7 / §9) plus R-64 parsing, R-75 exclusion and M-6 source labels.
 */
public class EntryRulesTest {

    private final FakeClock clock = new FakeClock(Instant.parse("2026-10-15T12:00:00Z"));
    private final EntryRules rules = new EntryRules();

    private EntryDraft draft(EntryType type, Integer value, Instant time) {
        return new EntryDraft("draft-id", EntryMode.CREATE, type, value, time, null);
    }

    private ValidationResult validate(EntryType type, Integer value) {
        return rules.validate(draft(type, value, null), clock.now());
    }

    private static void assertCode(ValidationResult r, ValidationField field, ValidationCode code) {
        assertEquals(List.of(new Violation(field, code, List.of())), r.violations());
        assertFalse(r.valid());
    }

    private Entry entry(String id, EntryOrigin origin, EntryType type, Instant time, boolean own, String app) {
        return new Entry(id, origin, type, time, null, null, null, null, null, null, null, app, null, own, null);
    }

    private Entry entry(String id, EntryType type, Instant time) {
        return entry(id, EntryOrigin.TREATMENTS, type, time, false, "xDrip");
    }

    private Entry enteredBy(String enteredBy, boolean own) {
        return new Entry("e", EntryOrigin.TREATMENTS, EntryType.CARBS, clock.now(), null, 10, null, null, null, null,
                enteredBy, null, null, own, null);
    }

    @Test
    public void ent1_bgAndCarbsRanges() {
        assertCode(validate(EntryType.BG_CHECK, 19), ValidationField.VALUE, ValidationCode.BG_RANGE);
        assertTrue(validate(EntryType.BG_CHECK, 20).valid());
        assertTrue(validate(EntryType.BG_CHECK, 600).valid());
        assertCode(validate(EntryType.BG_CHECK, 601), ValidationField.VALUE, ValidationCode.BG_RANGE);
        assertCode(validate(EntryType.BG_CHECK, null), ValidationField.VALUE, ValidationCode.BG_RANGE);

        assertCode(validate(EntryType.CARBS, 0), ValidationField.VALUE, ValidationCode.CARBS_RANGE);
        assertTrue(validate(EntryType.CARBS, 1).valid());
        assertTrue(validate(EntryType.CARBS, 250).valid());
        assertCode(validate(EntryType.CARBS, 251), ValidationField.VALUE, ValidationCode.CARBS_RANGE);
    }

    @Test
    public void ent2_bolusMilliRangeWithoutStepGrid() {
        for (int invalid : new int[] {0, 24, 25001, Integer.MAX_VALUE}) {
            assertCode(validate(EntryType.BOLUS, invalid), ValidationField.VALUE, ValidationCode.BOLUS_RANGE);
        }
        for (int valid : new int[] {25, 1230, 1234, 25000}) {
            assertTrue(validate(EntryType.BOLUS, valid).valid());
        }
        assertCode(validate(EntryType.BOLUS, null), ValidationField.VALUE, ValidationCode.BOLUS_RANGE);
    }

    @Test
    public void r91_parseExactMilliSyntaxAndOverflow() {
        String[] text = {"0", "0.024", "0.025", "25", "25.001", "1.23", "1.234", "1,234",
                " 1.234\t", "0001.2", "2147483.647"};
        int[] milli = {0, 24, 25, 25000, 25001, 1230, 1234, 1234, 1234, 1200, Integer.MAX_VALUE};
        for (int i = 0; i < text.length; i++) {
            assertEquals(text[i], OptionalInt.of(milli[i]), EntryRules.parseInsulinMilli(text[i]));
        }
        for (String bad : new String[] {null, "", " ", "+1", "-1", ".1", ",1", "1.", "1,",
                "1.2340", "1,000.5", "1 000", "1_000", "1e0", "NaN", "١", "1.2.3",
                "2147483.648", "2147484", "9223372036854775807", "999999999999999999999"}) {
            assertEquals(bad, OptionalInt.empty(), EntryRules.parseInsulinMilli(bad));
        }
    }

    @Test
    public void r91_modelKeepsMilliAuthorityAndLegacyCentiFallback() {
        Entry legacy = new Entry("legacy", EntryOrigin.TREATMENTS, EntryType.BOLUS, clock.now(),
                null, null, 123, null, null, null, null, null, null, true, null);
        assertEquals(Long.valueOf(1230), legacy.insulinMilli());
        Entry imported = new Entry("imported", EntryOrigin.TREATMENTS, EntryType.BOLUS, clock.now(),
                null, null, 123, 1234L, null, null, null, null, null, null, false, null);
        assertEquals(Long.valueOf(1234), imported.insulinMilli());
        assertEquals(Integer.valueOf(123), imported.insulinCenti());
    }

    @Test
    public void ent3_timeLimits() {
        Instant now = clock.now();
        assertTrue(rules.validate(draft(EntryType.CARBS, 10, now.plus(Duration.ofMinutes(1))), now).valid());
        assertCode(rules.validate(draft(EntryType.CARBS, 10, now.plus(Duration.ofMinutes(1)).plusSeconds(1)), now),
                ValidationField.TIME, ValidationCode.TIME_FUTURE);
        assertTrue(rules.validate(draft(EntryType.CARBS, 10, now.minus(Duration.ofDays(7))), now).valid());
        assertCode(rules.validate(draft(EntryType.CARBS, 10, now.minus(Duration.ofDays(7)).minusSeconds(1)), now),
                ValidationField.TIME, ValidationCode.TIME_TOO_OLD);
        assertTrue("null time = Now", rules.validate(draft(EntryType.CARBS, 10, null), now).valid());
    }

    @Test
    public void ent4_typeRequired_siteWithoutValue_nonLoggableTypes() {
        assertCode(validate(null, null), ValidationField.TYPE, ValidationCode.TYPE_REQUIRED);
        assertTrue(validate(EntryType.SITE_CHANGE, null).valid());
        assertTrue(validate(EntryType.SENSOR_CHANGE, null).valid());
        assertCode(validate(EntryType.TEMP_BASAL, null), ValidationField.TYPE, ValidationCode.TYPE_REQUIRED);
        assertCode(validate(EntryType.PUMP_ALERT, null), ValidationField.TYPE, ValidationCode.TYPE_REQUIRED);
    }

    @Test
    public void validate_collectsSeveralViolations() {
        ValidationResult r = rules.validate(draft(EntryType.BG_CHECK, 5, clock.now().plus(Duration.ofHours(1))),
                clock.now());
        assertEquals(2, r.violations().size());
        assertEquals(ValidationCode.BG_RANGE, r.violations().get(0).code());
        assertEquals(ValidationCode.TIME_FUTURE, r.violations().get(1).code());
    }

    @Test
    public void ent5_m4_editability() {
        Instant t = clock.now();
        assertTrue(rules.isEditable(entry("uuid-1", EntryOrigin.TREATMENTS, EntryType.CARBS, t, true, "LightScout")));
        assertFalse(rules.isEditable(entry("x-1", EntryOrigin.TREATMENTS, EntryType.CARBS, t, false, "xDrip")));
        assertFalse(rules.isEditable(entry("doc#carbs", EntryOrigin.TREATMENTS, EntryType.CARBS, t, false, "AAPS")));
        assertFalse(rules.isEditable(entry("doc#insulin", EntryOrigin.TREATMENTS, EntryType.BOLUS, t, true, "x")));
        assertFalse(rules.isEditable(entry("mbg-1", EntryOrigin.ENTRIES_MBG, EntryType.BG_CHECK, t, false, null)));
    }

    @Test
    public void ent6_f10_similarWithin10MinSameType() {
        Instant t = clock.now();
        EntryDraft d = draft(EntryType.CARBS, 20, t);
        Entry eightBefore = entry("a", EntryType.CARBS, t.minus(Duration.ofMinutes(8)));
        Entry tenBefore = entry("b", EntryType.CARBS, t.minus(Duration.ofMinutes(10)));
        Entry elevenBefore = entry("c", EntryType.CARBS, t.minus(Duration.ofMinutes(11)));
        Entry otherType = entry("d", EntryType.BOLUS, t.minus(Duration.ofMinutes(2)));
        Entry eightAfter = entry("e", EntryType.CARBS, t.plus(Duration.ofMinutes(8)));
        List<Entry> similar = rules.findSimilar(d, List.of(eightBefore, tenBefore, elevenBefore, otherType, eightAfter));
        assertEquals(List.of(eightBefore, tenBefore, eightAfter), similar);
    }

    @Test
    public void r75_findSimilar_excludesEntryBeingEdited() {
        Instant t = clock.now();
        EntryDraft edit = new EntryDraft("uuid-1", EntryMode.EDIT, EntryType.CARBS, 20, t, 1L);
        Entry self = entry("uuid-1", EntryOrigin.TREATMENTS, EntryType.CARBS, t, true, "LightScout");
        Entry other = entry("uuid-2", EntryType.CARBS, t.minus(Duration.ofMinutes(1)));
        assertEquals(List.of(other), rules.findSimilar(edit, List.of(self, other)));
    }

    @Test
    public void findSimilar_withoutTypeOrTime_isEmpty() {
        Entry e = entry("a", EntryType.CARBS, clock.now());
        assertTrue(rules.findSimilar(draft(null, null, clock.now()), List.of(e)).isEmpty());
        assertTrue(rules.findSimilar(draft(EntryType.CARBS, 10, null), List.of(e)).isEmpty());
    }

    @Test
    public void r64_parseInsulinCenti() {
        assertEquals(OptionalInt.of(123), EntryRules.parseInsulinCenti("1.23"));
        assertEquals(OptionalInt.of(150), EntryRules.parseInsulinCenti("1,5"));
        assertEquals(OptionalInt.of(5), EntryRules.parseInsulinCenti("0.05"));
        assertEquals(OptionalInt.of(2500), EntryRules.parseInsulinCenti("25"));
        assertEquals(OptionalInt.of(107), EntryRules.parseInsulinCenti("1.07"));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti("1.234"));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti("1,000.5"));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti("abc"));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti(""));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti("-1"));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti(null));
        assertEquals(OptionalInt.of(4), EntryRules.parseInsulinCenti("0.04"));
        assertEquals(OptionalInt.empty(), EntryRules.parseInsulinCenti(" 1.23 "));
    }

    @Test
    public void parseWholeNumber_digitsOnly() {
        assertEquals(OptionalInt.of(120), EntryRules.parseWholeNumber("120"));
        assertEquals(OptionalInt.of(7), EntryRules.parseWholeNumber("007"));
        assertEquals(OptionalInt.empty(), EntryRules.parseWholeNumber("12.0"));
        assertEquals(OptionalInt.empty(), EntryRules.parseWholeNumber("-5"));
        assertEquals(OptionalInt.empty(), EntryRules.parseWholeNumber(""));
        assertEquals(OptionalInt.empty(), EntryRules.parseWholeNumber("1 000"));
        assertEquals(OptionalInt.empty(), EntryRules.parseWholeNumber("1234567890"));
        assertEquals(OptionalInt.empty(), EntryRules.parseWholeNumber(null));
    }

    @Test
    public void incompleteValue_canStillBecomeValidByTyping() {
        // bolus: a trailing separator within the bound, or below the 0.025 minimum
        for (String t : new String[] {"1.", "1,", "0.", "25.", "0", "0.0", "0.02", " 0.01 "}) {
            assertTrue(t, EntryRules.isIncompleteValue(EntryType.BOLUS, t));
        }
        // bolus: valid, above the maximum, bad syntax, too many decimals, empty
        for (String t : new String[] {"0.025", "1.1", "25", "26.", "25.001", "30", "1.2345", "-1", ".", "1e0", "", "abc"}) {
            assertFalse(t, EntryRules.isIncompleteValue(EntryType.BOLUS, t));
        }
        // whole numbers: only values below the minimum are incomplete (BG 2 -> 20..., carbs 0 -> 05)
        assertTrue(EntryRules.isIncompleteValue(EntryType.BG_CHECK, "2"));
        assertTrue(EntryRules.isIncompleteValue(EntryType.BG_CHECK, "19"));
        assertFalse(EntryRules.isIncompleteValue(EntryType.BG_CHECK, "20"));
        assertFalse(EntryRules.isIncompleteValue(EntryType.BG_CHECK, "601"));
        assertTrue(EntryRules.isIncompleteValue(EntryType.CARBS, "0"));
        assertFalse(EntryRules.isIncompleteValue(EntryType.CARBS, "251"));
        assertFalse(EntryRules.isIncompleteValue(EntryType.CARBS, "1."));
        assertFalse(EntryRules.isIncompleteValue(EntryType.CARBS, ""));
        assertFalse(EntryRules.isIncompleteValue(null, "1"));
        assertFalse(EntryRules.isIncompleteValue(EntryType.CARBS, null));
    }

    @Test
    public void m6_sourceLabel() {
        assertEquals(SourceKind.LIGHTSCOUT, EntryRules.sourceLabel(enteredBy("anyone", true)).kind());
        assertEquals(SourceKind.NIGHTSCOUT, EntryRules.sourceLabel(enteredBy(null, false)).kind());
        assertEquals(SourceKind.NIGHTSCOUT, EntryRules.sourceLabel(enteredBy("", false)).kind());
        assertEquals(SourceKind.NIGHTSCOUT, EntryRules.sourceLabel(enteredBy("undefined", false)).kind());
        SourceLabel uploader = EntryRules.sourceLabel(enteredBy("xDrip", false));
        assertEquals(SourceKind.UPLOADER, uploader.kind());
        assertEquals("xDrip", uploader.uploaderName());
        assertNull(EntryRules.sourceLabel(enteredBy("x", true)).uploaderName());
    }
}
