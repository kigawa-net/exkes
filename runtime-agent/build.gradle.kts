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
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
}
