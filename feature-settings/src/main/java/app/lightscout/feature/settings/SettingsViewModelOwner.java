package app.lightscout.feature.settings;

/** A screen that shows {@link SettingsValueDialogFragment} as a child and owns the view model the dialog saves to. */
interface SettingsViewModelOwner {

    SettingsViewModel viewModel();
}
