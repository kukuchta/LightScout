// :usecases (CP-00 §3). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Main: :domain + JDK only (CP-00 R-1). Tests: JUnit 4.13 only, hand-written fakes (CP-02 §9).
plugins {
    id("lightscout.module")
}

dependencies {
    implementation(project(":domain"))
    testImplementation(libs.junit)
}
