package app.lightscout.storage;

import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/** IF-66 fake that records event lines as {@code CODE} or {@code CODE:detail}. */
final class RecordingLogger implements Logger {

    final List<String> lines = new ArrayList<>();

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
        lines.add("DEBUG");
    }
}
