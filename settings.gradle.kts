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
    }
}

rootProject.name = "IMU-mapper"

// The pipeline is a standalone Kotlin/JVM build so it can be built and tested
// on machines without an Android SDK:  cd pipeline && ../gradlew test
includeBuild("pipeline")

include(":app")
