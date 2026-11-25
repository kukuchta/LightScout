// Root project: no code. "gradlew build" also runs the build-logic JVM tests (versioning, module rules).
plugins {
    base
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
