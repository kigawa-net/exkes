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
        // Required for Compose Multiplatform WASM dependencies (e.g., runtime-saveable-wasm-js)
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
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
