// :alarm (CP-00 §3, CP-07). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain, :usecases, :platform, :ui-core (never :monitor / :widget / :storage / :nightscout — R-2, R-3) +
// AndroidX core (NotificationCompat, ContextCompat). AndroidX activity and Material Components arrive through the
// :ui-core api (ComponentActivity, MaterialButton). Platform facilities: AudioManager, MediaPlayer, Vibrator,
// PowerManager (CP-07 §4.2).
// Tests: JUnit 4.13 (JVM, hand-written fakes); AndroidX test runner/core/ext-junit (instrumented, compiled only in
// SS-10; run batched on RD-2 API 33 over USB — CP-00 §10 CR-04, D-048).
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

    implementation(libs.androidx.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
