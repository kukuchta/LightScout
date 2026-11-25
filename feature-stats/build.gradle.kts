// :feature-stats (CP-00 §3). Plugin type, SDK levels, Java 17 and dependency rules come from "lightscout.module".
plugins {
    id("lightscout.module")
    alias(libs.plugins.navigation.safeargs)
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
