package app.lightscout.ui.core;

/**
 * Application facts for the UI (CP-00A R-12): the S-04 „Version {x.y.z}” row (CP-17 #5).
 *
 * @param versionName the SemVer version name of the installed APK
 */
public record AppInfo(String versionName) {
}
