package no.nav.paw.kafkakeygenerator.plugin

import io.ktor.server.application.Application
import io.ktor.server.application.install
import no.nav.paw.error.plugin.ErrorHandlingPlugin

fun Application.configureErrorHandling() {
    install(ErrorHandlingPlugin)
}
