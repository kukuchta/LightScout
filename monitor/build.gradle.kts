// :monitor (CP-00 §3, CP-08 slice A). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain, :usecases, :platform, :ui-core (never :alarm / :widget — R-3) + AndroidX core (NotificationCompat,
// ContextCompat, ServiceCompat). Platform facilities: AlarmManager, JobScheduler, PowerManager (CP-00 §2).
// Tests: JUnit 4.13 (JVM, hand-written fakes for the policy classes); AndroidX test runner/core/ext-junit
// (instrumented, compiled only in SS-09; run batched on RD-2 API 33 over USB — CP-00 §10 CR-04, D-048).
plugins {
    id("lightscout.module")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Test-only code shared by the JVM and the instrumented tests (BUG-09: the pure dumpsys-alarm parser
    // AlarmDump, checked on the JVM against a fixture from a real device dump). No production source.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
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
