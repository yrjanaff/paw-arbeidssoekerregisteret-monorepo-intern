plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":lib:error-handling"))
    implementation(libs.ktor.server.auth)
    implementation(libs.logbackClassic)
    implementation(libs.nav.security.tokenValidationKtorV2)

    //Test
    testImplementation(project(":lib:pdl-client"))
    testImplementation(libs.nav.poao.tilgangClient)
    testImplementation(libs.nav.security.mockOauth2Server)
    testImplementation(libs.bundles.testLibsWithUnitTesting)
    testImplementation(libs.ktor.server.testJvm)
    testImplementation(libs.ktor.client.contentNegotiation)
    testImplementation(libs.ktor.serialization.jackson)
    testImplementation(libs.jackson.datatypeJsr310)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
