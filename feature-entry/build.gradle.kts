// :feature-entry (CP-00 §3, CP-13). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain, :usecases, :platform, :ui-core only (IF-23 EntryWriter / IF-06 EntryRules; never the gateway or the
// stores — R-4). AndroidX fragment, activity, livedata and Material Components arrive through the :ui-core api; added
// here: lifecycle ViewModel + viewmodel-savedstate (S-02 form in SavedStateHandle, CP-13 §7.7), navigation-fragment
// (nested graph entry_graph, CP-00A R-38). All from the catalog and the CP-00 §2 allow-list; no -ktx.
// Safe Args: the Java generator (CP-00 §2 „navigation (XML + Safe Args Java)”), applied in this module only.
// Tests: JUnit 4.13 (JVM, hand-written fakes); AndroidX test runner/core/ext-junit + Espresso (instrumented TST-06,
// compiled in SS-15; run in the M2 batched device run).
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

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
