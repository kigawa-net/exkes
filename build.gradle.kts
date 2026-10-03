import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Plugin versions are declared once in gradle/libs.versions.toml and applied
// per project below (plan chapter 4).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

// Captured outside the subprojects block: the `libs` accessor belongs to this
// script's project, not to the target projects of subprojects { }.
val kotlinJvmPluginId = libs.plugins.kotlin.jvm.get().pluginId
val kotlinSerializationPluginId = libs.plugins.kotlin.serialization.get().pluginId
val kotlinTestJunit5 = libs.kotlin.test.junit5

allprojects {
    group = "net.kigawa.exkes"
    version = "0.0.1"
}

subprojects {
    // Every module is a Kotlin/JVM module. runtime-agent is a sandbox-side
    // module and must never depend on exkes-common (plan chapter 3).
    apply(plugin = kotlinJvmPluginId)
    apply(plugin = kotlinSerializationPluginId)

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlinTestJunit5)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
