package app.lightscout.domain.sound;

import java.util.Objects;
import java.util.Optional;

/**
 * A reference to one alarm sound (DCR-51), stored as text: {@code classic}, {@code bundled:<id>},
 * {@code phone:<key>} or {@code own:<key>}. A key is 16 lower-case hex digits and is never made from user text.
 *
 * @param kind where the sound comes from
 * @param key  bundled id or file key; empty for {@link SoundKind#CLASSIC}
 */
public record SoundRef(SoundKind kind, String key) {

    /** The built-in classic tone, the default of every alarm type. */
    public static final SoundRef CLASSIC = new SoundRef(SoundKind.CLASSIC, "");

    private static final String CLASSIC_TEXT = "classic";
    private static final String BUNDLED_PREFIX = "bundled";
    private static final String PHONE_PREFIX = "phone";
    private static final String OWN_PREFIX = "own";

    /**
     * Checks the key against the kind.
     *
     * @throws IllegalArgumentException when the key does not fit the kind
     */
    public SoundRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        boolean valid = switch (kind) {
            case CLASSIC -> key.isEmpty();
            case BUNDLED -> SoundFields.isBundledId(key);
            case PHONE, OWN -> SoundFields.isKey(key);
        };
        SoundFields.require(valid, "Bad sound reference key for " + kind);
    }

    /**
     * Returns a reference to a sound shipped inside the app.
     *
     * @param id bundled id, 1 to 24 characters of {@code a-z 0-9 _}
     * @return the reference
     */
    public static SoundRef bundled(String id) {
        return new SoundRef(SoundKind.BUNDLED, id);
    }

    /**
     * Returns a reference to a copied phone sound.
     *
     * @param key 16 hex digits
     * @return the reference
     */
    public static SoundRef phone(String key) {
        return new SoundRef(SoundKind.PHONE, key);
    }

    /**
     * Returns a reference to an own sound.
     *
     * @param key 16 hex digits
     * @return the reference
     */
    public static SoundRef own(String key) {
        return new SoundRef(SoundKind.OWN, key);
    }

    /**
     * Returns the stored text.
     *
     * @return {@code classic}, or {@code <kind>:<key>}
     */
    public String encode() {
        return switch (kind) {
            case CLASSIC -> CLASSIC_TEXT;
            case BUNDLED -> BUNDLED_PREFIX + ":" + key;
            case PHONE -> PHONE_PREFIX + ":" + key;
            case OWN -> OWN_PREFIX + ":" + key;
        };
    }

    /**
     * Reads a stored text.
     *
     * @param text stored text; {@code null} or anything malformed gives empty
     * @return the reference, or empty
     */
    public static Optional<SoundRef> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        if (CLASSIC_TEXT.equals(text)) {
            return Optional.of(CLASSIC);
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String key = text.substring(colon + 1);
        SoundKind kind = switch (text.substring(0, colon)) {
            case BUNDLED_PREFIX -> SoundKind.BUNDLED;
            case PHONE_PREFIX -> SoundKind.PHONE;
            case OWN_PREFIX -> SoundKind.OWN;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SoundRef(kind, key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
