// :storage (CP-00 §3, CP-04). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain + platform APIs only (SQLiteOpenHelper, SharedPreferences, Android Keystore, org.json).
// Tests: JUnit 4.13 + org.json (JVM, hand-written fakes); AndroidX test runner/core/ext-junit (instrumented,
// compiled only in SS-03; run on RD-2 API 33 over USB — CP-00 §10 CR-04, D-048).
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

    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
