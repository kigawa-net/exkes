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
    implementation(libs.ktor.server.cors)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
}

// Bundle the dashboard WASM static files into the exkes-api installDist directory.
// Ktor's staticResources() cannot reliably serve files from inside a JAR
// (KTOR-3880), so we copy the dashboard files next to the JAR in the installDist
// layout. Ktor then serves them from the file system using static { files(...) }.
// Skipped when BUNDLE_DASHBOARD=false (e.g., non-exkes-api Docker builds).
val bundleDashboardTask = tasks.register("bundleDashboard") {
    onlyIf {
        System.getenv("BUNDLE_DASHBOARD") != "false"
    }
    dependsOn(":exkes-dashboard:wasmJsBrowserDistribution")
    dependsOn("installDist")
    doLast {
        val distDir = file("../exkes-dashboard/build/dist/wasmJs/productionExecutable")
        // Copy to build/install/exkes-api/dashboard/ so Ktor can serve from the file system.
        val targetDir = file("build/install/exkes-api/dashboard")

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        // Copy all files from the WASM distribution directory
        distDir.copyRecursively(targetDir, overwrite = true)
    }
}
