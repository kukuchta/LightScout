package app.lightscout.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;

import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;
import app.lightscout.platform.intents.AndroidSystemSettingsIntents;

/**
 * CP-05 §9 „IF-55 intents per band” on a real {@code PackageManager}: every built intent resolves or is the
 * {@code ACTION_APPLICATION_DETAILS_SETTINGS} fallback; full-screen intent only on 34+, exact-alarm intent only on
 * 31+; IF-56 OEM intents are present only when resolvable; IF-57 is an https {@code ACTION_VIEW}.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). API 36 / HyperOS is VR-05 on RD-1.
 */
@RunWith(AndroidJUnit4.class)
public class SystemSettingsIntentsDeviceTest {

    private PackageManager packageManager;
    private AndroidSystemSettingsIntents intents;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        packageManager = context.getPackageManager();
        DefaultNotificationChannels channels =
                new DefaultNotificationChannels(new AndroidChannelBackend(context), new RecordingLogger());
        channels.ensureChannels();
        intents = new AndroidSystemSettingsIntents(context, channels);
    }

    @Test
    public void everyItemIntentResolvesOrFallsBackToAppDetails() {
        for (ReadinessItemId id : ReadinessItemId.values()) {
            Optional<Intent> intent = intents.forItem(id);
            if (id == ReadinessItemId.MANUFACTURER
                    || (id == ReadinessItemId.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33)) {
                assertTrue(id + " has no intent", intent.isEmpty());
                continue;
            }
            Intent i = intent.orElseThrow();
            assertTrue(id + " resolves or falls back: " + i,
                    resolves(i) || Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(i.getAction()));
        }
    }

    @Test
    public void bandGatedIntents() {
        String fullScreen = intents.forItem(ReadinessItemId.FULL_SCREEN).orElseThrow().getAction();
        if (Build.VERSION.SDK_INT < 34) {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fullScreen);
        }
        String exact = intents.forItem(ReadinessItemId.EXACT_ALARM).orElseThrow().getAction();
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM.equals(exact)
                    || Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(exact));
        }
    }

    @Test
    public void appDetailsAndNotificationSettingsResolve() {
        assertTrue(resolves(intents.appDetails()));
        assertTrue(resolves(intents.appNotificationSettings()));
    }

    @Test
    public void item7TargetsTheCurrentAlarmChannel() {
        Intent i = intents.forItem(ReadinessItemId.ALARM_CHANNEL).orElseThrow();
        if (Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS.equals(i.getAction())) {
            assertEquals("alarms", i.getStringExtra(Settings.EXTRA_CHANNEL_ID));
        }
    }

    @Test
    public void oemIntentsArePresentOnlyWhenResolvable() {
        for (OemSettingId id : OemSettingId.values()) {
            intents.oem(id).ifPresent(i -> assertTrue(id + " resolves", resolves(i)));
        }
    }

    @Test
    public void dontKillMyAppIsHttpsView() {
        Intent i = intents.dontKillMyApp(Manufacturer.XIAOMI);
        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertNotNull(i.getData());
        assertEquals("https://dontkillmyapp.com/xiaomi", i.getData().toString());
    }

    @SuppressWarnings("deprecation")
    private boolean resolves(Intent intent) {
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }
}
