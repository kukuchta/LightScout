package app.lightscout.platform.sound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import app.lightscout.domain.sound.ImportOutcome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DCR-51: a phone sound or a picked file is copied byte for byte, but never beyond the size limit, and the user can
 * leave: the copy stops at once on cancel and never reads a huge source to the end.
 */
public class BoundedCopyTest {

    private static byte[] bytes(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i * 31);
        }
        return out;
    }

    @Test
    public void everyByteIsCopiedAsItIs() throws IOException {
        byte[] data = bytes(100_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportOutcome outcome = BoundedCopy.copy(new ByteArrayInputStream(data), out, 1_000_000, () -> false);
        assertEquals(ImportOutcome.OK, outcome);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void aSourceExactlyAtTheLimitIsAccepted() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportOutcome outcome = BoundedCopy.copy(new ByteArrayInputStream(bytes(50_000)), out, 50_000, () -> false);
        assertEquals(ImportOutcome.OK, outcome);
        assertEquals(50_000, out.size());
    }

    @Test
    public void aSourceOneByteOverTheLimitIsTooLarge() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImportOutcome outcome = BoundedCopy.copy(new ByteArrayInputStream(bytes(50_001)), out, 50_000, () -> false);
        assertEquals(ImportOutcome.TOO_LARGE, outcome);
    }

    @Test
    public void aHugeSourceIsNotReadToTheEnd() throws IOException {
        AtomicLong read = new AtomicLong();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                read.incrementAndGet();
                return 7;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                read.addAndGet(len);
                java.util.Arrays.fill(b, off, off + len, (byte) 7);
                return len;
            }
        };
        ImportOutcome outcome = BoundedCopy.copy(endless, new ByteArrayOutputStream(), 100_000, () -> false);
        assertEquals(ImportOutcome.TOO_LARGE, outcome);
        assertTrue("read " + read.get(), read.get() <= 100_000 + 2L * BoundedCopy.CHUNK);
    }

    @Test
    public void cancelStopsTheCopy() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] polls = {0};
        ImportOutcome outcome = BoundedCopy.copy(new ByteArrayInputStream(bytes(10 * BoundedCopy.CHUNK)), out,
                1_000_000, () -> ++polls[0] > 2);
        assertEquals(ImportOutcome.CANCELLED, outcome);
        assertTrue(out.size() < 10 * BoundedCopy.CHUNK);
    }

    @Test
    public void anEmptySourceCopiesNothingAndIsLeftToTheCaller() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(ImportOutcome.OK, BoundedCopy.copy(new ByteArrayInputStream(new byte[0]), out, 10, () -> false));
        assertEquals(0, out.size());
    }

    @Test
    public void aReadErrorPropagates() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("provider gone");
            }
        };
        try {
            BoundedCopy.copy(broken, new ByteArrayOutputStream(), 10, () -> false);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("provider gone", expected.getMessage());
        }
    }
}
