package app.lightscout;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

/**
 * Instrumentation runner of the {@code :app} device tests (TST-05 / 06 / 07 parts, CP-15 §9): starts the app with
 * {@link TestLightScoutApplication}, i.e. the real composition root and {@code MainActivity} over in-memory stores and
 * a gateway that never contacts any server (CP-00 §8). Runs on RD-2 (API 33) over USB in the batched M1 run (D-050).
 */
public final class LightScoutTestRunner extends AndroidJUnitRunner {

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return super.newApplication(cl, TestLightScoutApplication.class.getName(), context);
    }
}
