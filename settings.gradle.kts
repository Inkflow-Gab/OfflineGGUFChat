pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencyResolutionManagement {
        repositories.setMode(RepositoryMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "OfflineGGUFChat"
include(":app")
