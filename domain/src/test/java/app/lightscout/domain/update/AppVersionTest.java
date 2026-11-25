package app.lightscout.domain.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DCR-49: only a plain x.y.z is a stable version; versions compare number by number, not as text. */
public class AppVersionTest {

    @Test
    public void parsesAPlainVersion() {
        assertEquals(new AppVersion(0, 2, 10), AppVersion.parseStable("0.2.10").orElseThrow());
    }

    @Test
    public void refusesNightlyAndOtherNames() {
        assertTrue(AppVersion.parseStable("0.1.0-nightly.20261125.abc1234").isEmpty());
        assertTrue(AppVersion.parseStable("0.1.0-debug").isEmpty());
        assertTrue(AppVersion.parseStable("v0.1.0").isEmpty());
        assertTrue(AppVersion.parseStable("0.1").isEmpty());
        assertTrue(AppVersion.parseStable("").isEmpty());
        assertTrue(AppVersion.parseStable(null).isEmpty());
    }

    @Test
    public void comparesNumbersNotText() {
        AppVersion nine = new AppVersion(0, 1, 9);
        AppVersion ten = new AppVersion(0, 1, 10);
        assertTrue(ten.compareTo(nine) > 0);
        assertTrue(new AppVersion(1, 0, 0).compareTo(new AppVersion(0, 99, 99)) > 0);
        assertTrue(new AppVersion(0, 2, 0).compareTo(new AppVersion(0, 1, 99)) > 0);
        assertEquals(0, nine.compareTo(new AppVersion(0, 1, 9)));
        assertFalse(nine.compareTo(ten) > 0);
    }

    @Test
    public void printsAsDottedNumbers() {
        assertEquals("1.20.3", new AppVersion(1, 20, 3).toString());
    }

    @Test
    public void releasePageIsBuiltFromTheVersionOnly() {
        assertEquals("https://github.com/kukuchta/LightScout/releases/tag/v0.2.0",
                new UpdateOffer(new AppVersion(0, 2, 0)).pageUrl());
    }
}
