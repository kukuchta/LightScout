package app.lightscout.nightscout;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.TokenSource;

/**
 * Hand-written fakes of the consumed ports (CP-00 §10: consumed ports through fakes; no Mockito).
 */
final class Fakes {

    /** 2030-01-01T00:00:00Z — synthetic fixture epoch. */
    static final long T0 = 1_893_456_000_000L;

    /** Obviously fake access token (never a real one — SR-39). */
    static final String FAKE_TOKEN = "fake-token-0000-for-tests";

    private Fakes() {
    }

    /** Settable IF-15 clock. */
    static final class FakeClock implements Clock {
        volatile Instant now = Instant.ofEpochMilli(T0);
        volatile ZoneId zone = ZoneId.of("Europe/Warsaw");

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return now.toEpochMilli();
        }

        @Override
        public ZoneId zone() {
            return zone;
        }
    }

    /** Settable IF-19 connectivity. */
    static final class FakeConnectivity implements Connectivity {
        volatile boolean online = true;

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public void addListener(Consumer<Boolean> l) {
            // not needed by the gateway
        }
    }

    /** IF-66 logger that records every line it would write. */
    static final class RecordingLogger implements Logger {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void event(EventCode code) {
            lines.add(code.name());
        }

        @Override
        public void event(EventCode code, String detail) {
            lines.add(code.name() + ":" + detail);
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
            lines.add("CRASH:" + exceptionClass + "@" + topAppFrame);
        }

        @Override
        public void debug(String tag, String message) {
            lines.add("DEBUG " + tag + " " + message);
        }

        boolean contains(String line) {
            synchronized (lines) {
                return lines.contains(line);
            }
        }
    }

    /** IF-61 token source; keeps every array it handed out so tests can check the caller wiped it. */
    static final class FakeTokenSource implements TokenSource {
        final List<char[]> issued = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        volatile String token;

        FakeTokenSource(String token) {
            this.token = token;
        }

        @Override
        public Optional<char[]> token() {
            calls.incrementAndGet();
            if (token == null) {
                return Optional.empty();
            }
            char[] chars = token.toCharArray();
            issued.add(chars);
            return Optional.of(chars);
        }
    }
}
