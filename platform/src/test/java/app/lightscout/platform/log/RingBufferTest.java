package app.lightscout.platform.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import app.lightscout.domain.port.EventCode;

/**
 * CP-05 §9 „Logger ring buffer (JVM)”: never exceeds 1 MB; entries older than 7 days dropped (fake clock); only
 * the event line (timestamp, level, code, vocabulary detail) is written.
 */
public class RingBufferTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Pattern LINE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z (INFO|WARN|ERROR) [A-Z_]+(:[^ ]+)?");

    @Test
    public void neverExceedsOneMegabyte() throws Exception {
        MemoryLineFile file = new MemoryLineFile();
        RingBuffer ring = new RingBuffer(file);
        Instant now = START;
        for (int i = 0; i < 40_000; i++) {
            ring.append(LogLine.format(now, EventCode.SYNC_FAIL, "NS_NOT_RESPONDING:CONNECT_FAILED"), now);
            now = now.plusMillis(10);
            assertTrue(file.length() <= RingBuffer.MAX_BYTES);
        }
        assertTrue(file.maxLength <= RingBuffer.MAX_BYTES);
        assertTrue("compaction ran", file.rewrites > 1);
        assertTrue("buffer keeps a useful amount", file.length() > RingBuffer.COMPACT_TARGET_BYTES / 2);
    }

    @Test
    public void sizeCompactionDropsOldestFirst() throws Exception {
        MemoryLineFile file = new MemoryLineFile();
        RingBuffer ring = new RingBuffer(file);
        Instant now = START;
        String last = null;
        for (int i = 0; i < 30_000; i++) {
            last = LogLine.format(now, EventCode.TICK_LATE, Integer.toString(i));
            ring.append(last, now);
            now = now.plusMillis(1);
        }
        assertEquals(last, file.lines.get(file.lines.size() - 1));
        assertFalse(file.lines.get(0).endsWith("TICK_LATE:0"));
    }

    @Test
    public void entriesOlderThanSevenDaysAreDropped() throws Exception {
        MemoryLineFile file = new MemoryLineFile();
        RingBuffer ring = new RingBuffer(file);
        ring.append(LogLine.format(START, EventCode.BOOT_PAUSED, null), START);
        Instant day3 = START.plus(Duration.ofDays(3));
        ring.append(LogLine.format(day3, EventCode.BOOT_RESUMED, null), day3);

        Instant later = START.plus(Duration.ofDays(7)).plusSeconds(1);
        ring.append(LogLine.format(later, EventCode.SYNC_OK, null), later);

        assertEquals(2, file.lines.size());
        assertTrue(file.lines.get(0).endsWith("BOOT_RESUMED"));
        assertTrue(file.lines.get(1).endsWith("SYNC_OK"));

        Instant muchLater = day3.plus(Duration.ofDays(7)).plusSeconds(1);
        ring.append(LogLine.format(muchLater, EventCode.HEARTBEAT, null), muchLater);
        assertEquals(2, file.lines.size());
        assertTrue(file.lines.get(0).endsWith("SYNC_OK"));
    }

    @Test
    public void entryExactlySevenDaysOldIsKept() throws Exception {
        MemoryLineFile file = new MemoryLineFile();
        RingBuffer ring = new RingBuffer(file);
        ring.append(LogLine.format(START, EventCode.BOOT_PAUSED, null), START);
        Instant now = START.plus(RingBuffer.MAX_AGE);
        ring.append(LogLine.format(now, EventCode.SYNC_OK, null), now);
        assertEquals(2, file.lines.size());
    }

    @Test
    public void firstAppendOfProcessCompactsExistingFile() throws Exception {
        MemoryLineFile file = new MemoryLineFile();
        file.lines.add(LogLine.format(START, EventCode.SYNC_OK, null));
        file.lines.add("corrupted line without timestamp");
        Instant now = START.plus(Duration.ofDays(8));
        new RingBuffer(file).append(LogLine.format(now, EventCode.FGS_START, "BOOT"), now);
        assertEquals(1, file.lines.size());
        assertTrue(file.lines.get(0).endsWith("FGS_START:BOOT"));
    }

    @Test
    public void onlyEventLinesAreWritten() throws Exception {
        MemoryLineFile file = new MemoryLineFile();
        RingBuffer ring = new RingBuffer(file);
        ring.append(LogLine.format(START, EventCode.ALARM_RING, "LOW"), START);
        ring.append(LogLine.format(START, EventCode.CRASH, "IllegalStateException@Foo.bar"), START);
        for (String line : file.lines) {
            assertTrue(line, LINE.matcher(line).matches());
        }
    }
}
