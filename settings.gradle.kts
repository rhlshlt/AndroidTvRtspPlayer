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
        // rtsp-client-android is published only on JitPack; keep JitPack limited to its author's
        // groups (JitPack may name a module com.github.alexeyvasilyev.rtsp-client-android).
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.alexeyvasilyev(\\..*)?") }
        }
    }
}

rootProject.name = "TvRtspPlayer"

include(":app")
include(":core:rtsp")
include(":core:player")
include(":core:perf")
include(":tools:camcheck")
