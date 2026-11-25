package app.lightscout.domain.update;

/**
 * A newer stable version the user may download (DCR-49).
 *
 * @param version the newer version
 */
public record UpdateOffer(AppVersion version) {

    /** Release page of a version; the tag of a release is {@code v} plus the version. */
    private static final String RELEASE_PAGE = "https://github.com/kukuchta/LightScout/releases/tag/v";

    /**
     * Returns the GitHub release page of this version (APK, checksum, notes). Built from the version, never taken
     * from a server reply, so it always points to the project's own releases.
     *
     * @return https URL
     */
    public String pageUrl() {
        return RELEASE_PAGE + version;
    }
}
