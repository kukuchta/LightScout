// :feature-onboarding (CP-00 §3, CP-14). Plugin type, SDK levels, Java 17 and dependency rules come from
// "lightscout.module".
// Main: :domain, :usecases, :platform, :ui-core only (never :storage / :nightscout / :monitor — R-2, R-4).
// AndroidX fragment, activity, livedata and Material Components arrive through the :ui-core api; added here:
// lifecycle ViewModel + viewmodel-savedstate (S-06 URL in SavedStateHandle, CP-00 §6) and navigation-fragment
// (nested graph onboarding_graph, CP-00A R-38) and navigation-ui (S-06 Up via NavigationUI, Q-74).
// All from the catalog and the CP-00 §2 allow-list; no -ktx.
// Safe Args: the Java generator "androidx.navigation.safeargs" (CP-00 §2 „navigation (XML + Safe Args Java)”),
// applied in this module only, so the Args / Directions classes are generated inside the feature (R-4, CP-00A R-38).
// Tests: JUnit 4.13 (JVM, hand-written fakes); AndroidX test runner/core/ext-junit + Espresso (instrumented,
// compiled only in SS-11; run on RD-2 API 33 over USB — CP-00 §10 CR-04, D-048).
plugins {
    id("lightscout.module")
    alias(libs.plugins.navigation.safeargs)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":usecases"))
    implementation(project(":platform"))
    implementation(project(":ui-core"))

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
