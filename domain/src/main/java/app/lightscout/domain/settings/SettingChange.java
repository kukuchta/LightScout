package app.lightscout.domain.settings;

import java.util.Objects;

/**
 * One requested settings change (CP-00A R-3).
 *
 * @param key         the setting
 * @param value       the new value (for {@link SettingKey#TARGET_RANGE}: the „From” value)
 * @param secondValue {@link SettingKey#TARGET_RANGE} „To” only; nullable
 */
public record SettingChange(SettingKey key, int value, Integer secondValue) {

    /**
     * Creates a change.
     *
     * @throws NullPointerException if {@code key} is null
     */
    public SettingChange {
        Objects.requireNonNull(key, "key");
    }
}
