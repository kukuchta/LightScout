// Build code only (Kotlin DSL script). The plugin itself is written in Java: no Kotlin plugin, no kotlin-dsl.
plugins {
    `java-gradle-plugin`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    implementation(libs.android.gradle.plugin)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("lightscoutModule") {
            id = "lightscout.module"
            implementationClass = "lightscout.buildlogic.LightScoutModulePlugin"
        }
    }
}
