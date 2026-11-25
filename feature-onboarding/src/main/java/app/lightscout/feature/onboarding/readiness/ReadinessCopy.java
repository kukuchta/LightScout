package app.lightscout.feature.onboarding.readiness;

import androidx.annotation.StringRes;

import app.lightscout.feature.onboarding.R;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Action;
import app.lightscout.platform.ReadinessItemId;

/**
 * Maps S-07 items and actions to their §17 copy keys (CP-14 §7.4.1, §7.7). Pure lookup, JVM-tested.
 */
public final class ReadinessCopy {

    private ReadinessCopy() {
    }

    /**
     * Headline of an item.
     *
     * @param id the item
     * @return {@code s07_item_*}
     */
    @StringRes
    public static int headline(ReadinessItemId id) {
        return switch (id) {
            case NOTIFICATIONS -> R.string.s07_item_notifications;
            case DND_ACCESS -> R.string.s07_item_dnd;
            case FULL_SCREEN -> R.string.s07_item_full_screen;
            case BATTERY -> R.string.s07_item_battery;
            case EXACT_ALARM -> R.string.s07_item_exact_alarm;
            case MANUFACTURER -> R.string.s07_item_manufacturer;
            case ALARM_CHANNEL -> R.string.s07_item_alarm_channel;
        };
    }

    /**
     * Rationale of an item (P2; never truncated — M3-31).
     *
     * @param id the item
     * @return {@code s07_item_*_rationale}
     */
    @StringRes
    public static int rationale(ReadinessItemId id) {
        return switch (id) {
            case NOTIFICATIONS -> R.string.s07_item_notifications_rationale;
            case DND_ACCESS -> R.string.s07_item_dnd_rationale;
            case FULL_SCREEN -> R.string.s07_item_full_screen_rationale;
            case BATTERY -> R.string.s07_item_battery_rationale;
            case EXACT_ALARM -> R.string.s07_item_exact_alarm_rationale;
            case MANUFACTURER -> R.string.s07_item_manufacturer_rationale;
            case ALARM_CHANNEL -> R.string.s07_item_alarm_channel_rationale;
        };
    }

    /**
     * Label of an action button.
     *
     * @param action the action
     * @return the key; {@code 0} for {@link Action#NONE}
     */
    @StringRes
    public static int action(Action action) {
        return switch (action) {
            case ALLOW -> R.string.s07_action_allow;
            case OPEN_SETTINGS -> R.string.s07_action_open_settings;
            case OPEN_GUIDE -> R.string.s07_action_open_guide;
            case NONE -> 0;
        };
    }
}
