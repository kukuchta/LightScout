package app.lightscout.feature.onboarding.disclaimer;

/**
 * What S-05 shows (CP-14 §7.2, M3-26).
 *
 * @param acknowledged  C-S05-05 is ticked
 * @param continueEnabled C-S05-04 „Continue” is enabled (ticked and no acceptance in flight — GIR-1)
 * @param helperVisible „Tick the box to continue” is shown above the button (hidden once ticked)
 */
public record DisclaimerUiState(boolean acknowledged, boolean continueEnabled, boolean helperVisible) {
}
