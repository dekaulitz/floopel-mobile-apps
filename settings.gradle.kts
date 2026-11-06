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

rootProject.name = "floopel-mobile-apps"

include(":apps:android:traveler")
include(":apps:android:partner")
include(":shared:core:model")
include(":shared:core:designsystem")
include(":shared:features:traveler")
include(":shared:features:partner")
