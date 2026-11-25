package app.lightscout.platform;

import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * Instrumented-test logger that records {@code CODE[:detail]} strings.
 */
public final class RecordingLogger implements Logger {

    /** Recorded events in call order. */
    public final List<String> events = new ArrayList<>();

    @Override
    public synchronized void event(EventCode code) {
        events.add(code.name());
    }

    @Override
    public synchronized void event(EventCode code, String detail) {
        events.add(code.name() + ":" + detail);
    }

    @Override
    public synchronized void crash(String exceptionClass, String topAppFrame) {
        events.add("CRASH:" + exceptionClass + "@" + topAppFrame);
    }

    @Override
    public synchronized void debug(String tag, String message) {
        events.add("DEBUG:" + tag);
    }
}
