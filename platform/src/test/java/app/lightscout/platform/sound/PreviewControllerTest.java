package app.lightscout.platform.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.sound.PlayableSound;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** DCR-51 S6: the preview rules, on the JVM with hand-written fakes (CP-17, plan section 6). */
public class PreviewControllerTest {

    private final PlayableSound own = PlayableSound.file("/data/own-0123456789abcdef", 100L);

    private Map<AlarmType, PlayableSound> chosen;
    private boolean ringing;
    private RuntimeException resolverFails;
    private final List<String> calls = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private boolean startResult;
    private RuntimeException startThrows;
    private boolean focusGranted;
    private RuntimeException focusThrows;
    private final List<FakeTask> tasks = new ArrayList<>();
    private PreviewController controller;
    private PlayableSound lastStarted;

    private final PreviewController.Output output = new PreviewController.Output() {
        @Override
        public boolean start(PlayableSound sound) {
            calls.add("start");
            lastStarted = sound;
            if (startThrows != null) {
                throw startThrows;
            }
            return startResult;
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    };

    private final PreviewController.Focus focus = new PreviewController.Focus() {
        @Override
        public boolean request() {
            calls.add("focus");
            if (focusThrows != null) {
                throw focusThrows;
            }
            return focusGranted;
        }

        @Override
        public void abandon() {
            calls.add("abandon");
        }
    };

    private final PreviewController.Timer timer = (task, delayMs) -> {
        FakeTask fake = new FakeTask(task, delayMs);
        tasks.add(fake);
        return () -> fake.cancelled = true;
    };

    @Before
    public void setUp() {
        chosen = new EnumMap<>(AlarmType.class);
        startResult = true;
        focusGranted = true;
        controller = new PreviewController(sounds(), () -> ringing, output, focus, timer);
    }

    private AlarmSoundResolver sounds() {
        return new AlarmSoundResolver() {
            @Override
            public PlayableSound playable(AlarmType type) {
                if (resolverFails != null) {
                    throw resolverFails;
                }
                return chosen.getOrDefault(type, PlayableSound.CLASSIC);
            }

            @Override
            public void reportUnplayable(AlarmType type) {
                // not used by the preview
            }
        };
    }

    private void play(AlarmType type) {
        controller.play(type, (t, e) -> events.add(t + ":" + e));
    }

    @Test
    public void playsTheSoundTheAlarmWouldPlayForThatType() {
        chosen.put(AlarmType.HIGH, own);
        play(AlarmType.HIGH);
        assertEquals(own, lastStarted);
        assertEquals(List.of("focus", "start"), calls);
        assertEquals(List.of("HIGH:STARTED"), events);
    }

    @Test
    public void aTypeWithoutAChoicePlaysTheClassicTone() {
        play(AlarmType.LOW);
        assertEquals(PlayableSound.CLASSIC, lastStarted);
    }

    @Test
    public void stopsAfterTenSecondsAtTheLatest() {
        play(AlarmType.LOW);
        assertEquals(1, tasks.size());
        assertEquals(10_000L, tasks.get(0).delayMs);
        calls.clear();
        tasks.get(0).task.run();
        assertEquals(List.of("stop", "abandon"), calls);
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED"), events);
    }

    @Test
    public void endOfTheSoundStopsItAndCancelsTheTimer() {
        play(AlarmType.LOW);
        controller.onCompleted();
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED"), events);
        assertTrue(tasks.get(0).cancelled);
        assertEquals("abandon", calls.get(calls.size() - 1));
    }

    @Test
    public void aSecondPlayOfTheSameTypeStopsIt() {
        play(AlarmType.LOW);
        calls.clear();
        play(AlarmType.LOW);
        assertEquals(List.of("stop", "abandon"), calls);
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED"), events);
    }

    @Test
    public void anotherTypeReplacesThePlayingOne() {
        play(AlarmType.LOW);
        calls.clear();
        play(AlarmType.HIGH);
        assertEquals(List.of("stop", "abandon", "focus", "start"), calls);
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED", "HIGH:STARTED"), events);
        assertTrue(tasks.get(0).cancelled);
        assertFalse(tasks.get(1).cancelled);
    }

    @Test
    public void staleTimerOfAnEarlierPreviewDoesNotStopTheNewOne() {
        play(AlarmType.LOW);
        controller.stop();
        play(AlarmType.HIGH);
        calls.clear();
        tasks.get(0).task.run();   // a timer that was cancelled but still fires
        assertTrue(calls.isEmpty());
        assertEquals("HIGH:STARTED", events.get(events.size() - 1));
    }

    @Test
    public void doesNotStartWhileAnAlarmRings() {
        ringing = true;
        play(AlarmType.LOW);
        assertTrue(calls.isEmpty());
        assertEquals(List.of("LOW:ALARM_RINGING"), events);
        assertTrue(tasks.isEmpty());
    }

    @Test
    public void aPlayingPreviewStopsWhenAnAlarmIsRingingAtTheNextTap() {
        play(AlarmType.LOW);
        ringing = true;
        calls.clear();
        play(AlarmType.HIGH);
        assertEquals(List.of("stop", "abandon"), calls);
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED", "HIGH:ALARM_RINGING"), events);
    }

    @Test
    public void anyFocusLossStopsThePreview() {
        play(AlarmType.NO_DATA);
        calls.clear();
        controller.onFocusLost();
        assertEquals(List.of("NO_DATA:STARTED", "NO_DATA:STOPPED"), events);
        assertEquals(List.of("stop", "abandon"), calls);
        assertTrue(tasks.get(0).cancelled);
    }

    @Test
    public void aFocusCallThatThrowsDoesNotStopThePreviewFromStartingOrEnding() {
        focusThrows = new IllegalStateException("audio service");
        play(AlarmType.LOW);
        controller.stop();
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED"), events);
    }

    @Test
    public void aRingingProbeThatThrowsDoesNotBlockThePreview() {
        controller = new PreviewController(sounds(), () -> {
            throw new IllegalStateException("probe");
        }, output, focus, timer);
        play(AlarmType.LOW);
        assertEquals(List.of("LOW:STARTED"), events);
    }

    @Test(expected = NullPointerException.class)
    public void aNullListenerIsRefusedAtOnce() {
        controller.play(AlarmType.LOW, null);
    }

    @Test
    public void focusThatIsNotGrantedStillPlays() {
        focusGranted = false;
        play(AlarmType.LOW);
        assertEquals(List.of("focus", "start"), calls);
        assertEquals(List.of("LOW:STARTED"), events);
    }

    @Test
    public void aSoundThatCannotBePlayedFailsAndReleasesFocus() {
        startResult = false;
        play(AlarmType.LOW);
        assertEquals(List.of("focus", "start", "abandon"), calls);
        assertEquals(List.of("LOW:FAILED"), events);
        assertTrue(tasks.isEmpty());
    }

    @Test
    public void anOutputThatThrowsIsAFailureNotACrash() {
        startThrows = new IllegalStateException("player gone");
        play(AlarmType.LOW);
        assertEquals(List.of("LOW:FAILED"), events);
        assertEquals("abandon", calls.get(calls.size() - 1));
    }

    @Test
    public void aResolverThatThrowsIsAFailureAndTakesNoFocus() {
        resolverFails = new IllegalStateException("prefs");
        play(AlarmType.LOW);
        assertEquals(List.of("LOW:FAILED"), events);
        assertTrue(calls.isEmpty());
    }

    @Test
    public void stopWhenNothingPlaysDoesNothing() {
        controller.stop();
        controller.onCompleted();
        controller.onFocusLost();
        assertTrue(calls.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    public void stopEndsAPlayingPreviewOnce() {
        play(AlarmType.LOW);
        controller.stop();
        controller.stop();
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED"), events);
    }

    @Test
    public void aPlaybackErrorIsReportedAsFailure() {
        play(AlarmType.LOW);
        controller.onError();
        assertEquals(List.of("LOW:STARTED", "LOW:FAILED"), events);
        assertTrue(tasks.get(0).cancelled);
        assertEquals("abandon", calls.get(calls.size() - 1));
    }

    @Test
    public void everyEventGoesToTheListenerOfItsOwnPlay() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        controller.play(AlarmType.LOW, (t, e) -> first.add(t + ":" + e));
        controller.play(AlarmType.HIGH, (t, e) -> second.add(t + ":" + e));
        assertEquals(List.of("LOW:STARTED", "LOW:STOPPED"), first);
        assertEquals(List.of("HIGH:STARTED"), second);
    }

    private static final class FakeTask {
        private final Runnable task;
        private final long delayMs;
        private boolean cancelled;

        private FakeTask(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }
}
