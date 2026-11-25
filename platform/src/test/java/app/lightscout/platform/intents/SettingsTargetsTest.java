package app.lightscout.platform.intents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.provider.Settings;

import org.junit.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.ReadinessItemId;

/**
 * CP-05 §9 „IF-55 intents per band” (selection part, JVM) and „IF-57 slug”; IF-56 components (R-85). Resolve checks
 * and the fallback on a real phone are in the instrumented {@code SystemSettingsIntentsTest}.
 */
public class SettingsTargetsTest {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]+");

    @Test
    public void item1RuntimeRequestOn33PlusAppNotificationSettingsBelow() {
        for (int sdk : new int[] {33, 34, 35, 36}) {
            assertTrue(SettingsTargets.forItem(ReadinessItemId.NOTIFICATIONS, sdk, "alarms").isEmpty());
        }
        for (int sdk : new int[] {29, 30, 31, 32}) {
            SettingsTarget target = SettingsTargets.forItem(ReadinessItemId.NOTIFICATIONS, sdk, "alarms").get();
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, target.action());
            assertTrue(target.packageExtra());
        }
    }

    @Test
    public void item2PolicyAccessOnEveryBand() {
        for (int sdk = 29; sdk <= 36; sdk++) {
            SettingsTarget target = SettingsTargets.forItem(ReadinessItemId.DND_ACCESS, sdk, "alarms").get();
            assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, target.action());
            assertFalse(target.packageUri());
        }
    }

    @Test
    public void fullScreenIntentOnlyOn34Plus() {
        for (int sdk = 29; sdk <= 33; sdk++) {
            assertEquals(SettingsTargets.APP_DETAILS,
                    SettingsTargets.forItem(ReadinessItemId.FULL_SCREEN, sdk, "alarms").get());
        }
        for (int sdk = 34; sdk <= 36; sdk++) {
            SettingsTarget target = SettingsTargets.forItem(ReadinessItemId.FULL_SCREEN, sdk, "alarms").get();
            assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, target.action());
            assertTrue(target.packageUri());
        }
    }

    @Test
    public void batteryOnEveryBand() {
        for (int sdk = 29; sdk <= 36; sdk++) {
            SettingsTarget target = SettingsTargets.forItem(ReadinessItemId.BATTERY, sdk, "alarms").get();
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, target.action());
            assertTrue(target.packageUri());
        }
    }

    @Test
    public void exactAlarmIntentOnlyOn31Plus() {
        for (int sdk = 29; sdk <= 30; sdk++) {
            assertEquals(SettingsTargets.APP_DETAILS,
                    SettingsTargets.forItem(ReadinessItemId.EXACT_ALARM, sdk, "alarms").get());
        }
        for (int sdk = 31; sdk <= 36; sdk++) {
            SettingsTarget target = SettingsTargets.forItem(ReadinessItemId.EXACT_ALARM, sdk, "alarms").get();
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, target.action());
            assertTrue(target.packageUri());
        }
    }

    @Test
    public void item7ChannelSettingsForCurrentAlarmChannelId() {
        SettingsTarget target = SettingsTargets.forItem(ReadinessItemId.ALARM_CHANNEL, 33, "alarms_v3").get();
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, target.action());
        assertTrue(target.packageExtra());
        assertEquals("alarms_v3", target.channelId());
    }

    @Test
    public void item6HasNoIntent() {
        for (int sdk = 29; sdk <= 36; sdk++) {
            assertTrue(SettingsTargets.forItem(ReadinessItemId.MANUFACTURER, sdk, "alarms").isEmpty());
        }
    }

    @Test
    public void unresolvablePageFallsBackToAppDetails() {
        for (ReadinessItemId id : new ReadinessItemId[] {ReadinessItemId.DND_ACCESS, ReadinessItemId.FULL_SCREEN,
            ReadinessItemId.BATTERY, ReadinessItemId.EXACT_ALARM, ReadinessItemId.ALARM_CHANNEL}) {
            assertEquals(id.name(), SettingsTargets.APP_DETAILS,
                    SettingsTargets.forItemResolved(id, 36, "alarms", target -> false).get());
        }
        assertEquals(SettingsTargets.APP_DETAILS,
                SettingsTargets.forItemResolved(ReadinessItemId.NOTIFICATIONS, 30, "alarms", target -> false).get());
        assertEquals(SettingsTargets.APP_DETAILS,
                SettingsTargets.resolvedOrDetails(SettingsTargets.APP_NOTIFICATIONS, target -> false));
    }

    @Test
    public void resolvablePageIsKept() {
        SettingsTarget battery = SettingsTargets.forItemResolved(ReadinessItemId.BATTERY, 36, "alarms",
                target -> true).get();
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action());
        assertTrue(SettingsTargets.forItemResolved(ReadinessItemId.MANUFACTURER, 36, "alarms", t -> false).isEmpty());
        assertTrue(SettingsTargets.forItemResolved(ReadinessItemId.NOTIFICATIONS, 33, "alarms", t -> false).isEmpty());
    }

    @Test
    public void appDetailsIsThePackageDetailsPage() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, SettingsTargets.APP_DETAILS.action());
        assertTrue(SettingsTargets.APP_DETAILS.packageUri());
        assertNull(SettingsTargets.APP_DETAILS.channelId());
    }

    @Test
    public void oemComponentsPerR85() {
        assertEquals(new OemComponent("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                SettingsTargets.oemComponent(OemSettingId.XIAOMI_AUTOSTART).get());
        assertEquals(new OemComponent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                SettingsTargets.oemComponent(OemSettingId.XIAOMI_BATTERY_SAVER).get());
        assertEquals(Optional.empty(), SettingsTargets.oemComponent(OemSettingId.APP_DETAILS));
    }

    @Test
    public void dontKillMyAppUsesFixedHttpsBaseAndSafeSlug() {
        for (Manufacturer m : Manufacturer.values()) {
            String url = SettingsTargets.dontKillMyAppUrl(m);
            assertTrue(url.startsWith("https://dontkillmyapp.com/"));
            String slug = url.substring("https://dontkillmyapp.com/".length());
            if (m == Manufacturer.OTHER) {
                assertEquals("", slug);
            } else {
                assertTrue(m + " slug " + slug, SLUG.matcher(slug).matches());
            }
        }
        assertEquals("https://dontkillmyapp.com/xiaomi", SettingsTargets.dontKillMyAppUrl(Manufacturer.XIAOMI));
        assertEquals("https://dontkillmyapp.com/google", SettingsTargets.dontKillMyAppUrl(Manufacturer.PIXEL));
        assertEquals("https://dontkillmyapp.com/", SettingsTargets.dontKillMyAppUrl(Manufacturer.OTHER));
    }
}
