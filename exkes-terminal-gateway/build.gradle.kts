// exkes-terminal-gateway: WebSocket terminal relay.
//
// OUT OF SCOPE for issue #2 ("対象外: Terminal Gateway"): the issue only asks for
// a deployable exkes Control Plane (stg auto deploy + manual prod promotion), so
// this module stays a skeleton and is not pushed as a container image by
// .github/workflows/deploy-stg.yml. Relay, session validation and audit logging
// land in a follow-up milestone after #1 (plan chapter 3.1).
plugins {
    application
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":exkes-common"))

    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
}
