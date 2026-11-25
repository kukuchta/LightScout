package app.lightscout.domain.model;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Therapy profile with its basal schedule (ENT-04, 03 §6.1).
 *
 * @param id    Nightscout {@code identifier}
 * @param start profile start (UTC)
 * @param zone  profile time zone
 * @param basal unmodifiable basal schedule
 */
public record Profile(String id, Instant start, ZoneId zone, List<BasalStep> basal) {

    /**
     * Creates a profile; the basal list is copied unmodifiably.
     *
     * @throws NullPointerException if {@code basal} or one of its elements is null
     */
    public Profile {
        basal = List.copyOf(Objects.requireNonNull(basal, "basal"));
    }
}
