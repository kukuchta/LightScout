package app.lightscout.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parser of {@code dumpsys alarm} output for the instrumented tests (BUG-06 minor 4, BUG-09). Test code only:
 * this file lives in {@code src/sharedTest/java}, which the module's build adds to both the JVM {@code test} and the
 * {@code androidTest} source sets, so that a JVM test can check the parser against a fixture derived from a real
 * device dump.
 *
 * <p>Only the <b>pending-alarm list</b> counts. Since API 31 the dump also carries sections that keep the tag of an
 * alarm after it was cancelled or fired („Removal history”, „Top Alarms”, „Alarm Stats”, „App Alarm history”), and
 * HyperOS 16 adds „Pending user blocked background alarms:” after the list (EV-08: the old parser anchored on the
 * first „Pending alarm” text and so started there, missing the real list).
 *
 * <ul>
 *   <li>Primary: the AOSP list header {@code "<n> pending alarms:"} (regex {@link #PENDING_HEADER}); the list is the
 *       lines after it that are indented deeper than the header, and it ends at the first line at the header's
 *       indentation or less, or at any known non-pending section.</li>
 *   <li>Pre-API 31 format: {@code "Pending alarm batches:"}; the batches are not indented, so that list ends only at a
 *       known non-pending section.</li>
 *   <li>Fallback (header not recognised, e.g. a future OEM format): the whole dump minus the known non-pending
 *       sections. This errs towards „found”, so an {@code assertFalse(pending)} fails loudly instead of passing on a
 *       dump the parser does not understand, while cancelled-alarm history still never counts.</li>
 * </ul>
 */
final class AlarmDump {

    /** AOSP (API 31+) list header, e.g. {@code "  168 pending alarms: "}. */
    static final Pattern PENDING_HEADER = Pattern.compile("^(\\s*)\\d+ pending alarms:\\s*$");

    /** Pre-API 31 list header, e.g. {@code "  Pending alarm batches: 24"}. */
    private static final String LEGACY_HEADER = "Pending alarm batches:";

    /**
     * Sections (trimmed line prefixes) that are not the pending list but may hold alarm tags or {@code Alarm{...}}
     * records of alarms that are no longer (or not really) pending.
     */
    static final List<String> NON_PENDING_SECTIONS = List.of(
            "Pending user blocked background alarms",
            "Pending alarms per uid",
            "App Alarm history",
            "Next wake from idle",
            "Past-due non-wakeup alarms",
            "Allow while idle history",
            "Allow while idle compat history",
            "Removal history",
            "Top Alarms",
            "Alarm Stats",
            "Alarm manager stats");

    /** Sections that run to the end of the dump (their entries are not indented below the title). */
    private static final List<String> TRAILING_SECTIONS = List.of("Removal history", "Top Alarms", "Alarm Stats",
            "Alarm manager stats");

    /** Lines after an {@code Alarm{...}} header searched for its tag. */
    private static final int MAX_RECORD_LINES = 10;

    private AlarmDump() {
    }

    /**
     * Returns the header line ({@code Alarm{...}}, trimmed; it carries the type, e.g. {@code ELAPSED_WAKEUP}) of the
     * pending alarm of {@code packageName} whose tag is {@code tag}. Only {@code Alarm{...}} records of
     * {@code packageName} count; the tag is looked for in the lines that follow the record header, up to the next
     * record.
     *
     * @param dump        {@code dumpsys alarm} output
     * @param packageName package that set the alarm
     * @param tag         alarm tag, e.g. {@code *walarm*:<action>}
     * @return the trimmed header line or {@code null}
     */
    static String pendingAlarmHeader(String dump, String packageName, String tag) {
        List<String> lines = pendingLines(dump);
        for (int i = 0; i < lines.size(); i++) {
            String header = lines.get(i);
            if (!header.contains("Alarm{") || !header.contains(" " + packageName + "}")) {
                continue;
            }
            for (int j = i + 1; j < lines.size() && j <= i + MAX_RECORD_LINES; j++) {
                String line = lines.get(j);
                if (line.contains("Alarm{")) {
                    break;
                }
                if (line.contains(tag)) {
                    return header.trim();
                }
            }
        }
        return null;
    }

    /**
     * Returns the lines of the pending-alarm list (see the class comment for the three modes).
     *
     * @param dump {@code dumpsys alarm} output
     * @return the lines, without the list header
     */
    static List<String> pendingLines(String dump) {
        String[] lines = dump.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PENDING_HEADER.matcher(lines[i]);
            if (m.matches()) {
                return listAfter(lines, i, m.group(1).length(), true);
            }
        }
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith(LEGACY_HEADER)) {
                return listAfter(lines, i, 0, false);
            }
        }
        return withoutNonPendingSections(lines);
    }

    private static List<String> listAfter(String[] lines, int header, int headerIndent, boolean endAtOutdent) {
        List<String> out = new ArrayList<>();
        for (int i = header + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            if (endAtOutdent && indent(line) <= headerIndent) {
                break;
            }
            if (nonPendingSection(line) != null) {
                break;
            }
            out.add(line);
        }
        return out;
    }

    private static List<String> withoutNonPendingSections(String[] lines) {
        List<String> out = new ArrayList<>();
        int skipIndent = -1;   // >= 0 while inside a non-pending section
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String section = nonPendingSection(line);
            if (section != null) {
                if (TRAILING_SECTIONS.contains(section)) {
                    break;
                }
                skipIndent = indent(line);
                continue;
            }
            if (skipIndent >= 0) {
                if (indent(line) > skipIndent) {
                    continue;
                }
                skipIndent = -1;
            }
            out.add(line);
        }
        return out;
    }

    private static String nonPendingSection(String line) {
        String t = line.trim();
        for (String s : NON_PENDING_SECTIONS) {
            if (t.startsWith(s)) {
                return s;
            }
        }
        return null;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && Character.isWhitespace(line.charAt(n))) {
            n++;
        }
        return n;
    }
}
