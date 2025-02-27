plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    id("com.google.cloud.tools.jib") version "3.4.4" apply false
    id("org.openapi.generator") version "7.11.0" apply false
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1" apply false
    id("com.expediagroup.graphql") version "8.3.0" apply false
}

rootProject.name = "paw-arbeidssoekerregisteret-monorepo-intern"

include(
    "lib:logging",
    "lib:hoplite-config",
    "lib:error-handling",
    "lib:metrics",
    "lib:security",
    "lib:database",
    "lib:scheduling",
    "lib:http-client-utils",
    "lib:kafka",
    "lib:kafka-streams",
    "lib:kafka-key-generator-client",
    "lib:pdl-client",
    "lib:aareg-client",
    "lib:tilgangskontroll-client",
    "lib:common-model",
    "test:test-data-lib",
    "test:kafka-streams-test-functions",
    "domain:bekreftelse-interne-hendelser",
    "domain:bekreftelse-paavegneav-avro-schema",
    "domain:bekreftelsesmelding-avro-schema",
    "domain:main-avro-schema",
    "domain:interne-hendelser",
    "domain:arbeidssoekerregisteret-kotlin",
    "domain:arbeidssoeker-regler",
    "domain:pdl-aktoer-schema",
    "apps:api-start-stopp-perioder",
    "apps:hendelseprosessor",
    "apps:utgang-formidlingsgruppe",
    "apps:hendelselogg-backup",
    "apps:utgang-pdl",
    "apps:kafka-key-generator",
    "apps:kafka-key-maintenance",
    "apps:bekreftelse-tjeneste",
    "apps:bekreftelse-api",
    "apps:bekreftelse-min-side-oppgaver",
    "apps:bekreftelse-utgang",
    "apps:bekreftelse-hendelsefilter",
    "apps:tilgangskontroll",
    "apps:meldekort-adapter",
    "apps:dolly-api"
)

dependencyResolutionManagement {
    val githubPassword: String by settings
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        maven {
            url = uri("https://packages.confluent.io/maven/")
        }
        maven {
            setUrl("https://maven.pkg.github.com/navikt/poao-tilgang")
            credentials {
                username = "x-access-token"
                password = githubPassword
            }
        }
        maven {
            setUrl("https://maven.pkg.github.com/navikt/paw-arbeidssokerregisteret-api")
            credentials {
                username = "x-access-token"
                password = githubPassword
            }
        }
        maven {
            setUrl("https://maven.pkg.github.com/navikt/paw-kotlin-clients")
            credentials {
                username = "x-access-token"
                password = githubPassword
            }
        }
    }
}
