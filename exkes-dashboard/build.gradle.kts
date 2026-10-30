// exkes-dashboard: Compose Multiplatform WASM web app.
//
// A standalone dashboard that fetches data from the exkes-api REST endpoints
// and displays Workspace status overview. Deployed as static files separately
// from the control plane.

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.compose") version libs.versions.compose.get()
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

// Use the system-installed Node.js instead of downloading from nodejs.org.
// This avoids long download timeouts in CI environments (Dockerfile already
// installs Node.js via apt).
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<NodeJsRootExtension>().download = false
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "index.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation(libs.ktor.client.core.wasm)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.serialization.kotlinx.json.wasm)
                implementation(libs.ktor.client.content.negotiation.wasm)
            }
        }
    }
}
