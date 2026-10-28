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

// Bundle the dashboard WASM static files into exkes-api resources.
// This task builds the dashboard and copies the output to
// exkes-api/src/main/resources/dashboard/ so that Ktor can serve them.
// Skipped when BUNDLE_DASHBOARD=false (e.g., non-exkes-api Docker builds).
tasks.register("bundleDashboard") {
    onlyIf {
        System.getenv("BUNDLE_DASHBOARD") != "false"
    }
    dependsOn(":exkes-dashboard:wasmJsBrowserDistribution")
    doLast {
        val distDir = file("../exkes-dashboard/build/dist/wasmJs/productionExecutable")
        val targetDir = file("src/main/resources/dashboard")
        
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()
        
        // Copy all files from the WASM distribution directory
        distDir.copyRecursively(targetDir, overwrite = true)
    }
}
