package app.lightscout.storage;

import android.content.SharedPreferences;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * IF-60 {@link AppFlagsStore} split across two files (ENT-11, CP-04 §8.8):
 * <ul>
 *   <li>{@code monitoringConfigured}, {@code handledBootCount} → ST-05 {@code boot} preferences in
 *       <b>device-protected</b> storage, usable before the first unlock (ADR-037, AR-26). The file holds exactly
 *       these two keys plus {@code schema} (CP-00A R-66);</li>
 *   <li>{@code disclaimerAccepted}, {@code onboardingCompleted} → ST-04 {@code runtime} preferences in
 *       <b>credential-encrypted</b> storage, opened lazily through a supplier.</li>
 * </ul>
 * While the user is locked the CE methods never touch CE storage (CP-00 §6, CP-00A R-39): getters return
 * {@code false}, setters do nothing, both log {@code CE_ACCESS_LOCKED} and never throw.
 */
final class PrefsAppFlagsStore implements AppFlagsStore {

    /** ST-05 key (device-protected). */
    static final String MONITORING_CONFIGURED = "monitoringConfigured";
    /** ST-05 key (device-protected). */
    static final String HANDLED_BOOT_COUNT = "handledBootCount";
    /** ST-04 key (credential-encrypted). */
    static final String DISCLAIMER_ACCEPTED = "disclaimerAccepted";
    /** ST-04 key (credential-encrypted). */
    static final String ONBOARDING_COMPLETED = "onboardingCompleted";

    private static final String OP_DE_READ = "boot.read";
    private static final String OP_DE_WRITE = "boot.write";
    private static final String OP_CE_READ = "flags.read";
    private static final String OP_CE_WRITE = "flags.write";

    private final SharedPreferences bootPrefs;
    private final Supplier<SharedPreferences> runtimePrefs;
    private final BooleanSupplier userUnlocked;
    private final Logger logger;
    private final StoreGuard guard;

    /**
     * @param bootPrefs    ST-05 {@code boot} preferences from the device-protected storage context
     * @param runtimePrefs supplier of the CE {@code runtime} preferences; called only while unlocked
     * @param userUnlocked {@code UserManager.isUserUnlocked()}
     * @param logger       IF-66 event logger
     * @param guard        failure logging and mapping (R-20)
     */
    PrefsAppFlagsStore(SharedPreferences bootPrefs, Supplier<SharedPreferences> runtimePrefs,
                       BooleanSupplier userUnlocked, Logger logger, StoreGuard guard) {
        this.bootPrefs = bootPrefs;
        this.runtimePrefs = runtimePrefs;
        this.userUnlocked = userUnlocked;
        this.logger = logger;
        this.guard = guard;
    }

    @Override
    public boolean disclaimerAccepted() {
        return readCeFlag(DISCLAIMER_ACCEPTED);
    }

    @Override
    public void setDisclaimerAccepted() {
        writeCeFlag(DISCLAIMER_ACCEPTED, true);
    }

    @Override
    public boolean onboardingCompleted() {
        return readCeFlag(ONBOARDING_COMPLETED);
    }

    @Override
    public void setOnboardingCompleted(boolean v) {
        writeCeFlag(ONBOARDING_COMPLETED, v);
    }

    @Override
    public boolean monitoringConfigured() {
        try {
            return bootPrefs.getBoolean(MONITORING_CONFIGURED, false);
        } catch (RuntimeException e) {
            throw guard.fail(OP_DE_READ, e);
        }
    }

    @Override
    public void setMonitoringConfigured(boolean v) {
        boolean written;
        try {
            SharedPreferences.Editor editor = bootPrefs.edit().putBoolean(MONITORING_CONFIGURED, v);
            PrefsSchema.stamp(bootPrefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_DE_WRITE, e);
        }
        if (!written) {
            throw guard.fail(OP_DE_WRITE, null);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return the last handled boot count, {@code 0} when none was stored
     */
    @Override
    public long handledBootCount() {
        try {
            return bootPrefs.getLong(HANDLED_BOOT_COUNT, 0L);
        } catch (RuntimeException e) {
            throw guard.fail(OP_DE_READ, e);
        }
    }

    @Override
    public void setHandledBootCount(long c) {
        boolean written;
        try {
            SharedPreferences.Editor editor = bootPrefs.edit().putLong(HANDLED_BOOT_COUNT, c);
            PrefsSchema.stamp(bootPrefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_DE_WRITE, e);
        }
        if (!written) {
            throw guard.fail(OP_DE_WRITE, null);
        }
    }

    private boolean readCeFlag(String key) {
        if (!userUnlocked.getAsBoolean()) {
            logger.event(EventCode.CE_ACCESS_LOCKED);
            return false;
        }
        try {
            return runtimePrefs.get().getBoolean(key, false);
        } catch (RuntimeException e) {
            throw guard.fail(OP_CE_READ, e);
        }
    }

    private void writeCeFlag(String key, boolean value) {
        if (!userUnlocked.getAsBoolean()) {
            logger.event(EventCode.CE_ACCESS_LOCKED);
            return;
        }
        boolean written;
        try {
            SharedPreferences prefs = runtimePrefs.get();
            SharedPreferences.Editor editor = prefs.edit().putBoolean(key, value);
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_CE_WRITE, e);
        }
        if (!written) {
            throw guard.fail(OP_CE_WRITE, null);
        }
    }
}
