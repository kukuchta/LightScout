package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * CP-08 §9 „JVM — boot-count check” and the WF-04 / WF-10 / F5 receiver decisions (SR-21 allow-lists).
 */
public class BootTest {

    @Test
    public void lockedBoot_configured_postsPaused_notConfigured_nothing() {
        assertEquals(BootDecision.Action.POST_PAUSED, BootDecision.forBoot(BootDecision.LOCKED_BOOT_COMPLETED, true));
        assertEquals(BootDecision.Action.IGNORE, BootDecision.forBoot(BootDecision.LOCKED_BOOT_COMPLETED, false));
    }

    @Test
    public void bootCompleted_andPackageReplaced_startTheService() {
        assertEquals(BootDecision.Action.START_BOOT, BootDecision.forBoot(BootDecision.BOOT_COMPLETED, true));
        assertEquals(BootDecision.Action.START_PACKAGE_REPLACED,
                BootDecision.forBoot(BootDecision.MY_PACKAGE_REPLACED, true));
        assertEquals(BootDecision.Action.IGNORE, BootDecision.forBoot(BootDecision.BOOT_COMPLETED, false));
    }

    @Test
    public void f5_userUnlocked_andSpoofedActions_areIgnored() {
        for (String action : new String[] {"android.intent.action.USER_UNLOCKED", "app.lightscout.FAKE", null,
                BootDecision.TIME_SET}) {
            assertFalse(String.valueOf(action), BootDecision.isBootAction(action));
            assertEquals(BootDecision.Action.IGNORE, BootDecision.forBoot(action, true));
        }
        assertFalse(BootDecision.isTimeAction(BootDecision.BOOT_COMPLETED));
        assertFalse(BootDecision.isTimeAction(null));
    }

    @Test
    public void wf10_timeActions_whenConfigured() {
        assertEquals(BootDecision.Action.TIME_CHANGED, BootDecision.forTime(BootDecision.TIME_SET, true));
        assertEquals(BootDecision.Action.TIME_CHANGED, BootDecision.forTime(BootDecision.TIMEZONE_CHANGED, true));
        assertEquals(BootDecision.Action.IGNORE, BootDecision.forTime(BootDecision.TIME_SET, false));
    }

    @Test
    public void ir76_bootCount_recordedOnBootCompletedOnly_lockedBootWritesNothing() {
        assertTrue(BootDecision.recordsBootCount(BootDecision.BOOT_COMPLETED));
        assertFalse(BootDecision.recordsBootCount(BootDecision.LOCKED_BOOT_COMPLETED));
        assertFalse(BootDecision.recordsBootCount(BootDecision.MY_PACKAGE_REPLACED));
    }

    @Test
    public void ir68_pausedPostedOnlyWhileLocked() {
        assertTrue(BootDecision.postsPaused(BootDecision.Action.POST_PAUSED, false));
        assertFalse(BootDecision.postsPaused(BootDecision.Action.POST_PAUSED, true));
        assertFalse(BootDecision.postsPaused(BootDecision.Action.START_BOOT, false));
    }

    @Test
    public void ir76_check_unhandledBoot_loggedExactlyOnce_checkItselfNeverWrites() {
        Fakes.FakeFlags flags = new Fakes.FakeFlags();
        flags.handledBootCount = 100;
        Fakes.FakeBootCount count = new Fakes.FakeBootCount();
        count.count = 101;
        Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
        BootNotHandledGuard guard = new BootNotHandledGuard();
        BootCountCheck check = new BootCountCheck(count, flags, logger, guard);
        assertTrue(check.run());
        assertFalse(check.run());                                       // job run in the same boot
        assertFalse(new BootCountCheck(count, flags, logger, guard).run());   // new instance, same process
        assertEquals(java.util.List.of("BOOT_NOT_HANDLED"), logger.lines);
        assertEquals(0, flags.bootCountWrites);                         // healing is the first tick's job
        assertEquals(0, flags.ceCalls);
    }

    @Test
    public void q67_checkSkippedOnlyInBootHandledProcess() {
        Fakes.FakeFlags flags = new Fakes.FakeFlags();
        flags.handledBootCount = 110;
        Fakes.FakeBootCount count = new Fakes.FakeBootCount();
        count.count = 111;
        Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
        BootNotHandledGuard marked = new BootNotHandledGuard();
        marked.markBootHandled();
        assertFalse(new BootCountCheck(count, flags, logger, marked).run());
        assertTrue(logger.lines.isEmpty());
        assertTrue(new BootCountCheck(count, flags, logger, new BootNotHandledGuard()).run());   // other process
    }

    @Test
    public void bootCountMatch_orNotConfigured_orUnavailable_logsNothing() {
        Fakes.FakeFlags flags = new Fakes.FakeFlags();
        Fakes.FakeBootCount count = new Fakes.FakeBootCount();
        count.count = 120;
        Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
        BootNotHandledGuard guard = new BootNotHandledGuard();
        flags.handledBootCount = 120;
        assertFalse(new BootCountCheck(count, flags, logger, guard).run());
        flags.handledBootCount = 1;
        flags.configured = false;
        assertFalse(new BootCountCheck(count, flags, logger, guard).run());
        flags.configured = true;
        count.count = -1;
        assertFalse(new BootCountCheck(count, flags, logger, guard).run());
        assertTrue(logger.lines.isEmpty());
    }
}
