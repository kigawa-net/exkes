// runtime-agent: runs inside Runtime Pods on the sandbox plane.
//
// OUT OF SCOPE for issue #2 (same as exkes-terminal-gateway): no Runtime Pods
// exist yet, so nothing runs this module and no container image is published
// for it. Process/PTY/Execution management lands in a follow-up milestone
// after #1 (plan chapter 3.1).
//
// It must NOT depend on exkes-common (plan chapter 3): the sandbox side only
// speaks JSON over HTTP/WebSocket and never links control plane code.
plugins {
    application
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
}
