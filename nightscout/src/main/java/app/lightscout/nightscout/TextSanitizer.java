package app.lightscout.nightscout;

/**
 * Free text from Nightscout is untrusted (M-11, SR-29): capped at 200 characters, control characters stripped,
 * kept as plain text (it is never rendered as HTML anywhere).
 */
final class TextSanitizer {

    /** M-11: maximum length of a free-text field. */
    static final int MAX_LENGTH = 200;

    private TextSanitizer() {
    }

    /**
     * Cleans one free-text value: line breaks and tabs become a space, every other control character (Unicode
     * category Cc) and every format character (category Cf: bidi overrides / isolates, zero-width characters —
     * display spoofing, IR-28) is removed, the result is trimmed and capped at {@value #MAX_LENGTH} characters without
     * splitting a surrogate pair.
     *
     * @param raw the raw text, may be {@code null}
     * @return the cleaned text, or {@code null} for {@code null}
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LENGTH + 16));
        // By code point (IR-35): format characters outside the BMP (tag characters U+E0001, U+E0020…E007F,
        // U+1BCA0…1BCA3) are dropped too, while valid supplementary characters (e.g. emoji) are kept.
        raw.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) {
                sb.appendCodePoint(cp);
            }
        });
        String s = sb.toString().trim();
        if (s.length() > MAX_LENGTH) {
            int end = Character.isHighSurrogate(s.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
            s = s.substring(0, end);
        }
        return s;
    }
}
