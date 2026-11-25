package app.lightscout.domain.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Stored codes of the appearance setting. */
public class ThemeModeTest {

    @Test
    public void codesAreStableAndUnknownCodesReadAsSystem() {
        assertEquals(0, ThemeMode.SYSTEM.code());
        assertEquals(1, ThemeMode.LIGHT.code());
        assertEquals(2, ThemeMode.DARK.code());
        for (ThemeMode mode : ThemeMode.values()) {
            assertEquals(mode, ThemeMode.fromCode(mode.code()));
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromCode(-1));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromCode(3));
        assertEquals(ThemeMode.SYSTEM, Settings.defaults().themeMode());
    }
}
