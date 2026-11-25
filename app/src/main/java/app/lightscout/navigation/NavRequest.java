package app.lightscout.navigation;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.domain.model.EntryType;
import java.util.Objects;

/**
 * One navigation step: a target, its argument (S-06 / S-07 mode, S-02 entry id) and an optional pop-up-to rule.
 *
 * @param target         the destination
 * @param connectionMode S-06 argument; non-null exactly for {@link NavTarget#CONNECTION}
 * @param readinessMode  S-07 argument; non-null exactly for {@link NavTarget#READINESS}
 * @param popUpTo        destination popped up to before navigating; nullable (no pop)
 * @param popInclusive   whether {@code popUpTo} itself is popped too
 * @param entryId        S-02 argument {@code entryId}; only for {@link NavTarget#ENTRY}, {@code null} = create
 * @param initialType    one of five create types; mutually exclusive with entryId
 */
public record NavRequest(NavTarget target, ConnectionMode connectionMode, ReadinessMode readinessMode,
                         NavTarget popUpTo, boolean popInclusive, String entryId, EntryType initialType) {

    /**
     * Validates the argument rule.
     *
     * @throws IllegalArgumentException if the mode or entry id does not match the target
     */
    public NavRequest {
        Objects.requireNonNull(target, "target");
        if ((target == NavTarget.CONNECTION) != (connectionMode != null)
                || (target == NavTarget.READINESS) != (readinessMode != null)
                || (target != NavTarget.ENTRY && (entryId != null || initialType != null))) {
            throw new IllegalArgumentException("mode argument does not match " + target);
        }
        if (target == NavTarget.ENTRY && (entryId == null ? !loggable(initialType)
                : entryId.isEmpty() || initialType != null)) {
            throw new IllegalArgumentException("mode-appropriate entry argument required");
        }
    }

    public NavRequest(NavTarget target, ConnectionMode connectionMode, ReadinessMode readinessMode,
                      NavTarget popUpTo, boolean popInclusive, String entryId) {
        this(target, connectionMode, readinessMode, popUpTo, popInclusive, entryId, null);
    }

    /**
     * A step without an entry id.
     *
     * @param target         the destination
     * @param connectionMode S-06 argument
     * @param readinessMode  S-07 argument
     * @param popUpTo        destination popped up to; nullable
     * @param popInclusive   whether {@code popUpTo} itself is popped too
     */
    public NavRequest(NavTarget target, ConnectionMode connectionMode, ReadinessMode readinessMode,
                      NavTarget popUpTo, boolean popInclusive) {
        this(target, connectionMode, readinessMode, popUpTo, popInclusive, null);
    }

    /**
     * S-02 edit-only route.
     *
     * @param identifier non-null, nonempty identifier of the entry to edit
     * @return the request
     */
    public static NavRequest entry(String identifier) {
        return new NavRequest(NavTarget.ENTRY, null, null, null, false, identifier);
    }

    public static NavRequest newEntry(EntryType initialType) {
        return new NavRequest(NavTarget.ENTRY, null, null, null, false, null, initialType);
    }

    private static boolean loggable(EntryType t) {
        return t == EntryType.CARBS || t == EntryType.BOLUS || t == EntryType.BG_CHECK
                || t == EntryType.SITE_CHANGE || t == EntryType.SENSOR_CHANGE;
    }

    /**
     * S-06 with a mode.
     *
     * @param mode ONBOARDING or EDIT
     * @return the request
     */
    public static NavRequest connection(ConnectionMode mode) {
        return new NavRequest(NavTarget.CONNECTION, Objects.requireNonNull(mode, "mode"), null, null, false);
    }

    /**
     * S-07 with a mode.
     *
     * @param mode ONBOARDING or CHECK
     * @return the request
     */
    public static NavRequest readiness(ReadinessMode mode) {
        return new NavRequest(NavTarget.READINESS, null, Objects.requireNonNull(mode, "mode"), null, false);
    }

    /**
     * A graph target that replaces everything up to and including {@code popUpTo}.
     *
     * @param target  HOME or ONBOARDING
     * @param popUpTo the destination popped inclusively
     * @return the request
     */
    public static NavRequest replacing(NavTarget target, NavTarget popUpTo) {
        return new NavRequest(target, null, null, Objects.requireNonNull(popUpTo, "popUpTo"), true);
    }
}
