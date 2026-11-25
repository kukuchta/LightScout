package app.lightscout.monitor;

/** Sanitises untrusted Nightscout notes before they reach CH-04 (SR-29, M-11). */
final class PumpAlertText {

    private static final int MAX_CODE_POINTS = 200;

    private PumpAlertText() {
    }

    static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(MAX_CODE_POINTS)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
