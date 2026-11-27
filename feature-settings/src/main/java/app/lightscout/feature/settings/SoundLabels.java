package app.lightscout.feature.settings;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import java.util.ArrayList;
import java.util.List;

/** Which sound name a row shows and which alarm types count as customised (DCR-51). No Android classes. */
final class SoundLabels {

    /** The five alarm types in the order of the design: S-13 rows and the S-04 summary. */
    static final List<AlarmType> ORDER = List.of(AlarmType.URGENT_LOW, AlarmType.LOW, AlarmType.NO_DATA,
            AlarmType.NOT_RUNNING, AlarmType.HIGH);

    private SoundLabels() {
    }

    /**
     * Returns the name of a chosen sound, or {@code null} when the row shows "LightScout classic". A reference the
     * library no longer holds plays as classic (the resolver falls back the same way), so it is shown as classic too.
     *
     * @param library the stored library
     * @param ref     the choice of one alarm type
     * @return the stored display name, or {@code null} for classic
     */
    static String customName(SoundLibrary library, SoundRef ref) {
        switch (ref.kind()) {
            case OWN:
                return library.ownSound(ref.key()).map(OwnSound::displayName).orElse(null);
            case PHONE:
                return library.phoneCopies().stream().filter(copy -> copy.key().equals(ref.key()))
                        .map(PhoneCopy::title).findFirst().orElse(null);
            default:
                return null;
        }
    }

    /**
     * Returns the alarm types that play something other than the classic tone, in design order.
     *
     * @param library the stored library
     * @return the customised types
     */
    static List<AlarmType> customisedTypes(SoundLibrary library) {
        List<AlarmType> customised = new ArrayList<>();
        for (AlarmType type : ORDER) {
            if (customName(library, library.choice().get(type)) != null) {
                customised.add(type);
            }
        }
        return List.copyOf(customised);
    }
}
