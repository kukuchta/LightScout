package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * BUG-09: the test-only {@code dumpsys alarm} parser ({@link AlarmDump}, used by the instrumented
 * {@code DeviceSupport.alarmPending / alarmHeader}). The fixture is trimmed from the HyperOS 16 dump taken on RD-1
 * while TICK and WATCHDOG were armed (EV-08 {@code dumpsys-alarm-live.txt}): structure and LightScout records kept,
 * other apps' records reduced and renamed to {@code com.example.other}.
 */
public class AlarmDumpTest {

    private static final String PKG = "app.lightscout.monitor.test";
    private static final String TICK = tag(MonitorIntents.ACTION_TICK);
    private static final String WATCHDOG = tag(MonitorIntents.ACTION_WATCHDOG);
    private static final String HEARTBEAT = tag(MonitorIntents.ACTION_HEARTBEAT);
    private static final String BOOT_RETRY = tag(MonitorIntents.ACTION_BOOT_RETRY);

    @Test
    public void hyperOs16_armedTickAndWatchdog_foundWithTheirType() {
        String dump = fixture();
        String tick = AlarmDump.pendingAlarmHeader(dump, PKG, TICK);
        assertNotNull("TICK pending (EV-08: armed, missed by the old parser)", tick);
        assertTrue(tick, tick.startsWith("ELAPSED_WAKEUP #3: Alarm{9b3e87 type 2"));
        String watchdog = AlarmDump.pendingAlarmHeader(dump, PKG, WATCHDOG);
        assertNotNull("WATCHDOG pending", watchdog);
        assertTrue(watchdog, watchdog.startsWith("ELAPSED_WAKEUP #5:"));
    }

    @Test
    public void hyperOs16_listStartsAtTheCountHeader_notAtPendingUserBlocked() {
        List<String> lines = AlarmDump.pendingLines(fixture());
        assertTrue(lines.get(0), lines.get(0).trim().startsWith("ELAPSED_WAKEUP #1: Alarm{"));
        for (String line : lines) {
            assertFalse(line, line.contains("LazyAlarmStore") || line.contains("Pending user blocked")
                    || line.contains("Removal history") || line.contains("Alarm Stats"));
        }
        assertTrue(lines.get(lines.size() - 1).contains("idle-options="));
    }

    @Test
    public void cancelledAlarmTags_inHistoryAndStats_doNotCount() {
        String dump = fixture();
        assertNull("only in Removal history / Top Alarms", AlarmDump.pendingAlarmHeader(dump, PKG, HEARTBEAT));
        assertNull("only in Removal history / Alarm Stats", AlarmDump.pendingAlarmHeader(dump, PKG, BOOT_RETRY));
    }

    @Test
    public void anotherPackagesRecord_withTheSameTag_doesNotCount() {
        // record #4 belongs to com.example.other and carries the HEARTBEAT tag
        assertNull(AlarmDump.pendingAlarmHeader(fixture(), PKG, HEARTBEAT));
        assertNotNull(AlarmDump.pendingAlarmHeader(fixture(), "com.example.other", HEARTBEAT));
    }

    @Test
    public void recordsInPendingUserBlocked_andNextWakeFromIdle_doNotCount() {
        String blocked = "  Pending user blocked background alarms: \n"
                + "    ELAPSED_WAKEUP #1: Alarm{1 type 2 origWhen 1 whenElapsed 1 " + PKG + "}\n"
                + "      tag=" + HEARTBEAT + "\n";
        String idle = "  Next wake from idle: Alarm{2 type 2 origWhen 1 whenElapsed 1 " + PKG + "}\n"
                + "    tag=" + BOOT_RETRY + "\n";
        String dump = fixture()
                .replace("  Pending user blocked background alarms: \n    none\n", blocked)
                .replace("  Next wake from idle: ", idle + "  Next wake from idle: ");
        assertTrue(dump.contains(blocked));
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, HEARTBEAT));
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, BOOT_RETRY));
        assertNotNull(AlarmDump.pendingAlarmHeader(dump, PKG, TICK));
    }

    @Test
    public void tickCancelled_notFound_althoughItsTagIsInRemovalHistory() {
        String dump = fixture()
                .replace("      tag=" + TICK + "\n", "      tag=*walarm*:other\n")
                .replace("tag=" + HEARTBEAT + " \n", "tag=" + TICK + " \n");
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, TICK));
        assertNotNull(AlarmDump.pendingAlarmHeader(dump, PKG, WATCHDOG));
    }

    @Test
    public void headerRegex_matchesTheCountLineOnly() {
        assertTrue(AlarmDump.PENDING_HEADER.matcher("  168 pending alarms: ").matches());
        assertTrue(AlarmDump.PENDING_HEADER.matcher("  0 pending alarms:").matches());
        assertFalse(AlarmDump.PENDING_HEADER.matcher("  Pending user blocked background alarms: ").matches());
        assertFalse(AlarmDump.PENDING_HEADER.matcher("  Pending alarms per uid: [1000:43]").matches());
        assertFalse(AlarmDump.PENDING_HEADER.matcher("  Past-due non-wakeup alarms: (none)").matches());
    }

    @Test
    public void windowsLineEndings_parsed() {
        String dump = fixture().replace("\n", "\r\n");
        assertNotNull(AlarmDump.pendingAlarmHeader(dump, PKG, TICK));
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, HEARTBEAT));
    }

    @Test
    public void legacyBatchFormat_listUntilTheNextSection() {
        String dump = "Current Alarm Manager state:\n"
                + "  Pending alarm batches: 1\n"
                + "Batch{c0e5e8a num=1 start=1 end=2 flgs=0x8}:\n"
                + "    ELAPSED_WAKEUP #0: Alarm{3 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + TICK + "\n"
                + "  Past-due non-wakeup alarms: (none)\n"
                + "    ELAPSED_WAKEUP #0: Alarm{4 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + WATCHDOG + "\n";
        assertNotNull(AlarmDump.pendingAlarmHeader(dump, PKG, TICK));
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, WATCHDOG));
    }

    @Test
    public void legacyBatchFormat_endsAtTheFirstNonPendingSection_unlikeTheFallback() {
        // The fallback would resume after the sibling line that ends „Past-due…”; legacy mode must not.
        String dump = "Current Alarm Manager state:\n"
                + "  Pending alarm batches: 1\n"
                + "Batch{c0e5e8a num=1 start=1 end=2 flgs=0x8}:\n"
                + "    ELAPSED_WAKEUP #0: Alarm{3 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + TICK + "\n"
                + "  Past-due non-wakeup alarms: (none)\n"
                + "    Number of delayed alarms: 0\n"
                + "  Broadcast ref count: 0\n"
                + "    ELAPSED_WAKEUP #0: Alarm{4 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + WATCHDOG + "\n";
        assertNotNull(AlarmDump.pendingAlarmHeader(dump, PKG, TICK));
        assertNull("legacy list ended at Past-due", AlarmDump.pendingAlarmHeader(dump, PKG, WATCHDOG));
    }

    @Test
    public void fallback_pendingRecordAfterTheSiblingLineThatEndsABlockedSection_isFound() {
        String dump = "Current Alarm Manager state:\n"
                + "  Pending user blocked background alarms: \n"
                + "    ELAPSED_WAKEUP #0: Alarm{5 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + HEARTBEAT + "\n"
                + "  Broadcast ref count: 0\n"
                + "    ELAPSED_WAKEUP #1: Alarm{6 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + TICK + "\n";
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, HEARTBEAT));
        String tick = AlarmDump.pendingAlarmHeader(dump, PKG, TICK);
        assertNotNull("skipping ends at the sibling line", tick);
        assertTrue(tick, tick.startsWith("ELAPSED_WAKEUP #1:"));
    }

    @Test
    public void unknownHeader_fallbackScansAllButTheNonPendingSections() {
        String dump = "Current Alarm Manager state:\n"
                + "  Pending alarms (OEM format):\n"
                + "    ELAPSED_WAKEUP #0: Alarm{3 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + TICK + "\n"
                + "  Pending user blocked background alarms: \n"
                + "    ELAPSED_WAKEUP #0: Alarm{5 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + HEARTBEAT + "\n"
                + "  Broadcast ref count: 0\n"
                + "  Removal history:\n"
                + "    ELAPSED_WAKEUP #0: Alarm{4 type 2 when 1 " + PKG + "}\n"
                + "      tag=" + WATCHDOG + "\n";
        assertNotNull("safe direction: a pending record is still found", AlarmDump.pendingAlarmHeader(dump, PKG,
                TICK));
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, HEARTBEAT));
        assertNull(AlarmDump.pendingAlarmHeader(dump, PKG, WATCHDOG));
        List<String> lines = AlarmDump.pendingLines(dump);
        assertEquals(1, lines.stream().filter(l -> l.contains("Alarm{")).count());
        assertTrue("a blocked section ends at the next sibling line",
                lines.stream().anyMatch(l -> l.contains("Broadcast ref count")));
    }

    private static String tag(String action) {
        return "*walarm*:" + action;
    }

    private static String fixture() {
        try (InputStream in = AlarmDumpTest.class.getResourceAsStream("dumpsys-alarm-hyperos16.txt")) {
            assertNotNull("fixture on the test classpath", in);
            // a Windows checkout may carry CRLF; the device emits LF (CRLF parsing is covered separately)
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
