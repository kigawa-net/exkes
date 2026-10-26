// runtime-agent: runs inside Runtime Pods on the sandbox plane.
//
// Process / Execution management (issue #8). It must NOT depend on
// exkes-common (plan chapter 3): the sandbox side only speaks JSON over
// HTTP and never links control plane code, so the Execution state machine is
// deliberately re-declared here.
plugins {
    application
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback.classic)
    // PTY. pty4j's kotlin-stdlib dependency is excluded: the project pins the stdlib
    // through the Kotlin plugin, and a newer transitive stdlib would not be readable
    // by the 2.1.x compiler.
    implementation(libs.pty4j) {
        exclude(group = "org.jetbrains.kotlin")
    }
    // Process-group signalling (kill(-pgid, SIGKILL)). pty4j already needs it at runtime.
    implementation(libs.jna)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}
