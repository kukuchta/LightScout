package app.lightscout.usecases.sync;

import java.util.List;

/**
 * Stored forms of one Nightscout treatment identifier: a split uploader document is cached as {@code X#carbs}
 * and {@code X#insulin} (CP-00 §5), an unsplit one as {@code X}. Public since CP-02b: the EntryWriter replaces
 * every stored form after a write (Q-39).
 */
public final class IdForms {

    /** Suffix of the carbs part of a split document. */
    public static final String CARBS_SUFFIX = "#carbs";
    /** Suffix of the insulin part of a split document. */
    public static final String INSULIN_SUFFIX = "#insulin";

    private IdForms() {
    }

    /**
     * Returns the base identifier ({@code X#carbs} → {@code X}).
     *
     * @param id a stored identifier; nullable
     * @return the base identifier, or {@code null}
     */
    public static String base(String id) {
        if (id == null) {
            return null;
        }
        if (id.endsWith(CARBS_SUFFIX)) {
            return id.substring(0, id.length() - CARBS_SUFFIX.length());
        }
        if (id.endsWith(INSULIN_SUFFIX)) {
            return id.substring(0, id.length() - INSULIN_SUFFIX.length());
        }
        return id;
    }

    /**
     * Returns every stored form of a base identifier.
     *
     * @param base the base identifier
     * @return {@code X}, {@code X#carbs}, {@code X#insulin}
     */
    public static List<String> forms(String base) {
        return List.of(base, base + CARBS_SUFFIX, base + INSULIN_SUFFIX);
    }
}
