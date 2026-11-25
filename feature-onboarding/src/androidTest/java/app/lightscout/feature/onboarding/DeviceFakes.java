package app.lightscout.feature.onboarding;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

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
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.platform.intents.SettingsTargets;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Fakes for the onboarding instrumented tests (no Mockito — CP-00 §2; never any network — CP-00 §8). */
final class DeviceFakes {

    private DeviceFakes() {
    }

    /** Recording IF-27. */
    static final class FakeOnboarding implements OnboardingState {

        final AtomicInteger accepts = new AtomicInteger();
        final AtomicInteger completes = new AtomicInteger();

        @Override
        public StartDestination resolve() {
            return StartDestination.DISCLAIMER;
        }

        @Override
        public void acceptDisclaimer() {
            accepts.incrementAndGet();
        }

        @Override
        public void completeOnboarding() {
            completes.incrementAndGet();
        }
    }

    /** Scripted IF-24: answers immediately with {@link #verifyResult}, or holds the call when it is {@code null}. */
    static final class ScriptedConnectionService implements ConnectionService {

        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> saveWithStoredToken = new CopyOnWriteArrayList<>();
        volatile StoredConnection stored;
        volatile Result<List<CheckResult>> verifyResult = Result.ok(allPassed());
        volatile Result<SaveResult> saveResult = Result.ok(new SaveResult(false));

        @Override
        public void loadStored(Callback<StoredConnection> cb) {
            calls.add("loadStored");
            cb.onResult(Result.ok(stored));
        }

        @Override
        public void verify(String url, char[] tokenOrNull, Callback<List<CheckResult>> cb) {
            calls.add("verify");
            Result<List<CheckResult>> r = verifyResult;
            if (r != null) {
                cb.onResult(r);
            }
        }

        @Override
        public void revealStoredToken(Callback<char[]> cb) {
            calls.add("reveal");
            cb.onResult(Result.ok("stored-token".toCharArray()));
        }

        @Override
        public void save(String url, char[] tokenOrNull, Callback<SaveResult> cb) {
            calls.add("save");
            saveWithStoredToken.add(tokenOrNull == null);
            cb.onResult(saveResult);
        }

        int count(String name) {
            int n = 0;
            for (String c : calls) {
                if (c.equals(name)) {
                    n++;
                }
            }
            return n;
        }

        static List<CheckResult> allPassed() {
            return List.of(new CheckResult(CheckRow.REACHABLE, true, null, null),
                    new CheckResult(CheckRow.VERSION, true, null, "15.0.6"),
                    new CheckResult(CheckRow.READ, true, null, null),
                    new CheckResult(CheckRow.WRITE, true, null, null));
        }
    }

    /** IF-21 with a switchable {@code online} flag. */
    static final class FakeStatusRepository implements StatusRepository {

        final List<StatusListener> listeners = new CopyOnWriteArrayList<>();
        volatile boolean online = true;
        volatile AlarmType ringing;

        @Override
        public StatusSnapshot current() {
            return snapshot(online, ringing);
        }

        @Override
        public void addListener(StatusListener l) {
            listeners.add(l);
        }

        @Override
        public void removeListener(StatusListener l) {
            listeners.remove(l);
        }

        @Override
        public void refresh() {
            // not used
        }

        @Override
        public void retry() {
            // not used
        }

        void setOnline(boolean value) {
            online = value;
            for (StatusListener l : listeners) {
                l.onStatus(snapshot(value, ringing));
            }
        }

        static StatusSnapshot snapshot(boolean online, AlarmType ringing) {
            ConsumableStatus site = new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND,
                    null, null, null, null);
            ConsumableStatus sensor = new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND,
                    null, null, null, null);
            return new StatusSnapshot(null, null, null, false, new DataLossStatus(DataLossKind.NONE, null, null),
                    FetchOutcome.OK, null, null, null, online, false, false, true, true, true, 55, 70, 250, 20,
                    ringing, null, site, sensor, 0, Banner.NONE, Instant.EPOCH);
        }
    }

    /** SDK-level / capability fake of the CMP-27 probe (CP-00 §10 CR-04); all checks pass by default. */
    static final class FakeProbe implements ReadinessProbe {

        volatile int sdk = 33;
        volatile boolean notifications = true;
        volatile boolean dnd = true;
        volatile boolean fullScreen = true;
        volatile boolean battery = true;
        volatile boolean exactAlarms = true;
        volatile boolean channelReady = true;

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
            return fullScreen;
        }

        @Override
        public boolean ignoringBatteryOptimizations() {
            return battery;
        }

        @Override
        public boolean canScheduleExactAlarms() {
            return exactAlarms;
        }

        @Override
        public ChannelState alarmChannel() {
            return new ChannelState("alarms_v1", true, true, 4, channelReady);
        }
    }

    /** Recording CMP-26; the current CH-01 id is {@code alarms_v1} (a re-created channel, F7). */
    static final class FakeChannels implements NotificationChannels {

        final AtomicInteger ensureChannels = new AtomicInteger();
        final AtomicInteger bypass = new AtomicInteger();

        @Override
        public void ensureChannels() {
            ensureChannels.incrementAndGet();
        }

        @Override
        public ChannelState state(ChannelId id) {
            return new ChannelState(channelId(id), true, true, 4, true);
        }

        @Override
        public String channelId(ChannelId id) {
            return id == ChannelId.ALARMS ? "alarms_v1" : id.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public void ensureAlarmChannelBypass() {
            bypass.incrementAndGet();
        }
    }

    /**
     * CMP-28 intents fake: every page is an explicit intent to {@link RecordingSettingsActivity} (so the test sees
     * what would have opened), or — while {@link #failing} is set — an intent nothing resolves
     * ({@code ActivityNotFoundException}). {@link #resolvable} limits the IF-56 pages that resolve.
     */
    static final class FakeIntents implements SystemSettingsIntents {

        static final String NO_SUCH_ACTION = "app.lightscout.test.NO_SUCH_SETTINGS_PAGE";

        private final Context context;
        private final NotificationChannels channels;
        volatile boolean failing;
        volatile Set<OemSettingId> resolvable = EnumSet.allOf(OemSettingId.class);

        FakeIntents(Context context, NotificationChannels channels) {
            this.context = context.getApplicationContext();
            this.channels = channels;
        }

        private Intent page(String action) {
            if (failing) {
                return new Intent(NO_SUCH_ACTION);
            }
            return new Intent(context, RecordingSettingsActivity.class).setAction(action);
        }

        @Override
        public Optional<Intent> forItem(ReadinessItemId id) {
            if (id == ReadinessItemId.MANUFACTURER) {
                return Optional.empty();
            }
            Intent intent = page("item." + id.name());
            if (id == ReadinessItemId.ALARM_CHANNEL) {
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, channels.channelId(ChannelId.ALARMS));
            }
            return Optional.of(intent);
        }

        @Override
        public Intent appNotificationSettings() {
            return page("appNotificationSettings");
        }

        @Override
        public Intent appDetails() {
            return page("appDetails");
        }

        @Override
        public Optional<Intent> oem(OemSettingId id) {
            return resolvable.contains(id) ? Optional.of(page("oem." + id.name())) : Optional.empty();
        }

        @Override
        public Intent dontKillMyApp(Manufacturer m) {
            return new Intent(context, RecordingSettingsActivity.class).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(SettingsTargets.dontKillMyAppUrl(m)));
        }
    }
}
