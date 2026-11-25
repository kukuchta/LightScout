package app.lightscout.platform.intents;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.Optional;

import app.lightscout.platform.ChannelId;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.SystemSettingsIntents;

/**
 * CMP-28 {@link SystemSettingsIntents} on {@code PackageManager} resolve checks (CP-05 §8.3). Selection rules are in
 * {@link SettingsTargets}. Package visibility comes from the {@code <queries>} of the {@code :platform} manifest
 * (SR-27: no {@code QUERY_ALL_PACKAGES}).
 */
public final class AndroidSystemSettingsIntents implements SystemSettingsIntents {

    private final String packageName;
    private final PackageManager packageManager;
    private final NotificationChannels channels;

    /**
     * Creates the component.
     *
     * @param context  any context (the application context is used)
     * @param channels CMP-26 for the current CH-01 id (item 7)
     */
    public AndroidSystemSettingsIntents(Context context, NotificationChannels channels) {
        Context app = context.getApplicationContext();
        this.packageName = app.getPackageName();
        this.packageManager = app.getPackageManager();
        this.channels = channels;
    }

    @Override
    public Optional<Intent> forItem(ReadinessItemId id) {
        String alarmChannelId = id == ReadinessItemId.ALARM_CHANNEL ? channels.channelId(ChannelId.ALARMS) : null;
        return SettingsTargets.forItemResolved(id, Build.VERSION.SDK_INT, alarmChannelId, this::resolves)
                .map(this::build);
    }

    @Override
    public Intent appNotificationSettings() {
        return build(SettingsTargets.resolvedOrDetails(SettingsTargets.APP_NOTIFICATIONS, this::resolves));
    }

    @Override
    public Intent appDetails() {
        return build(SettingsTargets.APP_DETAILS);
    }

    @Override
    public Optional<Intent> oem(OemSettingId id) {
        Intent intent = SettingsTargets.oemComponent(id)
                .map(c -> new Intent().setComponent(new ComponentName(c.packageName(), c.className())))
                .orElseGet(this::appDetails);
        return resolves(intent) ? Optional.of(intent) : Optional.empty();
    }

    @Override
    public Intent dontKillMyApp(Manufacturer m) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SettingsTargets.dontKillMyAppUrl(m)))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    private Intent build(SettingsTarget target) {
        Intent intent = new Intent(target.action());
        if (target.packageUri()) {
            intent.setData(Uri.fromParts("package", packageName, null));
        }
        if (target.packageExtra()) {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        }
        if (target.channelId() != null) {
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, target.channelId());
        }
        return intent;
    }

    private boolean resolves(SettingsTarget target) {
        return resolves(build(target));
    }

    private boolean resolves(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return packageManager.resolveActivity(intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY)) != null;
            }
            return resolveLegacy(intent);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean resolveLegacy(Intent intent) {
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }
}
