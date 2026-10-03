// exkes-common: shared library for control plane modules.
// Holds config / DB (Exposed + Flyway) / Workspace domain.
//
// It must stay free of any Kubernetes (client-java) dependency: the platform
// grants Kubernetes permissions to exkes-controller only, and exkes-api runs
// with `automountServiceAccountToken: false` (issue #2 "Controller RBAC").
// KubernetesClientFactory therefore lives in exkes-controller.
dependencies {
    // Exposed types appear in public API (WorkspacesTable), hence api().
    api(libs.ktor.server.core)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)

    implementation(libs.hikari.cp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.mariadb.driver)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.h2)
}
