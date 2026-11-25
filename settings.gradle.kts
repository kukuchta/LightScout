// LightScout root settings (CP-00 §2, §3; CP-15 §8.7; CP-00A R-86).
pluginManagement {
    // The single convention plugin "lightscout.module" (it brings AGP onto the build classpath).
    includeBuild("build-logic")
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.android(\..*)?""")
                includeGroupByRegex("""com\.google\.android\..*""")
                includeGroupByRegex("""com\.google\.testing\.platform(\..*)?""")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories (no JitPack or ad-hoc repositories).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // AndroidX / Android / Google Android groups come only from google() (CP-15 §8.7).
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.android(\..*)?""")
                includeGroupByRegex("""com\.google\.android\..*""")
                includeGroupByRegex("""com\.google\.testing\.platform(\..*)?""")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "LightScout"

// Module map of CP-00 §3. The plugin type of each module is chosen by the convention plugin.
include(
    ":domain",
    ":usecases",
    ":nightscout",
    ":storage",
    ":platform",
    ":ui-core",
    ":alarm",
    ":monitor",
    ":widget",
    ":feature-home",
    ":feature-entry",
    ":feature-stats",
    ":feature-settings",
    ":feature-onboarding",
    ":app",
)
