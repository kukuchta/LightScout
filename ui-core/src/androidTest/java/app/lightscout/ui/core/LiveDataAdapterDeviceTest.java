package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * §9 "LiveData adapter": a {@code Callback} invoked on a background executor reaches the observer on the main
 * thread via {@code postValue}; {@link StatusLiveData} registers its listener only while observed and delivers
 * listener calls from another thread on the main thread.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). Compiled in SS-08.
 */
@RunWith(AndroidJUnit4.class)
public class LiveDataAdapterDeviceTest {

    private static StatusSnapshot snapshot(Instant at) {
        ConsumableStatus none = new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND, null, null,
                null, null);
        ConsumableStatus sensor = new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND, null,
                null, null, null);
        return new StatusSnapshot(null, null, null, false, new DataLossStatus(DataLossKind.NONE, null, null),
                FetchOutcome.OK, null, null, null, true, false, true, true, true, true, 55, 70, 250, 20, null, null,
                none, sensor, 0, Banner.NONE, at);
    }

    @Test
    public void callbackOnBackgroundExecutorReachesObserverOnMainThread() throws Exception {
        MutableLiveData<Result<String>> live = new MutableLiveData<>();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Boolean> onMain = new AtomicReference<>();
        AtomicReference<Result<String>> received = new AtomicReference<>();
        Observer<Result<String>> observer = r -> {
            onMain.set(Looper.myLooper() == Looper.getMainLooper());
            received.set(r);
            delivered.countDown();
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> live.observeForever(observer));
        Callback<String> cb = LiveDataCallbacks.postingTo(live);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            io.execute(() -> cb.onResult(Result.ok("done")));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        } finally {
            io.shutdownNow();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> live.removeObserver(observer));
        }
        assertTrue(onMain.get());
        assertEquals("done", received.get().value());
    }

    @Test
    public void statusLiveDataFollowsListenerWhileObserved() throws Exception {
        List<StatusListener> listeners = new CopyOnWriteArrayList<>();
        StatusSnapshot first = snapshot(Instant.parse("2026-10-15T12:00:00Z"));
        StatusSnapshot second = snapshot(Instant.parse("2026-10-15T12:05:00Z"));
        LiveData<StatusSnapshot> live = StatusLiveData.of(() -> first, listeners::add, listeners::remove);
        CountDownLatch gotSecond = new CountDownLatch(1);
        AtomicReference<Boolean> onMain = new AtomicReference<>();
        Observer<StatusSnapshot> observer = s -> {
            if (s == second) {
                onMain.set(Looper.myLooper() == Looper.getMainLooper());
                gotSecond.countDown();
            }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            live.observeForever(observer);
            assertSame(first, live.getValue());
        });
        assertEquals(1, listeners.size());
        Thread monitor = new Thread(() -> listeners.get(0).onStatus(second));
        monitor.start();
        monitor.join();
        assertTrue(gotSecond.await(5, TimeUnit.SECONDS));
        assertTrue(onMain.get());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> live.removeObserver(observer));
        assertEquals(0, listeners.size());
    }
}
