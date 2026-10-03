// exkes-controller: the only Control Plane process with Kubernetes credentials.
//
// Per issue #2 ("Controller RBAC") the Runtime/PVC permissions are granted to
// this module's ServiceAccount only; exkes-api runs with
// `automountServiceAccountToken: false` and has no client-java dependency.
//
// NOTE: the reconciliation loop assumes a single active instance (platform
// deploys `replicas: 1`). Every DB write is a conditional UPDATE and PVC
// operations are idempotent, so a second replica would not corrupt state, but
// it would double the reconcile rate and keep per-process attempt counters.
// Lease-based leader election has not been discussed yet.
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
    // KubernetesClientFactory / KubernetesWorkspaceProvisioner. Kept here and
    // never re-exported: exkes-api must not have client-java on its classpath.
    implementation(libs.kubernetes.client)
    // JSON parsing for RuntimeSpec/Template merging
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.h2)
}
