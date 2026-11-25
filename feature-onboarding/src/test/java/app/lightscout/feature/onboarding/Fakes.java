package app.lightscout.feature.onboarding;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.ReadinessItem;
import app.lightscout.platform.readiness.ReadinessProbe;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.SaveResult;
import app.lightscout.usecases.StartDestination;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.StoredConnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Hand-written fakes for the onboarding JVM tests (no Mockito — CP-00 §2). */
public final class Fakes {

    /** Runs tasks immediately on the calling thread. */
    public static final Executor DIRECT = Runnable::run;

    private Fakes() {
    }

    /** Executor that queues tasks until {@link #runAll()}. */
    public static final class QueueExecutor implements Executor {

        private final List<Runnable> queue = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        /** Runs every queued task (including tasks queued while running). */
        public void runAll() {
            while (!queue.isEmpty()) {
                queue.remove(0).run();
            }
        }

        /**
         * Number of queued tasks.
         *
         * @return the count
         */
        public int size() {
            return queue.size();
        }
    }

    /** In-memory saved state (stands in for {@code SavedStateHandle}; survives a „process death” by reuse). */
    public static final class MapStore implements StateStore {

        /** Raw values, inspected by the SR-02 tests. */
        public final Map<String, Object> values = new HashMap<>();

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public String getString(String key) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : null;
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : fallback;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        /**
         * Tells whether any stored value contains the text.
         *
         * @param text the text searched for
         * @return {@code true} if found
         */
        public boolean containsText(String text) {
            for (Object v : values.values()) {
                if (v != null && String.valueOf(v).contains(text)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Recording IF-27. */
    public static final class FakeOnboarding implements OnboardingState {

        /** Calls of {@link #acceptDisclaimer()}. */
        public int accepts;
        /** Calls of {@link #completeOnboarding()}. */
        public int completes;
        /** When set, {@link #acceptDisclaimer()} and {@link #completeOnboarding()} throw it. */
        public RuntimeException failure;

        @Override
        public StartDestination resolve() {
            return StartDestination.DISCLAIMER;
        }

        @Override
        public void acceptDisclaimer() {
            accepts++;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void completeOnboarding() {
            completes++;
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Recording IF-24; callbacks are completed by the test. */
    public static final class FakeConnectionService implements ConnectionService {

        /** Call names in order. */
        public final List<String> calls = new ArrayList<>();
        /** URLs passed to verify. */
        public final List<String> verifyUrls = new ArrayList<>();
        /** Copies of the token passed to verify at call time ({@code null} = stored token). */
        public final List<String> verifyTokens = new ArrayList<>();
        /** The arrays passed to verify / save (checked for wiping). */
        public final List<char[]> passedArrays = new ArrayList<>();
        /** URLs passed to save. */
        public final List<String> saveUrls = new ArrayList<>();
        /** Copies of the token passed to save at call time. */
        public final List<String> saveTokens = new ArrayList<>();
        /** Stored connection returned by loadStored ({@code null} = none). */
        public StoredConnection stored;
        /** Token returned by revealStoredToken. */
        public String storedToken = "stored-token";

        private Callback<List<CheckResult>> verifyCallback;
        private Callback<SaveResult> saveCallback;
        private Callback<char[]> revealCallback;

        @Override
        public void loadStored(Callback<StoredConnection> cb) {
            calls.add("loadStored");
            cb.onResult(Result.ok(stored));
        }

        @Override
        public void verify(String url, char[] tokenOrNull, Callback<List<CheckResult>> cb) {
            calls.add("verify");
            verifyUrls.add(url);
            verifyTokens.add(tokenOrNull == null ? null : new String(tokenOrNull));
            if (tokenOrNull != null) {
                passedArrays.add(tokenOrNull);
            }
            verifyCallback = cb;
        }

        @Override
        public void revealStoredToken(Callback<char[]> cb) {
            calls.add("reveal");
            revealCallback = cb;
        }

        @Override
        public void save(String url, char[] tokenOrNull, Callback<SaveResult> cb) {
            calls.add("save");
            saveUrls.add(url);
            saveTokens.add(tokenOrNull == null ? null : new String(tokenOrNull));
            if (tokenOrNull != null) {
                passedArrays.add(tokenOrNull);
            }
            saveCallback = cb;
        }

        /**
         * Completes the pending verify.
         *
         * @param r the result
         */
        public void completeVerify(Result<List<CheckResult>> r) {
            Callback<List<CheckResult>> cb = verifyCallback;
            verifyCallback = null;
            cb.onResult(r);
        }

        /**
         * Completes the pending save.
         *
         * @param r the result
         */
        public void completeSave(Result<SaveResult> r) {
            Callback<SaveResult> cb = saveCallback;
            saveCallback = null;
            cb.onResult(r);
        }

        /** Completes the pending reveal with {@link #storedToken}. */
        public void completeReveal() {
            Callback<char[]> cb = revealCallback;
            revealCallback = null;
            cb.onResult(Result.ok(storedToken.toCharArray()));
        }

        /**
         * Number of calls with a name.
         *
         * @param name the call name
         * @return the count
         */
        public int count(String name) {
            int n = 0;
            for (String c : calls) {
                if (c.equals(name)) {
                    n++;
                }
            }
            return n;
        }
    }

    /** SDK-level / capability fake of the CMP-27 probe (CP-00 §10 CR-04): every check passes by default. */
    public static final class FakeProbe implements ReadinessProbe {

        /** API level. */
        public int sdk = 36;
        /** Item 1. */
        public boolean notifications = true;
        /** Item 2. */
        public boolean dnd = true;
        /** Item 3. */
        public boolean fullScreen = true;
        /** Item 4. */
        public boolean battery = true;
        /** Item 5. */
        public boolean exactAlarms = true;
        /** Item 7. */
        public boolean channelReady = true;

        @Override
        public int sdkInt() {
            return sdk;
        }

        @Override
        public boolean notificationsEnabled() {
            return notifications;
        }

        @Override
        public boolean notificationPolicyAccessGranted() {
            return dnd;
        }

        @Override
        public boolean canUseFullScreenIntent() {
            if (sdk < 34) {
                throw new AssertionError("band-gated query on API " + sdk);
            }
            return fullScreen;
        }

        @Override
        public boolean ignoringBatteryOptimizations() {
            return battery;
        }

        @Override
        public boolean canScheduleExactAlarms() {
            if (sdk < 31) {
                throw new AssertionError("band-gated query on API " + sdk);
            }
            return exactAlarms;
        }

        @Override
        public ChannelState alarmChannel() {
            return channelReady ? new ChannelState("alarms", true, true, 4, true)
                    : new ChannelState("alarms", true, true, 4, false);
        }
    }

    /** Recording CMP-26. */
    public static final class FakeChannels implements NotificationChannels {

        /** Calls of {@link #ensureChannels()}. */
        public int ensureChannels;
        /** Calls of {@link #ensureAlarmChannelBypass()}. */
        public int bypass;
        /** Runs inside {@link #ensureAlarmChannelBypass()} (e.g. the re-created channel now bypasses DND). */
        public Runnable onBypass;
        /** When set, {@link #ensureAlarmChannelBypass()} throws it. */
        public RuntimeException failure;

        @Override
        public void ensureChannels() {
            ensureChannels++;
        }

        @Override
        public ChannelState state(ChannelId id) {
            return new ChannelState("alarms", true, true, 4, true);
        }

        @Override
        public String channelId(ChannelId id) {
            return "alarms";
        }

        @Override
        public void ensureAlarmChannelBypass() {
            bypass++;
            if (failure != null) {
                throw failure;
            }
            if (onBypass != null) {
                onBypass.run();
            }
        }
    }

    /** CMP-27 wrapper that throws while {@link #fail} is set. */
    public static final class FlakyChecker implements ReadinessChecker {

        private final ReadinessChecker delegate;
        /** Throw on the next checks. */
        public boolean fail;

        /**
         * Wraps a checker.
         *
         * @param delegate the real checker
         */
        public FlakyChecker(ReadinessChecker delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ReadinessItem> check() {
            if (fail) {
                throw new IllegalStateException("probe failure");
            }
            return delegate.check();
        }

        @Override
        public int missingRequiredCount() {
            return delegate.missingRequiredCount();
        }
    }

    /** IF-21 with a settable {@code ringing} alarm (F7 guard, Q-52). */
    public static final class FakeStatusRepository implements StatusRepository {

        /** The ringing alarm, or {@code null}. */
        public AlarmType ringing;

        @Override
        public StatusSnapshot current() {
            ConsumableStatus site = new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND,
                    null, null, null, null);
            ConsumableStatus sensor = new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND,
                    null, null, null, null);
            return new StatusSnapshot(null, null, null, false, new DataLossStatus(DataLossKind.NONE, null, null),
                    FetchOutcome.OK, null, null, null, true, false, false, true, true, true, 55, 70, 250, 20,
                    ringing, null, site, sensor, 0, Banner.NONE, Instant.EPOCH);
        }

        @Override
        public void addListener(StatusListener l) {
            // not used by S-07
        }

        @Override
        public void removeListener(StatusListener l) {
            // not used by S-07
        }

        @Override
        public void refresh() {
            // not used by S-07
        }

        @Override
        public void retry() {
            // not used by S-07
        }
    }

    /**
     * Four passed rows (all ✓).
     *
     * @param version server version for the VERSION row
     * @return the rows
     */
    public static List<CheckResult> allPassed(String version) {
        return List.of(new CheckResult(CheckRow.REACHABLE, true, null, null),
                new CheckResult(CheckRow.VERSION, true, null, version),
                new CheckResult(CheckRow.READ, true, null, null),
                new CheckResult(CheckRow.WRITE, true, null, null));
    }
}
