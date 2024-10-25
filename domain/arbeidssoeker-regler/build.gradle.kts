plugins {
    kotlin("jvm")
    id("org.openapi.generator")
    application
    id("com.google.cloud.tools.jib")
}

val baseImage: String by project
val jvmMajorVersion: String by project

dependencies {
    implementation(project(":lib:pdl-client"))
    implementation(project(":domain:interne-hendelser"))
    api(libs.micrometer.registryPrometheus)
    api(libs.arrow.core.core)
    testImplementation(libs.ktor.server.testJvm)
    testImplementation(libs.test.junit5.runner)
    testImplementation(libs.test.kotest.assertionsCore)
    testImplementation(libs.test.testContainers.core)
    testImplementation(libs.test.mockOauth2Server)
    testImplementation(libs.test.mockk.core)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jvmMajorVersion))
    }
}

application {
    mainClass.set("no.nav.paw.arbeidssokerregisteret.ApplicationKt")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
