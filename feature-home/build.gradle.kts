// :feature-home (CP-00 §3, CP-12). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain, :usecases, :platform, :ui-core only (never :storage / :nightscout / :alarm / :monitor — R-2, R-3, R-4).
// AndroidX fragment, activity, livedata and Material Components arrive through the :ui-core api; added here:
// lifecycle ViewModel (HomeViewModel, CMP-70) and navigation-fragment (nested graph home_graph, CP-00A R-38).
// All from the catalog and the CP-00 §2 allow-list; no -ktx (D-052).
// Safe Args: not applied — home_graph has one destination without arguments and no actions (CP-12 §4.1, R-38);
// CP-12b adds the plugin if it ever needs generated classes.
// Tests: JUnit 4.13 (JVM, hand-written fakes); AndroidX test runner/core/ext-junit + Espresso (instrumented,
// compiled only in SS-13; run on RD-2 API 33 over USB — CP-00 §10 CR-04, D-048).
plugins {
    id("lightscout.module")
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
    implementation(libs.androidx.navigation.fragment)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
