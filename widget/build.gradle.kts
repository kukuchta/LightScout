// :widget (CP-00 §3). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
plugins {
    id("lightscout.module")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":usecases"))
    implementation(project(":platform"))
    implementation(project(":ui-core"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
