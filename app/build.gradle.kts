// :app (CP-00 §3, CP-15). Plugin type, SDK levels, Java 17, build types (debug / debugProxy / unsigned release),
// versioning and dependency rules come from "lightscout.module".
// Added by CP-15b (SS-14): BuildConfig (DEBUG for StrictMode / debug logging, VERSION_NAME for IF-28 AppInfo; no URL
// or token in BuildConfig, SR-33), navigation-fragment (NavHostFragment for root_graph), JVM tests (container wiring,
// navigation rules, TST-13 merged-manifest audit) and instrumented tests (TST-05 / 06 / 07 parts; compiled here, run
// on RD-2 API 33 over USB in the batched M1 run — CP-00 §10 CR-04, D-048, D-050).
import com.android.build.api.artifact.SingleArtifact

plugins {
    id("lightscout.module")
}

android {
    defaultConfig {
        testInstrumentationRunner = "app.lightscout.LightScoutTestRunner"
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        // IR-113 (SR-33 hygiene): no git revision (META-INF/version-control-info.textproto) in the release APK.
        getByName("release") {
            vcsInfo {
                include = false
            }
        }
    }
}

// TST-13 (CP-15 §9): the JVM audit reads the MERGED manifest of every variant (release, debug, debugProxy) and the
// network security config sources. Paths are handed over as system properties; the manifest tasks become inputs.
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val property = "lightscout.mergedManifest." + variant.name
        tasks.withType<Test>().configureEach {
            inputs.file(manifest).withPropertyName(property)
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-D" + property + "=" + manifest.get().asFile.absolutePath)
            })
        }
    }
}

tasks.withType<Test>().configureEach {
    val root = rootDir.absolutePath
    inputs.dir(layout.projectDirectory.dir("src")).withPropertyName("appSources")
    // M1MissingDestinationsTest: re-run when a feature graph appears (S-02 / S-03 / S-04).
    inputs.files(fileTree(rootDir) { include("feature-*/src/main/res/navigation/*.xml") })
        .withPropertyName("featureGraphs")
    // RootGraphRestoreTest (BUG-12): re-run when a feature fragment starts using a graph-scoped entry.
    inputs.files(fileTree(rootDir) { include("feature-*/src/main/java/**/*.java", "ui-core/src/main/java/**/*.java") })
        .withPropertyName("featureSources")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dlightscout.rootDir=" + root) })
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":usecases"))
    implementation(project(":nightscout"))
    implementation(project(":storage"))
    implementation(project(":platform"))
    implementation(project(":ui-core"))
    implementation(project(":alarm"))
    implementation(project(":monitor"))
    implementation(project(":widget"))
    implementation(project(":feature-home"))
    implementation(project(":feature-entry"))
    implementation(project(":feature-stats"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-onboarding"))

    implementation(libs.androidx.navigation.fragment)

    testImplementation(libs.junit)
    // BUG-18 S-B F2: composed real-gateway + SlicedWindowFetcher test (CP-00 §2 test-only libraries, as :nightscout)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
