package no.nav.paw.arbeidssokerregisteret

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.FreeSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.headers
import io.ktor.serialization.jackson.*
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.paw.arbeidssokerregisteret.TestData
import no.nav.paw.arbeidssokerregisteret.application.*
import no.nav.paw.arbeidssokerregisteret.application.OK
import no.nav.paw.arbeidssokerregisteret.auth.configureAuthentication
import no.nav.paw.arbeidssokerregisteret.domain.Identitetsnummer
import no.nav.paw.arbeidssokerregisteret.domain.http.PeriodeTilstand
import no.nav.paw.arbeidssokerregisteret.domain.http.StartStoppRequest
import no.nav.paw.arbeidssokerregisteret.plugins.configureHTTP
import no.nav.paw.arbeidssokerregisteret.plugins.configureSerialization
import no.nav.paw.arbeidssokerregisteret.requestScope
import no.nav.paw.arbeidssokerregisteret.routes.arbeidssokerRoutes
import no.nav.paw.arbeidssokerregisteret.utils.TokenXPID
import no.nav.security.mock.oauth2.MockOAuth2Server

class InngangSomBrukerTest : FreeSpec({
    val oauth = MockOAuth2Server()
    val testAuthUrl = "/testAuthTokenx"

    beforeSpec {
        oauth.start()
    }

    afterSpec {
        oauth.shutdown()
    }
    "Teste inngang som bruker" - {
        val requestHandler: RequestHandler = mockk()
        coEvery {
            with(any<RequestScope>()) {
                requestHandler.startArbeidssokerperiode(any(), any())
            }
        } returns OK(
            regel = Regel(
                id = RegelId.OVER_18_AAR_OG_BOSATT_ETTER_FREG_LOVEN,
                beskrivelse = "",
                opplysninger = emptyList(),
                vedTreff = ::OK
            ),
            opplysning = emptySet()
        )
        testApplication {
            application {
                configureHTTP()
                configureSerialization()
                configureAuthentication(oauth)
                routing {
                    authenticate("tokenx") {
                        arbeidssokerRoutes(requestHandler)
                    }
                }
            }
            val token = oauth.issueToken(
                claims = mapOf(
                    "acr" to "idporten-loa-high",
                    "pid" to "12345678909"
                )
            )
            val client = createClient {
                install(ContentNegotiation) {
                    jackson {
                        registerKotlinModule()
                        registerModule(JavaTimeModule())
                    }
                }
            }
            val response = client.put("/api/v1/arbeidssoker/periode") {
                bearerAuth(token.serialize())
                headers { append(HttpHeaders.ContentType, ContentType.Application.Json) }
                setBody(
                    StartStoppRequest(
                        identitetsnummer = "12345678909",
                        registreringForhaandsGodkjentAvAnsatt = false,
                        periodeTilstand = PeriodeTilstand.STARTET
                    )
                )
            }
            response.status shouldBe HttpStatusCode.NoContent
            coVerify(exactly = 1) {
                with(any<RequestScope>()) {
                    requestHandler.startArbeidssokerperiode(Identitetsnummer("12345678909"), false)
                }
            }
        }
    }
})
