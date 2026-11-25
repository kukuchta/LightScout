// Included build holding the single LightScout convention plugin "lightscout.module" (CP-00A R-86).
dependencyResolutionManagement {
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
