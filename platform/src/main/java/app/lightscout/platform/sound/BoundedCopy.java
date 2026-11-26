package app.lightscout.platform.sound;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

import app.lightscout.domain.sound.ImportOutcome;

/**
 * Copies a stream byte for byte with a size limit and a cancel check (DCR-51). Nothing is decoded or changed. The
 * source is never read much beyond the limit, so a huge or endless file cannot fill the storage or hold the thread.
 */
final class BoundedCopy {

    /** Size of one read, in bytes; the cancel flag is polled once per chunk. */
    static final int CHUNK = 16 * 1024;

    private BoundedCopy() {
    }

    /**
     * Copies {@code in} to {@code out}.
     *
     * @param in        the source
     * @param out       the target; bytes written so far stay when the copy stops early
     * @param maxBytes  largest accepted size
     * @param cancelled polled before every read; {@code true} stops the copy
     * @return {@link ImportOutcome#OK}, {@link ImportOutcome#TOO_LARGE} once more than {@code maxBytes} were read, or
     *         {@link ImportOutcome#CANCELLED}
     * @throws IOException when reading or writing fails
     */
    static ImportOutcome copy(InputStream in, OutputStream out, long maxBytes, BooleanSupplier cancelled)
            throws IOException {
        byte[] buffer = new byte[CHUNK];
        long total = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                return ImportOutcome.CANCELLED;
            }
            int n = in.read(buffer);
            if (n < 0) {
                return ImportOutcome.OK;
            }
            total += n;
            if (total > maxBytes) {
                return ImportOutcome.TOO_LARGE;
            }
            out.write(buffer, 0, n);
        }
    }
}
