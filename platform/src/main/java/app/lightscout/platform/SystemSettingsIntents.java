package app.lightscout.platform;

import android.content.Intent;

import java.util.Optional;

/**
 * CMP-28: builds the system and OEM settings intents IF-55, IF-56, IF-57 (CP-05 §8.3, CP-00A R-11, R-74).
 *
 * <p>Returned intents are plain {@link Intent}s for the caller's {@code startActivity}; no PendingIntent is created
 * here (SR-22). A start failure is shown by the caller (M3-30 inline error / S-08 Snackbar).
 */
public interface SystemSettingsIntents {

    /**
     * Returns the settings intent for a readiness item. When the item's page does not resolve, or does not exist on
     * this API band, the app details page is returned instead.
     *
     * @param id the item
     * @return the intent; empty for item 1 on API 33+ (runtime request by CP-14) and for item 6 (→ S-08)
     */
    Optional<Intent> forItem(ReadinessItemId id);

    /**
     * Returns the app notification settings page (item 1 below API 33 and „permanently denied”, CP-00A R-74), or the
     * app details page when it does not resolve.
     *
     * @return the intent
     */
    Intent appNotificationSettings();

    /**
     * Returns the app details page ({@code ACTION_APPLICATION_DETAILS_SETTINGS}), the universal fallback.
     *
     * @return the intent
     */
    Intent appDetails();

    /**
     * Returns an OEM settings page intent when it resolves on this phone.
     *
     * @param id the page
     * @return the intent; empty when not resolvable (VR-05 fallback: hide the button)
     */
    Optional<Intent> oem(OemSettingId id);

    /**
     * Returns the external-browser intent for dontkillmyapp.com (IF-57): fixed https base + slug {@code [a-z0-9-]}
     * (SR-31).
     *
     * @param m the manufacturer
     * @return an {@code ACTION_VIEW} intent
     */
    Intent dontKillMyApp(Manufacturer m);
}
