package app.lightscout.platform.channels;

import android.app.NotificationManager;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.NotificationChannels;

/**
 * CMP-26 {@link NotificationChannels} (CP-05 §8.1, CP-00A R-11, R-74).
 *
 * <p><b>CH-01 versioning (F7, R-74).</b> The current alarm channel id is derived from the existing channels: the
 * highest {@code alarms_v{n}}, where plain {@code alarms} is version 0. Nothing is persisted. F7 creates
 * {@code alarms_v{n+1}} first and then deletes the old id, so a process death in between leaves two alarm channels;
 * {@link #ensureChannels()} then deletes the older ones.
 *
 * <p><b>F7 guard (Q-52).</b> Only v0 is ever re-created, and only into v1: a channel created by F7 already asked
 * for DND bypass while PERM-10 was granted, so if it still reports {@code canBypassDnd() == false} the user (or the
 * OEM) turned it off and the app stops (item 7 „needs attention”, VR-27 fallback). A v0 channel created while PERM-10
 * is granted asks for bypass at creation ({@link ChannelSpec}).
 *
 * <p><b>CH-02 re-creation (DCR-06 / D-061, BUG-16).</b> CH-02 moved from {@code monitoring} (LOW) to
 * {@code monitoring_v1} (DEFAULT, silent) because a channel's importance cannot be changed after creation.
 * {@link #ensureChannels()} creates {@code monitoring_v1} first and then deletes {@code monitoring} if present (same
 * create-then-delete order as F7, so a process death in between leaves both and the next call finishes the job).
 * It runs at app start ({@code LightScoutApplication}), before the paused notice ({@code BootReceiver}, also before the
 * first unlock — channel APIs touch no CE storage) and before every {@code startForeground}. The system refuses to
 * delete a channel that a foreground-service notification is using ({@code SecurityException}); that delete is then
 * skipped and retried on the next call. User changes on the old channel are not carried over (as for F7). The
 * migration is unconditional and logs nothing: unlike F7 it is not a Q-52 event and never touches CH-01.
 *
 * <p>Thread-safe (methods are {@code synchronized}); safe before the first unlock.
 */
public final class DefaultNotificationChannels implements NotificationChannels {

    private static final Pattern ALARMS_VERSIONED = Pattern.compile("alarms_v([0-9]{1,9})");

    private final ChannelBackend backend;
    private final Logger logger;

    /**
     * Creates the component.
     *
     * @param backend system seam ({@link AndroidChannelBackend} in the app)
     * @param logger  IF-66 logger ({@code CHANNEL_RECREATED})
     */
    public DefaultNotificationChannels(ChannelBackend backend, Logger logger) {
        this.backend = backend;
        this.logger = logger;
    }

    @Override
    public synchronized void ensureChannels() {
        List<ExistingChannel> existing = backend.list();
        for (ChannelId id : ChannelId.values()) {
            if (id == ChannelId.ALARMS) {
                ensureAlarmChannel(existing);
            } else if (id == ChannelId.MONITORING) {
                ensureMonitoringChannel(existing);
            } else {
                ChannelSpec spec = ChannelSpec.of(id);
                if (find(existing, spec.baseId()) == null) {
                    backend.create(spec.baseId(), spec);
                }
            }
        }
    }

    @Override
    public synchronized ChannelState state(ChannelId id) {
        List<ExistingChannel> existing = backend.list();
        String currentId = currentId(id, existing);
        ExistingChannel channel = find(existing, currentId);
        if (channel == null) {
            return new ChannelState(currentId, false, false, NotificationManager.IMPORTANCE_NONE, false);
        }
        return new ChannelState(currentId, true, channel.importance() != NotificationManager.IMPORTANCE_NONE,
                channel.importance(), channel.bypassDnd());
    }

    @Override
    public synchronized String channelId(ChannelId id) {
        return currentId(id, backend.list());
    }

    @Override
    public synchronized void ensureAlarmChannelBypass() {
        ensureChannels();
        if (!backend.notificationPolicyAccessGranted()) {
            return;
        }
        List<ExistingChannel> existing = backend.list();
        if (highestAlarmVersion(existing) != 0) {
            return; // Q-52: v1+ was created by F7 with bypass requested; never re-create it
        }
        ExistingChannel current = find(existing, ChannelSpec.ALARMS_BASE_ID);
        if (current == null || current.bypassDnd()) {
            return;
        }
        backend.create(alarmId(1), ChannelSpec.of(ChannelId.ALARMS));
        backend.delete(current.id());
        logger.event(EventCode.CHANNEL_RECREATED, "1");
    }

    private void ensureAlarmChannel(List<ExistingChannel> existing) {
        int version = highestAlarmVersion(existing);
        if (version < 0) {
            backend.create(ChannelSpec.ALARMS_BASE_ID, ChannelSpec.of(ChannelId.ALARMS));
            return;
        }
        for (ExistingChannel channel : existing) {
            int v = alarmVersion(channel.id());
            if (v >= 0 && v < version) {
                deleteIfAllowed(channel.id()); // leftover of an interrupted F7 re-creation
            }
        }
    }

    /** DCR-06: create {@code monitoring_v1}, then delete the LOW {@code monitoring} channel of older installs. */
    private void ensureMonitoringChannel(List<ExistingChannel> existing) {
        if (find(existing, ChannelSpec.MONITORING_ID) == null) {
            backend.create(ChannelSpec.MONITORING_ID, ChannelSpec.of(ChannelId.MONITORING));
        }
        if (find(existing, ChannelSpec.MONITORING_LEGACY_ID) != null) {
            deleteIfAllowed(ChannelSpec.MONITORING_LEGACY_ID);
        }
    }

    /**
     * Deletes a leftover channel (old CH-01 version, legacy CH-02). The system refuses to delete a channel that a
     * foreground-service notification uses ({@code SecurityException}); only that is caught, so one refused delete
     * neither throws out of {@link #ensureChannels()} nor skips the other channels. The delete is retried on the
     * next call.
     */
    private void deleteIfAllowed(String id) {
        try {
            backend.delete(id);
        } catch (SecurityException e) {
            // still used by a foreground-service notification — retried on the next ensureChannels()
        }
    }

    private static String currentId(ChannelId id, List<ExistingChannel> existing) {
        if (id == ChannelId.MONITORING) {
            // Before ensureChannels() has created monitoring_v1, an older install's channel is still the one to post
            // on (posting on a missing channel drops the notification and fails startForeground).
            if (find(existing, ChannelSpec.MONITORING_ID) == null
                    && find(existing, ChannelSpec.MONITORING_LEGACY_ID) != null) {
                return ChannelSpec.MONITORING_LEGACY_ID;
            }
            return ChannelSpec.MONITORING_ID;
        }
        if (id != ChannelId.ALARMS) {
            return ChannelSpec.of(id).baseId();
        }
        return alarmId(Math.max(0, highestAlarmVersion(existing)));
    }

    private static int highestAlarmVersion(List<ExistingChannel> existing) {
        int highest = -1;
        for (ExistingChannel channel : existing) {
            highest = Math.max(highest, alarmVersion(channel.id()));
        }
        return highest;
    }

    /**
     * Returns the CH-01 version of a channel id.
     *
     * @param id system channel id
     * @return 0 for {@code alarms}, n for {@code alarms_v{n}}, -1 for any other id
     */
    static int alarmVersion(String id) {
        if (ChannelSpec.ALARMS_BASE_ID.equals(id)) {
            return 0;
        }
        Matcher matcher = ALARMS_VERSIONED.matcher(id);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Returns the CH-01 id of a version.
     *
     * @param version 0 or more
     * @return {@code alarms} for 0, else {@code alarms_v{n}}
     */
    static String alarmId(int version) {
        return version == 0 ? ChannelSpec.ALARMS_BASE_ID : ChannelSpec.ALARMS_BASE_ID + "_v" + version;
    }

    private static ExistingChannel find(List<ExistingChannel> existing, String id) {
        for (ExistingChannel channel : existing) {
            if (channel.id().equals(id)) {
                return channel;
            }
        }
        return null;
    }
}
