package app.lightscout.ui.core;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.test.core.app.ApplicationProvider;

import java.util.Locale;

/**
 * Themed contexts for instrumented tests: {@code Theme.LightScout} with a forced light / dark uiMode, font scale
 * and English locale.
 */
final class DeviceContexts {

    private DeviceContexts() {
    }

    static Context themed(boolean night) {
        return themed(night, 1.0f);
    }

    static Context themed(boolean night, float fontScale) {
        Context app = ApplicationProvider.getApplicationContext();
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        cfg.fontScale = fontScale;
        cfg.setLocale(Locale.ENGLISH);
        return new ContextThemeWrapper(app.createConfigurationContext(cfg), R.style.Theme_LightScout);
    }
}
