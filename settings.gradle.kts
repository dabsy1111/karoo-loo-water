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

rootProject.name = "LooWater"

// karoo-ext (Apache 2.0, SRAM LLC) is vendored as source in ./karoo-ext so the
// project builds without a GitHub Packages token.
include(":karoo-ext", ":app")
