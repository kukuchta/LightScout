package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.sound.PlayableSound;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deferred stores (CP-15 §8.1 „built on first use”; no disk while the graph is wired on the main thread). */
public class DeferredStoresTest {

    @Test
    public void storeIsCreatedOnTheFirstCall_once_evenWithConcurrentCallers() throws Exception {
        AtomicInteger created = new AtomicInteger();
        TestDoubles.CallLog log = new TestDoubles.CallLog();
        DeferredStores.Readings readings = new DeferredStores.Readings(() -> {
            created.incrementAndGet();
            return new TestDoubles.FakeReadings(log);
        });
        assertEquals(0, created.get());
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                readings.clear();
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(5000);
        }
        assertEquals(1, created.get());
        assertEquals(8, log.calls.size());
    }

    @Test
    public void settingsListenerAddedBeforeTheStoreExists_firesOnLaterPuts() {
        AtomicInteger created = new AtomicInteger();
        TestDoubles.FakeSettings[] real = new TestDoubles.FakeSettings[1];
        DeferredStores.SettingsPort settings = new DeferredStores.SettingsPort(() -> {
            created.incrementAndGet();
            real[0] = new TestDoubles.FakeSettings(new TestDoubles.CallLog());
            return real[0];
        });
        AtomicInteger fired = new AtomicInteger();
        settings.addListener(fired::incrementAndGet);
        assertEquals("addListener creates no store (it runs on the main thread in DefaultStatusRepository)", 0,
                created.get());
        settings.put(Settings.defaults());
        assertEquals(1, created.get());
        assertEquals(1, fired.get());
        AtomicInteger late = new AtomicInteger();
        settings.addListener(late::incrementAndGet);
        settings.put(Settings.defaults());
        assertEquals(2, fired.get());
        assertEquals(1, late.get());
        assertEquals("one forwarding listener on the real store", 1, real[0].listeners.size());
    }

    @Test
    public void callsAreDelegatedUnchanged() {
        TestDoubles.FakeSettings real = new TestDoubles.FakeSettings(new TestDoubles.CallLog());
        SettingsStore settings = new DeferredStores.SettingsPort(() -> real);
        assertSame(real.value, settings.get());
    }

    @Test
    public void soundResolverIsCreatedOnTheFirstCallOnly_neverWhileWired() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        DeferredStores.SoundResolver resolver = new DeferredStores.SoundResolver(() -> {
            created.incrementAndGet();
            return new AlarmSoundResolver() {
                @Override
                public PlayableSound playable(AlarmType type) {
                    return PlayableSound.bundled("chime");
                }

                @Override
                public void reportUnplayable(AlarmType type) {
                    reports.incrementAndGet();
                }
            };
        });
        assertEquals("wiring the alarm runtime must not open the sound preferences (main thread)", 0, created.get());
        assertEquals(PlayableSound.bundled("chime"), resolver.playable(AlarmType.LOW));
        resolver.reportUnplayable(AlarmType.LOW);
        assertEquals(PlayableSound.bundled("chime"), resolver.playable(AlarmType.HIGH));
        assertEquals(1, created.get());
        assertEquals(1, reports.get());
    }

    @Test
    public void soundResolverThatCannotBeCreated_failsOnlyTheCallAndTriesAgain() {
        AtomicInteger attempts = new AtomicInteger();
        DeferredStores.SoundResolver resolver = new DeferredStores.SoundResolver(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("user locked");
            }
            return new AlarmSoundResolver() {
                @Override
                public PlayableSound playable(AlarmType type) {
                    return PlayableSound.CLASSIC;
                }

                @Override
                public void reportUnplayable(AlarmType type) {
                    // not needed here
                }
            };
        });
        try {
            resolver.playable(AlarmType.LOW);
            org.junit.Assert.fail("expected the first call to fail");
        } catch (IllegalStateException expected) {
            // the engine's ResolverSoundChoice turns this into the classic tone
        }
        assertEquals(PlayableSound.CLASSIC, resolver.playable(AlarmType.LOW));
        assertEquals(2, attempts.get());
    }
}
