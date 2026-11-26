package app.lightscout.alarm;

import android.app.Application;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.alarm.AlarmStateMemory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test stand-in for the CP-15 composition root: implements {@link AlarmRuntimeProvider} with an in-memory IF-14, a
 * recording IF-25 (snooze applies {@code Stop(type)} on the monitor thread like CP-02 does), a shiftable clock (dark
 * window tests) and the real CMP-26 channels.
 */
public class TestAlarmApplication extends Application implements AlarmRuntimeProvider {

    final ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();
    final List<AlarmType> snoozes = new CopyOnWriteArrayList<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final ShiftableClock clock = new ShiftableClock();
    final MemoryStore store = new MemoryStore();
    final AlarmStateMemory memory = new AlarmStateMemory();

    private NotificationChannels channels;
    private AlarmRuntime runtime;

    @Override
    public synchronized AlarmRuntime alarmRuntime() {
        if (runtime == null) {
            Logger logger = new Logger() {
                @Override
                public void event(EventCode code) {
                    events.add(code.name());
                }

                @Override
                public void event(EventCode code, String detail) {
                    events.add(code.name() + ":" + detail);
                }

                @Override
                public void crash(String exceptionClass, String topAppFrame) {
                    events.add("CRASH");
                }

                @Override
                public void debug(String tag, String message) {
                    // not recorded
                }
            };
            channels = new DefaultNotificationChannels(new AndroidChannelBackend(this), logger);
            channels.ensureChannels();
            AlarmActions actions = new AlarmActions() {
                @Override
                public void snooze(AlarmType t) {
                    snoozes.add(t);
                    monitorExecutor.execute(() -> runtime.apply(List.of(new AlarmCommand.Stop(t))));
                }

                @Override
                public void setAlarmsEnabled(boolean on) {
                    // not used by :alarm
                }

                @Override
                public void setAlarmTypeEnabled(AlarmType t, boolean on) {
                    // not used by :alarm
                }
            };
            runtime = new AlarmRuntime(this, () -> actions, store, memory, channels, clock, logger, monitorExecutor,
                    new AlarmSoundResolver() {
                        @Override
                        public PlayableSound playable(AlarmType t) {
                            return PlayableSound.CLASSIC;   // device tests of the alarm screen use the classic tone
                        }

                        @Override
                        public void reportUnplayable(AlarmType t) {
                            // nothing to record in these tests
                        }
                    });
        }
        return runtime;
    }

    /** IF-15 with a settable offset (dark-window tests). */
    static final class ShiftableClock implements Clock {
        volatile Duration offset = Duration.ZERO;
        volatile ZoneId zone = ZoneId.systemDefault();

        @Override
        public Instant now() {
            return Instant.now().plus(offset);
        }

        @Override
        public long elapsedRealtimeMs() {
            return android.os.SystemClock.elapsedRealtime();
        }

        @Override
        public ZoneId zone() {
            return zone;
        }
    }

    /** In-memory IF-14. */
    static final class MemoryStore implements AlarmStateStore {
        volatile AlarmState state = AlarmState.empty();

        @Override
        public AlarmState get() {
            return state;
        }

        @Override
        public void put(AlarmState s) {
            state = s;
        }

        @Override
        public void clear() {
            state = AlarmState.empty();
        }
    }
}
