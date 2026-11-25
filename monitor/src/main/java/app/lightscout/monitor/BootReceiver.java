package app.lightscout.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

import app.lightscout.domain.port.EventCode;

/**
 * CMP-53 {@code BootReceiver} (ADR-046, WF-04 + F5): direct-boot aware, {@code exported="false"} (VR-20),
 * allow-list {@code LOCKED_BOOT_COMPLETED}, {@code BOOT_COMPLETED}, {@code MY_PACKAGE_REPLACED} — no
 * {@code USER_UNLOCKED} (F5). Extras are ignored (SR-21).
 *
 * <ul>
 *   <li>{@code BOOT_COMPLETED} records {@code Settings.Global.BOOT_COUNT} as handled in ST-05 before the start (F5,
 *       IR-76); {@code LOCKED_BOOT_COMPLETED} writes nothing.</li>
 *   <li>{@code LOCKED_BOOT_COMPLETED}: only while the user is still locked (IR-68); reads only ST-05 {@code monitoringConfigured} → posts „LightScout is paused”
 *       (CH-02) + {@code BOOT_PAUSED}. Touches only DE flags, the channel registry, {@code NotificationManager} and
 *       the no-op logger (CP-00 §6) — never {@link MonitorRuntimeProvider#monitorRuntime()}.</li>
 *   <li>{@code BOOT_COMPLETED}: if configured,
 *       starts {@link MonitorService} (tick {@code BOOT}; S-10 replaces the paused notification, id 1).</li>
 *   <li>{@code MY_PACKAGE_REPLACED}: same start (tick {@code PACKAGE_REPLACED}).</li>
 *   <li>Start refused (VR-09) → exact alarm in 5 s ({@code …action.BOOT_RETRY}) starts it (WF-04).</li>
 * </ul>
 * Handling {@code BOOT_COMPLETED} / {@code LOCKED_BOOT_COMPLETED} marks this process as boot-handled (🏛️ Q-67), so
 * its own app-start check is skipped. Work runs after {@code goAsync()} on the receiver executor (DE prefs off the main thread).
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!BootDecision.isBootAction(action)) {
            return;
        }
        if (!BootDecision.MY_PACKAGE_REPLACED.equals(action)) {
            BootNotHandledGuard.PROCESS.markBootHandled();   // Q-67: before the service start
        }
        Context app = context.getApplicationContext();
        MonitorBootDependencies boot = MonitorComponents.provider(app).monitorBootDependencies();
        PendingResult pending = goAsync();
        boot.receiverExecutor().execute(() -> {
            try {
                handle(app, boot, action);
            } finally {
                if (pending != null) {
                    pending.finish();   // null when invoked outside a broadcast (tests)
                }
            }
        });
    }

    private static boolean isUserUnlocked(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        return um != null && um.isUserUnlocked();
    }

    private static void handle(Context context, MonitorBootDependencies boot, String action) {
        try {
            if (BootDecision.recordsBootCount(action)) {
                long count = new SettingsBootCountSource(context).bootCount();
                if (count >= 0) {
                    boot.flags().setHandledBootCount(count);
                }
            }
            switch (BootDecision.forBoot(action, boot.flags().monitoringConfigured())) {
                case POST_PAUSED -> {
                    if (BootDecision.postsPaused(BootDecision.Action.POST_PAUSED, isUserUnlocked(context))) {
                        PausedNotification.post(context, boot.channels());
                        boot.logger().event(EventCode.BOOT_PAUSED);
                    }
                }
                case START_BOOT -> MonitorComponents.launcher(context, boot).launch(StartOrigin.BOOT);
                case START_PACKAGE_REPLACED ->
                        MonitorComponents.launcher(context, boot).launch(StartOrigin.PACKAGE_REPLACED);
                default -> {
                    // not configured: nothing to resume
                }
            }
        } catch (RuntimeException e) {
            boot.logger().crash(e.getClass().getSimpleName(), "BootReceiver.handle");
        }
    }
}
