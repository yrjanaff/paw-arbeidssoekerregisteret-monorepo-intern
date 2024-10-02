plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(libs.ktor.server.cors)
    compileOnly(libs.ktor.serialization.jackson)
    compileOnly(libs.kafka.streams.core)
    compileOnly(libs.logbackClassic)

    //Test
    testImplementation(libs.bundles.testLibsWithUnitTesting)
    testImplementation(libs.ktor.server.testJvm)
    testImplementation(libs.ktor.server.contentNegotiation)
    testImplementation(libs.ktor.server.statusPages)
    testImplementation(libs.ktor.serialization.jackson)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.kafka.streams.core)
    testImplementation(libs.logbackClassic)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
