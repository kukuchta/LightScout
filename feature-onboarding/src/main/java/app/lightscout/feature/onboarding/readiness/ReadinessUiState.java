package app.lightscout.feature.onboarding.readiness;

import app.lightscout.platform.ReadinessItemId;

import java.util.List;

/**
 * What S-07 shows (CP-14 §7.4).
 *
 * @param items                the shown checklist items (C-S07-04) in display order 1–5, 7, 6; empty before the
 *                             first check
 * @param missingRequired      shown ∧ required ∧ not done (item 6 is never counted)
 * @param allSet               „All set — alarms can ring.” is shown (every shown required item done)
 * @param doneVisible          C-S07-05 „Done” exists (onboarding mode only)
 * @param doneEnabled          „Done” accepts a tap: the first check result has arrived and no completion is in flight
 *                             (IR-98, GIR-1)
 * @param missingDialogVisible C-S07-07 „Some settings are missing” is shown
 */
public record ReadinessUiState(List<Item> items, int missingRequired, boolean allSet, boolean doneVisible,
                               boolean doneEnabled, boolean missingDialogVisible) {

    /**
     * Creates the state with an unmodifiable copy of the items.
     *
     * @param items                see the record description
     * @param missingRequired      see the record description
     * @param allSet               see the record description
     * @param doneVisible          see the record description
     * @param doneEnabled          see the record description
     * @param missingDialogVisible see the record description
     */
    public ReadinessUiState {
        items = List.copyOf(items);
    }

    /** Leading status of an item (icon shape + word, never colour alone — M3-29). */
    public enum Status {
        /** ✓ — supporting „Done”, no button. */
        DONE,
        /** ! — rationale and an action button. */
        NEEDS_ATTENTION,
        /** ! — item 1 only: supporting „Turned off in system settings” and „Open settings” (P3, CP-00A R-74). */
        PERMANENTLY_DENIED,
        /** ↗ — item 6 „Manufacturer settings”. */
        RECOMMENDED
    }

    /** Trailing action button of an item (A-S07-1…3). */
    public enum Action {
        /** No button (done). */
        NONE,
        /** „Allow”. */
        ALLOW,
        /** „Open settings” (item 7, M3-32; item 1 permanently denied). */
        OPEN_SETTINGS,
        /** „Open guide” (item 6 → S-08). */
        OPEN_GUIDE
    }

    /**
     * One checklist row.
     *
     * @param id     the readiness item
     * @param status the leading status
     * @param action the button
     * @param error  the inline M3-30 error „Couldn't open this setting. …” is shown
     */
    public record Item(ReadinessItemId id, Status status, Action action, boolean error) {
    }
}
