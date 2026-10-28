pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "halone-android-integration"

include(":app")

// Builds against a local halone-sdk checkout instead of Maven Central: -PhaloneSdk=../halone/sdk/android
providers.gradleProperty("haloneSdk").orNull?.let { includeBuild(it) }
