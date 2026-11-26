package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.sound.SoundKind;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.NotificationChannels;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Hand-written fakes for the {@code :alarm} JVM tests (no Mockito / Robolectric — CP-00 §2). A shared {@link #ops}
 * log records the order of store writes and volume changes (F8 „commit before the first change”).
 */
final class Fakes {

    static final Instant T0 = Instant.parse("2026-10-15T02:14:00Z");

    final List<String> ops = new ArrayList<>();
    final FakeClock clock = new FakeClock();
    final FakeLogger logger = new FakeLogger();
    final FakeStore store = new FakeStore();
    final FakeVolume volume = new FakeVolume();
    final FakeOutput output = new FakeOutput();
    final FakeWakeLock wakeLock = new FakeWakeLock();
    final ManualScheduler scheduler = new ManualScheduler();
    final FakeChannels channels = new FakeChannels();
    final FakeNotifications notifications = new FakeNotifications();
    final FakeScreenOff screenOff = new FakeScreenOff();
    /** What the user chose per type; empty = every type on the classic tone (the default of 0.1.0). */
    final Map<AlarmType, SoundKind> chosen = new EnumMap<>(AlarmType.class);
    final SoundChoice choice = type -> chosen.getOrDefault(type, SoundKind.CLASSIC);

    static AlarmView view(AlarmType type, Integer sgv) {
        return new AlarmView(type, sgv, Trend.SINGLE_DOWN, T0.minusSeconds(120), null, null, null, 15);
    }

    static AlarmView noData(DataLossCause cause) {
        return new AlarmView(AlarmType.NO_DATA, null, Trend.UNKNOWN, T0.minusSeconds(23 * 60), cause, null,
                T0.minusSeconds(23 * 60), 30);
    }

    /** Settable IF-15 clock. */
    final class FakeClock implements Clock {
        Instant now = T0;
        long elapsed = 1_000_000L;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return elapsed;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("UTC");
        }

        void advanceMs(long ms) {
            elapsed += ms;
            now = now.plusMillis(ms);
        }
    }

    /** Records event codes. */
    final class FakeLogger implements Logger {
        final List<String> events = new ArrayList<>();

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
    }

    /** IF-14 fake; can fail on put. */
    final class FakeStore implements AlarmStateStore {
        AlarmState state = AlarmState.empty();
        boolean failPut;
        int puts;

        @Override
        public AlarmState get() {
            return state;
        }

        @Override
        public void put(AlarmState s) {
            if (failPut) {
                throw new StorageException("put", null);
            }
            puts++;
            state = s;
            ops.add("put(saved=" + s.savedUserAlarmVolume() + ")");
        }

        @Override
        public void clear() {
            state = AlarmState.empty();
        }
    }

    /** STREAM_ALARM fake: max 15, min 1; can refuse changes (VR-12). */
    final class FakeVolume implements RingEngine.Volume {
        int current = 5;
        int min = 1;
        int max = 15;
        boolean refuse;
        final List<Integer> sets = new ArrayList<>();

        @Override
        public int current() {
            return current;
        }

        @Override
        public int min() {
            return min;
        }

        @Override
        public int max() {
            return max;
        }

        @Override
        public boolean set(int index) {
            ops.add("set(" + index + ")");
            if (refuse) {
                return false;
            }
            sets.add(index);
            current = index;
            return true;
        }
    }

    /** MediaPlayer / Vibrator fake. */
    final class FakeOutput implements RingEngine.Output {
        boolean selectedFails;
        boolean primaryFails;
        boolean systemFails;
        SoundStage playing;
        AlarmType playingType;
        boolean vibrating;
        float gain = 1f;
        int plays;
        /** Every {@code play} call that was asked, as {@code STAGE:TYPE}, whether it worked or not. */
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean play(SoundStage stage, AlarmType type) {
            asked.add(stage + ":" + type);
            if (stage == SoundStage.SELECTED_SOUND && selectedFails
                    || stage == SoundStage.PRIMARY_TONE && primaryFails
                    || stage == SoundStage.SYSTEM_TONE && systemFails) {
                return false;
            }
            plays++;
            playing = stage;
            playingType = type;
            return true;
        }

        @Override
        public void setGain(float g) {
            gain = g;
        }

        @Override
        public void stopPlayback() {
            playing = null;
        }

        @Override
        public void startVibration() {
            vibrating = true;
            ops.add("vibrate");
        }

        @Override
        public void stopVibration() {
            vibrating = false;
        }
    }

    /** Partial wake-lock fake. */
    final class FakeWakeLock implements RingEngine.WakeLock {
        boolean held;
        int acquires;
        long lastTimeout;

        @Override
        public void acquire(long timeoutMs) {
            held = true;
            acquires++;
            lastTimeout = timeoutMs;
        }

        @Override
        public void release() {
            held = false;
        }
    }

    /** Manual scheduler driven by {@link #advance(long)}. */
    final class ManualScheduler implements Scheduler {
        long now;
        final List<Task> tasks = new ArrayList<>();

        final class Task implements Cancellable {
            final Runnable r;
            final long at;
            boolean cancelled;

            Task(Runnable r, long at) {
                this.r = r;
                this.at = at;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMs) {
            Task t = new Task(task, now + delayMs);
            tasks.add(t);
            return t;
        }

        void advance(long ms) {
            long end = now + ms;
            while (true) {
                Task next = null;
                for (Task t : tasks) {
                    if (!t.cancelled && t.at <= end && (next == null || t.at < next.at)) {
                        next = t;
                    }
                }
                if (next == null) {
                    break;
                }
                tasks.remove(next);
                now = next.at;
                next.r.run();
            }
            now = end;
            Iterator<Task> it = tasks.iterator();
            while (it.hasNext()) {
                if (it.next().cancelled) {
                    it.remove();
                }
            }
        }

        int pending() {
            int n = 0;
            for (Task t : tasks) {
                if (!t.cancelled) {
                    n++;
                }
            }
            return n;
        }
    }

    /** CMP-26 fake: the CH-01 id can change between posts (F7 / Q-52). */
    final class FakeChannels implements NotificationChannels {
        String alarmId = "alarms";
        int lookups;

        @Override
        public void ensureChannels() {
            // no-op
        }

        @Override
        public ChannelState state(ChannelId id) {
            return new ChannelState(alarmId, true, true, 4, true);
        }

        @Override
        public String channelId(ChannelId id) {
            lookups++;
            return id == ChannelId.ALARMS ? alarmId : id.name().toLowerCase();
        }

        @Override
        public void ensureAlarmChannelBypass() {
            // no-op
        }
    }

    /** Notification seam fake: keeps the posted notification (id 2) and a log. */
    final class FakeNotifications implements AlarmSurfaceController.NotificationPort {
        final List<String> log = new ArrayList<>();
        AlarmView posted;
        String postedChannel;
        boolean postedOnlyAlertOnce;

        @Override
        public void post(String channelId, AlarmView view, boolean onlyAlertOnce) {
            posted = view;
            postedChannel = channelId;
            postedOnlyAlertOnce = onlyAlertOnce;
            log.add("post(" + channelId + "," + view.type() + "," + (onlyAlertOnce ? "once" : "fsi") + ")");
        }

        @Override
        public void cancel() {
            posted = null;
            log.add("cancel");
        }

        int active() {
            return posted == null ? 0 : 1;
        }
    }

    /** F11 screen-off watch fake. */
    final class FakeScreenOff implements AlarmSurfaceController.ScreenOffWatch {
        Runnable callback;
        int registers;

        @Override
        public void register(Runnable onScreenOff) {
            callback = onScreenOff;
            registers++;
        }

        @Override
        public void unregister() {
            callback = null;
        }

        void fire() {
            if (callback != null) {
                callback.run();
            }
        }
    }
}
