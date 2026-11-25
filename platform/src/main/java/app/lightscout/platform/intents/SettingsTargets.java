package app.lightscout.platform.intents;

import android.annotation.SuppressLint;
import android.provider.Settings;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.ReadinessItemId;

/**
 * Intent selection rules of IF-55, IF-56, IF-57 per API band (CP-05 §8.3, CP-00A R-74, R-85). Pure: builds no
 * {@code Intent}; {@link AndroidSystemSettingsIntents} turns the result into intents and runs the resolve checks.
 */
public final class SettingsTargets {

    /** IF-57 fixed https base (SR-31). */
    public static final String DONT_KILL_MY_APP_BASE = "https://dontkillmyapp.com/";

    /** App details page — the universal fallback. */
    public static final SettingsTarget APP_DETAILS =
            new SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true, false, null);

    /** App notification settings page. */
    public static final SettingsTarget APP_NOTIFICATIONS =
            new SettingsTarget(Settings.ACTION_APP_NOTIFICATION_SETTINGS, false, true, null);

    static final int API_NOTIFICATION_PERMISSION = 33;
    static final int API_FULL_SCREEN_INTENT = 34;
    static final int API_EXACT_ALARM = 31;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]*");

    private SettingsTargets() {
    }

    /**
     * Selects the IF-55 target of a readiness item (R-74).
     *
     * @param id             the item
     * @param sdkInt         API level of the phone
     * @param alarmChannelId current CH-01 id (item 7)
     * @return the target; empty for item 1 on 33+ (runtime request by CP-14) and for item 6 (→ S-08)
     */
    // InlinedApi: the 31+ / 34+ actions are selected only on their band. BatteryLife: F-22 (sideloaded APK).
    @SuppressLint({"InlinedApi", "BatteryLife"})
    public static Optional<SettingsTarget> forItem(ReadinessItemId id, int sdkInt, String alarmChannelId) {
        return switch (id) {
            case NOTIFICATIONS -> sdkInt >= API_NOTIFICATION_PERMISSION
                    ? Optional.empty() : Optional.of(APP_NOTIFICATIONS);
            case DND_ACCESS -> Optional.of(
                    new SettingsTarget(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, false, false, null));
            case FULL_SCREEN -> Optional.of(sdkInt >= API_FULL_SCREEN_INTENT
                    ? new SettingsTarget(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true, false, null)
                    : APP_DETAILS);
            case BATTERY -> Optional.of(
                    new SettingsTarget(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true, false, null));
            case EXACT_ALARM -> Optional.of(sdkInt >= API_EXACT_ALARM
                    ? new SettingsTarget(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true, false, null)
                    : APP_DETAILS);
            case ALARM_CHANNEL -> Optional.of(
                    new SettingsTarget(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, false, true, alarmChannelId));
            case MANUFACTURER -> Optional.empty();
        };
    }

    /**
     * Selects the IF-55 target of a readiness item and applies the resolve check: a target whose page does not
     * resolve is replaced by {@link #APP_DETAILS} (IF-55 fallback).
     *
     * @param id             the item
     * @param sdkInt         API level of the phone
     * @param alarmChannelId current CH-01 id (item 7)
     * @param resolves       resolve seam in front of {@code PackageManager}
     * @return the target or the fallback; empty as in {@link #forItem}
     */
    public static Optional<SettingsTarget> forItemResolved(ReadinessItemId id, int sdkInt, String alarmChannelId,
                                                           Predicate<SettingsTarget> resolves) {
        return forItem(id, sdkInt, alarmChannelId).map(target -> resolvedOrDetails(target, resolves));
    }

    /**
     * Returns the target if it resolves, else {@link #APP_DETAILS}.
     *
     * @param target   the wanted page
     * @param resolves resolve seam in front of {@code PackageManager}
     * @return the target or the fallback
     */
    public static SettingsTarget resolvedOrDetails(SettingsTarget target, Predicate<SettingsTarget> resolves) {
        return resolves.test(target) ? target : APP_DETAILS;
    }

    /**
     * Returns the explicit component of an IF-56 OEM page (R-85; verified by VR-05).
     *
     * @param id the page
     * @return the component; empty for {@link OemSettingId#APP_DETAILS} (use {@link #APP_DETAILS})
     */
    public static Optional<OemComponent> oemComponent(OemSettingId id) {
        return switch (id) {
            case XIAOMI_AUTOSTART -> Optional.of(new OemComponent(
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            case XIAOMI_BATTERY_SAVER -> Optional.of(new OemComponent(
                    "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
            case APP_DETAILS -> Optional.empty();
        };
    }

    /**
     * Returns the IF-57 URL: fixed https base + slug (SR-31).
     *
     * @param m the manufacturer
     * @return the URL
     * @throws IllegalStateException if a slug outside {@code [a-z0-9-]} ever appears
     */
    public static String dontKillMyAppUrl(Manufacturer m) {
        String slug = m.dontKillMyAppSlug();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalStateException("invalid slug for " + m.name());
        }
        return DONT_KILL_MY_APP_BASE + slug;
    }
}
