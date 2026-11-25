package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PumpAlertTextTest {

    @Test
    public void stripsControlsAndCapsAtTwoHundredCodePoints() {
        String input = "blocked\n" + "x".repeat(500);
        String actual = PumpAlertText.sanitize(input);
        assertEquals(200, actual.codePointCount(0, actual.length()));
        assertEquals("blocked" + "x".repeat(193), actual);
    }

    @Test
    public void preservesUnicodeCodePointsAndHandlesNull() {
        assertEquals("🚨".repeat(200), PumpAlertText.sanitize("🚨".repeat(201)));
        assertEquals("", PumpAlertText.sanitize(null));
    }
}
