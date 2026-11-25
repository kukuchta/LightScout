package app.lightscout.storage;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * Shared fixtures of the {@code :storage} instrumented tests (TST-05). Synthetic data only (SR-40); test tokens
 * are obviously fake strings.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB in the batched pre-M1 run (CP-00 §10 CR-04, D-048). Every run
 * ends with {@code adb uninstall} of the app and test APKs (SR-34).
 */
final class StorageTestSupport {

    static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    private StorageTestSupport() {
    }

    static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    static Instant t(long minutes) {
        return T0.plusSeconds(minutes * 60);
    }

    /** Deletes every file of this module so that a test starts from a fresh install. */
    static void wipe(Context context) {
        context.deleteDatabase(CacheSchema.DB_NAME);
        context.deleteSharedPreferences(StorageFactory.SETTINGS_PREFS);
        context.deleteSharedPreferences(StorageFactory.CONNECTION_PREFS);
        context.deleteSharedPreferences(StorageFactory.RUNTIME_PREFS);
        context.createDeviceProtectedStorageContext().deleteSharedPreferences(StorageFactory.BOOT_PREFS);
    }

    /** A {@link CacheDatabase} wired like {@link StorageFactory} does it. */
    static CacheDatabase cacheDatabase(Context context) {
        return new CacheDatabase(context, new CacheGeneration(
                context.getSharedPreferences(StorageFactory.RUNTIME_PREFS, Context.MODE_PRIVATE)));
    }

    static Reading reading(String id, long minutes, int sgv) {
        return new Reading(id, t(minutes), sgv, Trend.FLAT, "fake-cgm");
    }

    static Entry entry(String id, EntryOrigin origin, EntryType type, long minutes) {
        return new Entry(id, origin, type, t(minutes), null, null, null, null, null, null, "fake-uploader", null,
                null, false, null);
    }

    /** Settable IF-15 clock. */
    static final class TestClock implements Clock {
        Instant now = T0;

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return 0L;
        }

        @Override
        public ZoneId zone() {
            return ZoneOffset.UTC;
        }
    }

    /** IF-66 fake that records event lines. */
    static final class TestLogger implements Logger {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void event(EventCode code) {
            lines.add(code.name());
        }

        @Override
        public void event(EventCode code, String detail) {
            lines.add(code.name() + ":" + detail);
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
            lines.add("CRASH");
        }

        @Override
        public void debug(String tag, String message) {
            lines.add("DEBUG");
        }
    }
}
