# R8 rules for the release build of :app (CP-15 §8.6, 08 §8.1, SS-14 brief / Q-49).
# Release: shrinking + optimisation + obfuscation (proguard-android-optimize.txt). The mapping file stays private
# with the release (build/outputs/mapping/release/mapping.txt), never inside the APK.

# --- Log stripping: ONLY DEBUG / VERBOSE (Log.d / Log.v) and Logger.debug are removed. Event codes at INFO / WARN /
# --- ERROR (Log.i / Log.w / Log.e through PlatformLogger) stay in release logcat (07 §10 S2, Q-49).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
-assumenosideeffects interface app.lightscout.domain.port.Logger {
    public void debug(java.lang.String, java.lang.String);
}
-assumenosideeffects class * implements app.lightscout.domain.port.Logger {
    public void debug(java.lang.String, java.lang.String);
}

# --- Navigation argument types are looked up by name when the graphs are inflated (argType in
# --- onboarding_graph.xml) and enum arguments are matched by constant name: keep both.
-keep enum app.lightscout.ui.core.ConnectionMode { *; }
-keep enum app.lightscout.ui.core.ReadinessMode { *; }
-keep enum app.lightscout.platform.Manufacturer { *; }

# --- Fragments are re-created by class name from navigation XML and saved state.
-keep public class app.lightscout.** extends androidx.fragment.app.Fragment {
    public <init>();
}
