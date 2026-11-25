package app.lightscout.ui.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Explicit launch intent of {@code MainActivity} for PendingIntents of runtime modules (CP-00A R-12, R-36; SR-22),
 * so that no runtime module references {@code :app}.
 */
public final class AppLaunch {

    private AppLaunch() {
    }

    /**
     * Builds the explicit intent that opens (or brings to front) the app's launcher activity: the component comes
     * from {@code PackageManager.getLaunchIntentForPackage}; flags {@code CLEAR_TOP | SINGLE_TOP}.
     *
     * @param c any context of this app
     * @return the explicit intent
     * @throws IllegalStateException if the package has no launcher activity (a build error)
     */
    public static Intent mainActivityIntent(Context c) {
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        ComponentName component = launch != null ? launch.getComponent() : null;
        if (component == null) {
            throw new IllegalStateException("no launcher activity in " + c.getPackageName());
        }
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
