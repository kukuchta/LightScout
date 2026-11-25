package app.lightscout.feature.home;

/**
 * The „calm moment” condition of the one-time support prompt (DCR-46, D-166): the prompt may appear only while S-01
 * shows a fresh reading and nothing needs the user's attention. Display-only decision over {@link HomeUiState}; the
 * schedule and the once-only rule are {@code SupportPromptPolicy} and {@code SupportPromptStore}.
 */
final class SupportPromptGate {

    private SupportPromptGate() {
    }

    /**
     * Returns whether S-01 is calm: a reading is shown and not stale, no fault banner or chip, no alarm ringing and
     * no snooze running.
     *
     * @param s the Home state
     * @return {@code true} when the support prompt may be considered
     */
    static boolean isCalm(HomeUiState s) {
        return s.sgv() != null
                && !s.stale()
                && s.banners().isEmpty()
                && s.snapshot().ringing() == null
                && s.snapshot().snoozes().isEmpty();
    }
}
