pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "exkes"

include(
    "exkes-common",
    "exkes-api",
    "exkes-controller",
    "exkes-terminal-gateway",
    "runtime-agent",
)
