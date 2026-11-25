package app.lightscout.platform.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.Instant;

import app.lightscout.domain.port.EventCode;

/**
 * R-13 line format {@code <ISO-8601 UTC> <LEVEL> <CODE>[:<detail>]}.
 */
public class LogLineTest {

    private static final Instant T = Instant.parse("2026-10-15T12:34:56.789Z");

    @Test
    public void formatsInfoLineWithoutDetail() {
        assertEquals("2026-10-15T12:34:56.789Z INFO SYNC_OK", LogLine.format(T, EventCode.SYNC_OK, null));
    }

    @Test
    public void formatsWarnAndErrorLevelsWithDetail() {
        assertEquals("2026-10-15T12:34:56.789Z WARN SYNC_FAIL:NO_INTERNET:NO_NETWORK",
                LogLine.format(T, EventCode.SYNC_FAIL, "NO_INTERNET:NO_NETWORK"));
        assertEquals("2026-10-15T12:34:56.789Z ERROR STORAGE_ERROR:readings.upsert",
                LogLine.format(T, EventCode.STORAGE_ERROR, "readings.upsert"));
    }

    @Test
    public void wholeSecondsKeepMillis() {
        assertEquals("2026-10-15T00:00:00.000Z INFO HEARTBEAT",
                LogLine.format(Instant.parse("2026-10-15T00:00:00Z"), EventCode.HEARTBEAT, null));
    }

    @Test
    public void parsesTimestampBack() {
        assertEquals(T, LogLine.timestamp(LogLine.format(T, EventCode.NET_LOST, null)));
    }

    @Test
    public void malformedLineHasNoTimestamp() {
        assertNull(LogLine.timestamp("garbage"));
        assertNull(LogLine.timestamp("notatime INFO SYNC_OK"));
        assertNull(LogLine.timestamp(""));
    }
}
