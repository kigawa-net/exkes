// exkes-terminal-gateway: WebSocket terminal relay (plan chapter 3.1 / 第5MS).
//
// Accepts a single-use terminal session token, re-checks the Runtime through the
// shared admission rule, and relays JSON frames to the runtime-agent in the Runtime
// Pod (docs/runtime-agent-terminal.md). It never calls the Kubernetes API and has no
// client-java dependency (issue #2). It does not run Flyway migrations: schema changes
// belong to exkes-api / exkes-controller.
//
// Not yet in CI: deploy-stg.yml / deploy-prod.yml do not build or promote this module
// (see docs/terminal-gateway.md, "CI" section for the reasoning).
plugins {
    application
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":exkes-common"))

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
}
