package app.lightscout.feature.settings;

import app.lightscout.domain.settings.Settings;

/** Immutable values displayed by S-04. */
public record SettingsUiState(Settings settings, String host, int missingRequiredCount, boolean loaded) {
}
