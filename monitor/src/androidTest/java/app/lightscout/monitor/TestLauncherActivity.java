package app.lightscout.monitor;

import android.app.Activity;

/**
 * Launcher stand-in of the test APK: {@code AppLaunch.mainActivityIntent} needs a launcher activity in the package, the
 * self-instrumenting test APK has none, so S-10 / S-12 content intents could not be built in the device tests.
 */
public class TestLauncherActivity extends Activity {
}
