pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "exkes"

include(
    "exkes-common",
    "exkes-api",
    "exkes-controller",
    "exkes-terminal-gateway",
    "runtime-agent",
    "exkes-dashboard",
)
