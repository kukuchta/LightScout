// :domain (CP-00 §3). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
// Zero main dependencies (JDK only, CP-00 R-1). Tests: JUnit 4.13 only, hand-written fakes (CP-00 §2).
plugins {
    id("lightscout.module")
}

dependencies {
    testImplementation(libs.junit)
}
