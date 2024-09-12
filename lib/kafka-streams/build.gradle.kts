plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":lib:kafka"))
    implementation(orgApacheKafka.kafkaClients)
    implementation(orgApacheKafka.kafkaStreams)
    implementation(apacheAvro.kafkaStreamsAvroSerde)

    // Test
    testImplementation(testLibs.bundles.withUnitTesting)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
