package app.lightscout.domain.entry;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry rules (CMP-07, IF-06; design S-02, DS:A-18, DS:A-19, DS:D-016, DS:D-027, M-4, M-6; CP-00A R-9, R-64, R-75).
 */
public final class EntryRules {

    private static final int BG_MIN = 20;
    private static final int BG_MAX = 600;
    private static final int CARBS_MIN = 1;
    private static final int CARBS_MAX = 250;
    private static final int BOLUS_MILLI_MIN = 25;
    private static final int BOLUS_MILLI_MAX = 25000;

    /** Times later than {@code now} plus this are in the future. */
    private static final Duration MAX_FUTURE = Duration.ofMinutes(1);
    /** Times earlier than {@code now} minus this are too old. */
    private static final Duration MAX_AGE = Duration.ofDays(7);
    /** DS:D-027 similar-entry window. */
    private static final Duration SIMILAR_WINDOW = Duration.ofMinutes(10);

    /** Split-document id suffixes (M-2); such entries are never editable. */
    private static final String SPLIT_CARBS = "#carbs";
    private static final String SPLIT_INSULIN = "#insulin";

    /** M-6: {@code enteredBy} value treated as empty. */
    private static final String UNDEFINED = "undefined";

    /** Digits only; at most 9 digits so the value fits an {@code int}. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,9}");
    /** Units with {@code .} or {@code ,} and at most 2 decimals; no grouping. */
    private static final Pattern INSULIN = Pattern.compile("([0-9]{1,6})(?:[.,]([0-9]{1,2}))?");
    private static final Pattern TRAILING_SEPARATOR = Pattern.compile("([0-9]+)[.,]");
    private static final Pattern INSULIN_MILLI = Pattern.compile("([0-9]+)(?:[.,]([0-9]{1,3}))?");

    /**
     * Creates the stateless rule component.
     */
    public EntryRules() {
        // no state
    }

    /**
     * Validates a draft (S-02). A {@code null} time means „Now” and is valid; the caller resolves it at Save
     * with the same {@code now} (R-75).
     *
     * @param d   the draft
     * @param now validation instant
     * @return codes of every violation, empty when valid
     */
    public ValidationResult validate(EntryDraft d, Instant now) {
        List<Violation> violations = new ArrayList<>();
        EntryType type = d.type();
        if (!isLoggable(type)) {
            violations.add(new Violation(ValidationField.TYPE, ValidationCode.TYPE_REQUIRED, List.of()));
        } else {
            ValidationCode valueCode = valueViolation(type, d.value());
            if (valueCode != null) {
                violations.add(new Violation(ValidationField.VALUE, valueCode, List.of()));
            }
        }
        Instant time = d.time();
        if (time != null) {
            if (time.isAfter(now.plus(MAX_FUTURE))) {
                violations.add(new Violation(ValidationField.TIME, ValidationCode.TIME_FUTURE, List.of()));
            } else if (time.isBefore(now.minus(MAX_AGE))) {
                violations.add(new Violation(ValidationField.TIME, ValidationCode.TIME_TOO_OLD, List.of()));
            }
        }
        return violations.isEmpty() ? ValidationResult.ok() : new ValidationResult(violations);
    }

    private static boolean isLoggable(EntryType type) {
        return type == EntryType.BG_CHECK || type == EntryType.CARBS || type == EntryType.BOLUS
                || type == EntryType.SITE_CHANGE || type == EntryType.SENSOR_CHANGE;
    }

    private static ValidationCode valueViolation(EntryType type, Integer value) {
        return switch (type) {
            case BG_CHECK -> inRange(value, BG_MIN, BG_MAX) ? null : ValidationCode.BG_RANGE;
            case CARBS -> inRange(value, CARBS_MIN, CARBS_MAX) ? null : ValidationCode.CARBS_RANGE;
            case BOLUS -> inRange(value, BOLUS_MILLI_MIN, BOLUS_MILLI_MAX) ? null : ValidationCode.BOLUS_RANGE;
            default -> null;
        };
    }

    private static boolean inRange(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }

    /**
     * Tells whether an entry may be edited or deleted (M-4, DS:D-016): only own LightScout entries of the
     * treatments collection; split ids and meter BG entries are read-only.
     *
     * @param e the entry
     * @return {@code true} if editable
     */
    public boolean isEditable(Entry e) {
        if (!e.own() || e.origin() != EntryOrigin.TREATMENTS) {
            return false;
        }
        String id = e.id();
        return id != null && !id.endsWith(SPLIT_CARBS) && !id.endsWith(SPLIT_INSULIN);
    }

    /**
     * Returns entries of the same type within ±10 min of the draft's time (DS:D-027), excluding the entry being
     * edited ({@code e.id() == d.identifier()}, R-75). The draft's time must be resolved by the caller („Now” →
     * now); a draft without type or time has no similar entries.
     *
     * @param d      the draft
     * @param nearby candidate entries
     * @return matching entries in input order, unmodifiable
     */
    public List<Entry> findSimilar(EntryDraft d, List<Entry> nearby) {
        if (d.type() == null || d.time() == null) {
            return List.of();
        }
        List<Entry> similar = new ArrayList<>();
        for (Entry e : nearby) {
            if (e != null && e.type() == d.type() && e.time() != null
                    && !d.identifier().equals(e.id())
                    && Duration.between(e.time(), d.time()).abs().compareTo(SIMILAR_WINDOW) <= 0) {
                similar.add(e);
            }
        }
        return List.copyOf(similar);
    }

    /**
     * Returns the source label of an entry (M-6): own → LightScout; empty or {@code "undefined"}
     * {@code enteredBy} → Nightscout; otherwise the uploader name.
     *
     * @param e the entry
     * @return the label
     */
    public static SourceLabel sourceLabel(Entry e) {
        if (e.own()) {
            return new SourceLabel(SourceKind.LIGHTSCOUT, null);
        }
        String enteredBy = e.enteredBy() == null ? "" : e.enteredBy().trim();
        if (enteredBy.isEmpty() || UNDEFINED.equals(enteredBy)) {
            return new SourceLabel(SourceKind.NIGHTSCOUT, null);
        }
        return new SourceLabel(SourceKind.UPLOADER, enteredBy);
    }

    /**
     * Tells whether a not-yet-valid value text may still become valid by typing more characters, so a form can
     * hold the error back while the user is mid-entry (D-147): a trailing decimal separator within the bound
     * ({@code "1."}) or a number below the minimum ({@code "0"}, {@code "0.02"}, {@code "1"} for BG). Never true
     * for a valid text, a number above the maximum, an unparsable text or more than 3 bolus decimals.
     *
     * @param type BG check, carbs or bolus (other types have no value)
     * @param text the raw field text
     * @return {@code true} if appending characters can still make the text valid
     */
    public static boolean isIncompleteValue(EntryType type, String text) {
        if (type == null || text == null) {
            return false;
        }
        String t = text.trim();
        if (type == EntryType.BOLUS) {
            Matcher open = TRAILING_SEPARATOR.matcher(t);
            if (open.matches()) {
                return open.group(1).length() <= 6 && Long.parseLong(open.group(1)) * 1000 <= BOLUS_MILLI_MAX;
            }
            OptionalInt milli = parseInsulinMilli(t);
            return milli.isPresent() && milli.getAsInt() < BOLUS_MILLI_MIN;
        }
        OptionalInt whole = parseWholeNumber(t);
        if (!whole.isPresent()) {
            return false;
        }
        return whole.getAsInt() < (type == EntryType.BG_CHECK ? BG_MIN : type == EntryType.CARBS ? CARBS_MIN : 0);
    }

    /**
     * Parses a BG or carbs value: digits only.
     *
     * @param text the input text
     * @return the value, or empty if the text is not a whole number
     */
    public static OptionalInt parseWholeNumber(String text) {
        if (text == null || !WHOLE_NUMBER.matcher(text).matches()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(text));
    }

    /**
     * Parses a bolus in units to centi-units (R-64): {@code .} or {@code ,} as decimal separator, at most
     * 2 decimals, no grouping. Compatibility parser only; manual drafts use {@link #parseInsulinMilli}.
     *
     * @param text the input text, e.g. {@code "1,25"}
     * @return centi-units, or empty if the text is not a valid number
     */
    public static OptionalInt parseInsulinCenti(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        Matcher m = INSULIN.matcher(text);
        if (!m.matches()) {
            return OptionalInt.empty();
        }
        int units = Integer.parseInt(m.group(1));
        String decimals = m.group(2);
        int centi = 0;
        if (decimals != null) {
            centi = Integer.parseInt(decimals);
            if (decimals.length() == 1) {
                centi *= 10;
            }
        }
        return OptionalInt.of(units * 100 + centi);
    }

    /**
     * Parses an exact bolus number in U to integer milli-U (R-91). Surrounding whitespace is trimmed;
     * only digits and an optional dot or comma followed by one to three digits are accepted.
     * Signs, grouping, exponents, partial decimals, excess precision and overflow are rejected, never rounded.
     * Manual dose range checking is done by {@link #validate}.
     *
     * @param text input number in U, e.g. {@code "1,234"}
     * @return exact milli-U, or empty for invalid syntax or integer overflow
     */
    public static OptionalInt parseInsulinMilli(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        Matcher m = INSULIN_MILLI.matcher(text.trim());
        if (!m.matches()) {
            return OptionalInt.empty();
        }
        try {
            long whole = Math.multiplyExact(Long.parseLong(m.group(1)), 1000L);
            String fraction = m.group(2);
            int milli = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
            return OptionalInt.of(Math.toIntExact(Math.addExact(whole, milli)));
        } catch (NumberFormatException | ArithmeticException ex) {
            return OptionalInt.empty();
        }
    }
}
