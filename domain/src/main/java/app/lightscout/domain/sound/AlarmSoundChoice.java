package app.lightscout.domain.sound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.lightscout.domain.alarm.AlarmType;

/**
 * The sound chosen for each alarm type (DCR-51): five independent choices, every type starts on
 * {@link SoundRef#CLASSIC}. Immutable.
 */
public final class AlarmSoundChoice {

    private static final AlarmSoundChoice ALL_CLASSIC = new AlarmSoundChoice(allTypes(SoundRef.CLASSIC));

    private final Map<AlarmType, SoundRef> refs;

    private AlarmSoundChoice(Map<AlarmType, SoundRef> refs) {
        this.refs = Collections.unmodifiableMap(refs);
    }

    private static EnumMap<AlarmType, SoundRef> allTypes(SoundRef ref) {
        EnumMap<AlarmType, SoundRef> map = new EnumMap<>(AlarmType.class);
        for (AlarmType type : AlarmType.values()) {
            map.put(type, ref);
        }
        return map;
    }

    /**
     * Returns the default: every alarm type uses the classic tone.
     *
     * @return the default choice
     */
    public static AlarmSoundChoice allClassic() {
        return ALL_CLASSIC;
    }

    /**
     * Returns the sound of one alarm type.
     *
     * @param type alarm type
     * @return its sound, never {@code null}
     */
    public SoundRef get(AlarmType type) {
        return refs.get(Objects.requireNonNull(type, "type"));
    }

    /**
     * Returns a choice with one type changed.
     *
     * @param type alarm type
     * @param ref  new sound
     * @return a new choice, or this one when nothing changes
     */
    public AlarmSoundChoice with(AlarmType type, SoundRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (get(type).equals(ref)) {
            return this;
        }
        EnumMap<AlarmType, SoundRef> copy = new EnumMap<>(refs);
        copy.put(type, ref);
        return new AlarmSoundChoice(copy);
    }

    /**
     * Returns the types that use a sound other than the classic tone.
     *
     * @return those types in alarm order (most urgent first)
     */
    public List<AlarmType> customisedTypes() {
        List<AlarmType> out = new ArrayList<>();
        for (AlarmType type : AlarmType.values()) {
            if (!SoundRef.CLASSIC.equals(refs.get(type))) {
                out.add(type);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Returns the types that use one sound.
     *
     * @param ref the sound
     * @return those types in alarm order (most urgent first)
     */
    public List<AlarmType> typesUsing(SoundRef ref) {
        List<AlarmType> out = new ArrayList<>();
        for (AlarmType type : AlarmType.values()) {
            if (refs.get(type).equals(ref)) {
                out.add(type);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AlarmSoundChoice choice && refs.equals(choice.refs);
    }

    @Override
    public int hashCode() {
        return refs.hashCode();
    }

    @Override
    public String toString() {
        return "AlarmSoundChoice" + refs;
    }
}
