// exkes-api: the milestone-1 HTTP entry point of the Control Plane.
//
// It writes desired state only (a CREATING row / a DELETING transition) and holds
// no Kubernetes credentials: WorkspaceProvisioner / WorkspaceReconciler live in
// exkes-controller, the only process with client-java (issue #2 "Controller RBAC").
plugins {
    application
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":exkes-common"))

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
}
