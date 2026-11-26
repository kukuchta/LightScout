package app.lightscout.platform.sound;

import app.lightscout.platform.R;

/**
 * The sounds shipped inside the app (DCR-51). The alarm player and, later, the sound preview both open them from
 * here, which is why the files live in {@code :platform} and not in {@code :alarm}: the settings screen cannot see
 * {@code :alarm}.
 *
 * <p>Only "LightScout classic" is bundled for now. A further sound is added here together with its file in
 * {@code res/raw} and a line in {@code BundledSoundsAuditTest}.
 */
public final class BundledSounds {

    private BundledSounds() {
    }

    /**
     * Returns the resource of "LightScout classic", the self-generated tone of release 0.1.0 (Q-69). The file is
     * byte for byte unchanged and opens with {@code Resources.openRawResourceFd}.
     *
     * @return the {@code R.raw} id
     */
    public static int classicRaw() {
        return R.raw.alarm_tone;
    }
}
