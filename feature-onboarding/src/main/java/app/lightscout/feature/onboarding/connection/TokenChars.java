package app.lightscout.feature.onboarding.connection;

import android.text.Editable;

/**
 * Short-lived token plaintext handling for the S-06 field (SR-02, ADR-026): characters are copied out of the
 * {@link Editable} as {@code char[]} (no {@code String}) and the {@code Editable} is overwritten before it is
 * released.
 */
final class TokenChars {

    private TokenChars() {
    }

    /**
     * Copies a range of an editable into a fresh array (the caller owns and wipes it).
     *
     * @param text  the field text
     * @param start first index (inclusive)
     * @param end   last index (exclusive)
     * @return the characters
     */
    static char[] copy(CharSequence text, int start, int end) {
        int s = Math.max(0, Math.min(start, text.length()));
        int e = Math.max(s, Math.min(end, text.length()));
        char[] out = new char[e - s];
        for (int i = s; i < e; i++) {
            out[i - s] = text.charAt(i);
        }
        return out;
    }

    /**
     * Overwrites every character of an editable, then empties it.
     *
     * @param e the field text; nullable
     */
    static void wipe(Editable e) {
        if (e == null) {
            return;
        }
        int n = e.length();
        if (n > 0) {
            e.replace(0, n, new String(new char[n]));
        }
        e.clear();
    }
}
