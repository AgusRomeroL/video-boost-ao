pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android y sus transitivas (spake2-android, sun-security-android)
        // se publican via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "VideoBoostAO"
include(":app")
