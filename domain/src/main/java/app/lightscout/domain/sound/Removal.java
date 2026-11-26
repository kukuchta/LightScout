package app.lightscout.domain.sound;

import java.util.List;
import java.util.Objects;

import app.lightscout.domain.alarm.AlarmType;

/**
 * An own sound that was removed but can still be restored with Undo (DCR-51). The file stays on disk until the
 * removal is finalised.
 *
 * @param sound      the removed sound
 * @param reassigned the alarm types that used it and now play the classic tone, most urgent first
 */
public record Removal(OwnSound sound, List<AlarmType> reassigned) {

    /**
     * Copies the list.
     */
    public Removal {
        Objects.requireNonNull(sound, "sound");
        reassigned = List.copyOf(reassigned);
    }
}
