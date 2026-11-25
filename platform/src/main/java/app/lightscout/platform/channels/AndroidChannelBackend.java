package app.lightscout.platform.channels;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChannelBackend} on {@code NotificationManager}. Touches no storage, so it is safe before the first unlock
 * (CP-00 §6). Never calls {@code setInterruptionFilter}, {@code setNotificationPolicy} or zen-rule APIs (SR-26).
 */
public final class AndroidChannelBackend implements ChannelBackend {

    private final Context context;
    private final NotificationManager manager;

    /**
     * Creates the backend.
     *
     * @param context any context (the application context is kept; names are read from its resources)
     */
    public AndroidChannelBackend(Context context) {
        this.context = context.getApplicationContext();
        this.manager = this.context.getSystemService(NotificationManager.class);
    }

    @Override
    public List<ExistingChannel> list() {
        List<ExistingChannel> result = new ArrayList<>();
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            result.add(new ExistingChannel(channel.getId(), channel.getImportance(), channel.canBypassDnd()));
        }
        return result;
    }

    @Override
    public void create(String id, ChannelSpec spec) {
        NotificationChannel channel =
                new NotificationChannel(id, context.getString(spec.nameRes()), spec.importance());
        channel.setBypassDnd(spec.bypassDnd());
        channel.setLockscreenVisibility(spec.lockscreenVisibility());
        if (spec.silent()) {
            // CH-01: sound and vibration come from AlarmPlayer (CP-05 §12); CH-02: DEFAULT importance but never
            // audible (DCR-06) — no channel sound, no vibration.
            channel.setSound(null, null);
            channel.enableVibration(false);
        }
        manager.createNotificationChannel(channel);
    }

    @Override
    public void delete(String id) {
        manager.deleteNotificationChannel(id);
    }

    @Override
    public boolean notificationPolicyAccessGranted() {
        return manager.isNotificationPolicyAccessGranted();
    }
}
