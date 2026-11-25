package app.lightscout.ui.core;

import java.util.Locale;

import app.lightscout.domain.model.Entry;

/**
 * Pure value formats of CP-00A R-82 / Q-54 / R-91, used by {@link Formatters}.
 * The public milli-dose formatter returns a number only, without a unit or Android context.
 */
public final class ValueText {

    private ValueText() {
    }

    /**
     * Insulin number with 1–2 decimals, a trailing zero after the first decimal removed: 150 → "1.5",
     * 125 → "1.25", 200 → "2.0", 5 → "0.05".
     *
     * @param centi insulin in centi-units (0.01 U), not negative
     * @return the number text
     */
    static String insulinNumber(int centi) {
        int abs = Math.abs(centi);
        int whole = abs / 100;
        int fraction = abs % 100;
        String sign = centi < 0 ? "-" : "";
        if (fraction % 10 == 0) {
            return sign + whole + "." + (fraction / 10);
        }
        return sign + whole + "." + (fraction < 10 ? "0" : "") + fraction;
    }

    /**
     * Exact treatment dose in units with up to three decimals and trailing zeros trimmed (CP-00A R-91):
     * 25 → "0.025", 1234 → "1.234", 1250 → "1.25", 2000 → "2".
     *
     * @param milli dose in milli-units (0.001 U)
     * @return the number text
     */
    public static String insulinMilliNumber(long milli) {
        long abs = Math.abs(milli);
        String sign = milli < 0 ? "-" : "";
        long fraction = abs % 1000;
        if (fraction == 0) {
            return sign + (abs / 1000);
        }
        String digits = String.format(Locale.ROOT, "%03d", fraction);
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') {
            end--;
        }
        return sign + (abs / 1000) + "." + digits.substring(0, end);
    }

    /**
     * Joins a number and its unit suffix.
     *
     * @param number number text
     * @param suffix unit suffix ({@code s02_suffix_*})
     * @return "number suffix"
     */
    static String withUnit(String number, String suffix) {
        return number + " " + suffix;
    }

    /**
     * Marker / similar-entry value (R-82, Q-54, R-91): BG "{n}"; carbs "{n} g"; bolus "{x} U" from the authoritative
     * {@code insulinMilli} (legacy centi * 10 when absent; up to three decimals, trailing zeros trimmed); pump alert
     * the sanitised Nightscout text (R-70, M-11; "" if none); site / sensor change and temp basal "". Missing values
     * give "".
     *
     * @param e       the entry
     * @param gSuffix {@code s02_suffix_g}
     * @param uSuffix {@code s02_suffix_u}
     * @return the value text
     */
    static String entryValue(Entry e, String gSuffix, String uSuffix) {
        if (e == null || e.type() == null) {
            return "";
        }
        switch (e.type()) {
            case BG_CHECK:
                return e.glucose() == null ? "" : Integer.toString(e.glucose());
            case CARBS:
                return e.carbs() == null ? "" : withUnit(Integer.toString(e.carbs()), gSuffix);
            case BOLUS:
                Long milli = e.insulinMilli();
                if (milli == null && e.insulinCenti() != null) {
                    milli = e.insulinCenti().longValue() * 10L;
                }
                return milli == null ? "" : withUnit(insulinMilliNumber(milli), uSuffix);
            case PUMP_ALERT:
                return e.text() == null ? "" : e.text();
            default:
                return "";
        }
    }
}
