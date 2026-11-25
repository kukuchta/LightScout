package app.lightscout.platform;

/**
 * The six S-08 manufacturer options (C-S08-03, CP-00A R-85).
 */
public enum Manufacturer {
    /** „Xiaomi / Redmi / POCO”. */
    XIAOMI("xiaomi"),
    /** „Samsung”. */
    SAMSUNG("samsung"),
    /** „Huawei / Honor”. */
    HUAWEI("huawei"),
    /** „OnePlus / OPPO / realme”. */
    ONEPLUS("oneplus"),
    /** „Google Pixel”. */
    PIXEL("google"),
    /** „Other” (also the empty value). */
    OTHER("");

    private final String slug;

    Manufacturer(String slug) {
        this.slug = slug;
    }

    /**
     * Returns the dontkillmyapp.com path slug (IF-57, CP-00A R-85). Matches {@code [a-z0-9-]*}; empty for
     * {@link #OTHER}, which opens the site's start page.
     *
     * @return slug
     */
    public String dontKillMyAppSlug() {
        return slug;
    }
}
