package app.lightscout.platform.intents;

/**
 * Description of one IF-55 system settings intent, independent of {@code android.content.Intent}.
 *
 * @param action       intent action ({@code Settings.ACTION_*})
 * @param packageUri   {@code true}: data {@code package:<app package>}
 * @param packageExtra {@code true}: extra {@code Settings.EXTRA_APP_PACKAGE = <app package>}
 * @param channelId    extra {@code Settings.EXTRA_CHANNEL_ID}, or {@code null}
 */
public record SettingsTarget(String action, boolean packageUri, boolean packageExtra, String channelId) {
}
