package app.lightscout.platform.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The local log ring buffer (CP-05 §8.6): event lines in an app-private CE file, never larger than
 * {@link #MAX_BYTES}, entries older than {@link #MAX_AGE} dropped. Not thread-safe: the logger calls it from its
 * single writer thread only.
 *
 * <p>Compaction (read, filter, atomic rewrite) runs on the first append of the process, when the oldest entry
 * passes {@link #MAX_AGE}, and when the next line would exceed {@link #MAX_BYTES}; in the size case the buffer is
 * trimmed from the oldest end to {@link #COMPACT_TARGET_BYTES} so that compaction does not run on every line.
 * Lines without a readable timestamp are dropped.
 */
public final class RingBuffer {

    /** Maximum file size: 1 MB. */
    public static final long MAX_BYTES = 1024L * 1024L;

    /** Size after a size-triggered compaction (¾ of the maximum). */
    public static final long COMPACT_TARGET_BYTES = MAX_BYTES * 3 / 4;

    /** Maximum entry age: 7 days. */
    public static final Duration MAX_AGE = Duration.ofDays(7);

    private final LineFile file;
    private long size = -1;
    private Instant nextAgeCheck = Instant.MIN;

    /**
     * Creates the buffer over a file. Nothing is read until the first {@link #append}.
     *
     * @param file the backing file
     */
    public RingBuffer(LineFile file) {
        this.file = file;
    }

    /**
     * Appends one line, compacting first if needed.
     *
     * @param line a line produced by {@link LogLine#format} (no terminator)
     * @param now  the current time (from IF-15)
     * @throws IOException on I/O failure
     */
    public void append(String line, Instant now) throws IOException {
        long lineBytes = bytes(line);
        if (size < 0) {
            size = file.length();
            compact(now, MAX_BYTES - lineBytes);
        } else if (!now.isBefore(nextAgeCheck)) {
            compact(now, MAX_BYTES - lineBytes);
        }
        if (size + lineBytes > MAX_BYTES) {
            compact(now, COMPACT_TARGET_BYTES - lineBytes);
        }
        if (lineBytes > MAX_BYTES) {
            return; // cannot happen with R-13 lines; never break the size bound
        }
        file.append(line);
        size += lineBytes;
        if (nextAgeCheck.equals(Instant.MAX)) {
            nextAgeCheck = now.plus(MAX_AGE);
        }
    }

    private void compact(Instant now, long budget) throws IOException {
        Instant cutoff = now.minus(MAX_AGE);
        List<String> kept = new ArrayList<>();
        List<Instant> times = new ArrayList<>();
        for (String line : file.readLines()) {
            Instant time = LogLine.timestamp(line);
            if (time != null && !time.isBefore(cutoff)) {
                kept.add(line);
                times.add(time);
            }
        }
        long total = 0;
        for (String line : kept) {
            total += bytes(line);
        }
        int first = 0;
        while (first < kept.size() && total > budget) {
            total -= bytes(kept.get(first));
            first++;
        }
        List<String> result = kept.subList(first, kept.size());
        file.rewrite(result);
        size = total;
        nextAgeCheck = result.isEmpty() ? Instant.MAX : oldest(times.subList(first, times.size())).plus(MAX_AGE);
    }

    private static Instant oldest(List<Instant> times) {
        Instant oldest = times.get(0);
        for (Instant time : times) {
            if (time.isBefore(oldest)) {
                oldest = time;
            }
        }
        return oldest;
    }

    private static long bytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }
}
