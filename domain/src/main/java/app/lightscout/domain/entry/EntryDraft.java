package app.lightscout.domain.entry;

import app.lightscout.domain.model.EntryType;

import java.time.Instant;
import java.util.Objects;

/**
 * An S-02 entry draft (ENT-12, CP-00A R-9). It carries one {@code identifier} for its whole S-02 session (GIR-3).
 *
 * @param identifier            Nightscout {@code identifier} (UUID v4 generated once for CREATE)
 * @param mode                  create or edit
 * @param type                  entry type; nullable until chosen
 * @param value                 mg/dL (BG), g (CARBS) or exact milli-U (BOLUS); null for SITE / SENSOR; nullable
 * @param time                  entry time; null = „Now”, resolved at Save (R-75)
 * @param expectedSrvModifiedMs EDIT only; nullable
 */
public record EntryDraft(String identifier, EntryMode mode, EntryType type,
                         Integer value,
                         Instant time,
                         Long expectedSrvModifiedMs) {

    /**
     * Creates a draft.
     *
     * @throws NullPointerException if {@code identifier} or {@code mode} is null
     */
    public EntryDraft {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(mode, "mode");
    }
}
