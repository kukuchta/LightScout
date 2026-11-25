// :nightscout (CP-00 §3, CP-03). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain + OkHttp 5 (the only module allowed to use it) + platform org.json.
// Tests (JVM only, CP-03 §9): JUnit 4.13, mockwebserver3, org.json:json (the JVM org.json; on Android the platform
// org.json is used at runtime).
plugins {
    id("lightscout.module")
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.org.json)
}
