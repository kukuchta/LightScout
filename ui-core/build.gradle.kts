// :ui-core (CP-00 §3, CP-06). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain + :platform; AndroidX core (IconCompat, WindowInsetsCompat), activity (EdgeToEdge), fragment and
// navigation-fragment (NavResults, R-38), lifecycle-livedata (§8.4 adapters) and Material Components (M3 theme,
// CMP-30). All from the catalog and the CP-00 §2 allow-list; no -ktx artifact.
// Tests: JUnit 4.13 (JVM, hand-written fakes); AndroidX test runner/core/ext-junit (instrumented, compiled only in
// SS-08; run on RD-2 API 33 over USB — CP-00 §10 CR-04, D-048).
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
    implementation(project(":platform"))

    // Types of these libraries appear in public :ui-core signatures (IconCompat, LiveData, Fragment,
    // ComponentActivity) or its resources (Theme.Material3 parent), so they are exposed to consumers.
    api(libs.androidx.core)
    api(libs.androidx.activity)
    api(libs.androidx.fragment)
    api(libs.androidx.lifecycle.livedata)
    api(libs.material)
    implementation(libs.androidx.navigation.fragment)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
