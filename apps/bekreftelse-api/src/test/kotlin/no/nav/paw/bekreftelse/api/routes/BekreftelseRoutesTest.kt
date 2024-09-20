package no.nav.paw.bekreftelse.api.routes

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.append
import io.ktor.server.testing.testApplication
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import no.nav.paw.bekreftelse.api.ApplicationTestContext
import no.nav.paw.bekreftelse.api.model.BekreftelseRequest
import no.nav.paw.bekreftelse.api.model.InnloggetBruker
import no.nav.paw.bekreftelse.api.model.InternState
import no.nav.paw.bekreftelse.api.model.TilgjengeligBekreftelserResponse
import no.nav.paw.bekreftelse.api.model.TilgjengeligeBekreftelserRequest
import no.nav.paw.bekreftelse.melding.v1.Bekreftelse
import no.nav.paw.error.model.ProblemDetails
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StoreQueryParameters

class BekreftelseRoutesTest : FreeSpec({
    with(ApplicationTestContext()) {

        beforeSpec {
            clearAllMocks()
            mockOAuth2Server.start()
        }

        afterSpec {
            confirmVerified(
                kafkaStreamsMock,
                stateStoreMock,
                bekreftelseKafkaProducerMock,
                autorisasjonServiceMock,
                bekreftelseHttpConsumerMock,
                bekreftelseServiceMock
            )
            mockOAuth2Server.shutdown()
        }

        "Test suite for sluttbruker" - {
            "Skal få 500 om Kafka Streams ikke kjører" {
                every { kafkaStreamsMock.state() } returns KafkaStreams.State.NOT_RUNNING

                testApplication {
                    configureTestApplication(bekreftelseServiceReal)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.InternalServerError
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.InternalServerError
                    body.type shouldBe "PAW_UKJENT_FEIL"
                    verify { kafkaStreamsMock.state() }
                }
            }

            "Skal få 500 om ingen state store funnet" {
                every { kafkaStreamsMock.state() } returns KafkaStreams.State.RUNNING
                every { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) } returns null

                testApplication {
                    configureTestApplication(bekreftelseServiceReal)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.InternalServerError
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.InternalServerError
                    body.type shouldBe "PAW_UKJENT_FEIL"
                    verify { kafkaStreamsMock.state() }
                    verify { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) }
                }
            }

            "Skal hente tilgjengelige bekreftelser fra intern state store" {
                every { kafkaStreamsMock.state() } returns KafkaStreams.State.RUNNING
                every { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) } returns stateStoreMock
                every { stateStoreMock.get(any<Long>()) } returns InternState(
                    listOf(
                        testData.nyBekreftelseTilgjengelig()
                    )
                )

                testApplication {
                    configureTestApplication(bekreftelseServiceReal)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<TilgjengeligBekreftelserResponse>()
                    body.size shouldBe 1
                    verify { kafkaStreamsMock.state() }
                    verify { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) }
                    verify { stateStoreMock.get(any<Long>()) }
                }
            }

            "Skal hente tilgjengelige bekreftelser fra annen node" {
                every { kafkaStreamsMock.state() } returns KafkaStreams.State.RUNNING
                every { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) } returns stateStoreMock
                every { stateStoreMock.get(any<Long>()) } returns null
                every {
                    kafkaStreamsMock.queryMetadataForKey(
                        any<String>(),
                        any<Long>(),
                        any<Serializer<Long>>()
                    )
                } returns testData.nyKeyQueryMetadata()
                coEvery {
                    bekreftelseHttpConsumerMock.finnTilgjengeligBekreftelser(
                        any<String>(),
                        any<InnloggetBruker>(),
                        any<TilgjengeligeBekreftelserRequest>()
                    )
                } returns listOf(testData.nyTilgjengeligBekreftelse())

                testApplication {
                    configureTestApplication(bekreftelseServiceReal)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<TilgjengeligBekreftelserResponse>()
                    body.size shouldBe 1
                    verify { kafkaStreamsMock.state() }
                    verify { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) }
                    verify {
                        kafkaStreamsMock.queryMetadataForKey(
                            any<String>(),
                            any<Long>(),
                            any<Serializer<Long>>()
                        )
                    }
                    verify { stateStoreMock.get(any<Long>()) }
                    coVerify {
                        bekreftelseHttpConsumerMock.finnTilgjengeligBekreftelser(
                            any<String>(),
                            any<InnloggetBruker>(),
                            any<TilgjengeligeBekreftelserRequest>()
                        )
                    }
                }
            }

            "Skal motta bekreftelse via intern state store" {
                val request = testData.nyBekreftelseRequest(identitetsnummer = "01017012345")
                every { kafkaStreamsMock.state() } returns KafkaStreams.State.RUNNING
                every { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) } returns stateStoreMock
                every { stateStoreMock.get(any<Long>()) } returns InternState(
                    listOf(
                        testData.nyBekreftelseTilgjengelig(bekreftelseId = request.bekreftelseId)
                    )
                )
                coEvery {
                    bekreftelseKafkaProducerMock.produceMessage(
                        any<Long>(),
                        any<Bekreftelse>()
                    )
                } just runs

                testApplication {
                    configureTestApplication(bekreftelseServiceReal)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.post("/api/v1/bekreftelse") {
                        bearerAuth(token.serialize())
                        headers {
                            append(HttpHeaders.ContentType, ContentType.Application.Json)
                        }
                        setBody(request)
                    }

                    response.status shouldBe HttpStatusCode.OK
                    verify { kafkaStreamsMock.state() }
                    verify { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) }
                    verify { stateStoreMock.get(any<Long>()) }
                    coVerify {
                        bekreftelseKafkaProducerMock.produceMessage(
                            any<Long>(),
                            any<Bekreftelse>()
                        )
                    }
                }
            }

            "Skal motta bekreftelse og overføre den til annen node" {
                val request = testData.nyBekreftelseRequest(identitetsnummer = "01017012345")
                every { kafkaStreamsMock.state() } returns KafkaStreams.State.RUNNING
                every { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) } returns stateStoreMock
                every { stateStoreMock.get(any<Long>()) } returns null
                every {
                    kafkaStreamsMock.queryMetadataForKey(
                        any<String>(),
                        any<Long>(),
                        any<Serializer<Long>>()
                    )
                } returns testData.nyKeyQueryMetadata()
                coEvery {
                    bekreftelseHttpConsumerMock.mottaBekreftelse(
                        any<String>(),
                        any<InnloggetBruker>(),
                        any<BekreftelseRequest>()
                    )
                } just runs

                testApplication {
                    configureTestApplication(bekreftelseServiceReal)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.post("/api/v1/bekreftelse") {
                        bearerAuth(token.serialize())
                        headers {
                            append(HttpHeaders.ContentType, ContentType.Application.Json)
                        }
                        setBody(request)
                    }

                    response.status shouldBe HttpStatusCode.OK
                    verify { kafkaStreamsMock.state() }
                    verify { kafkaStreamsMock.store(any<StoreQueryParameters<*>>()) }
                    verify {
                        kafkaStreamsMock.queryMetadataForKey(
                            any<String>(),
                            any<Long>(),
                            any<Serializer<Long>>()
                        )
                    }
                    verify { stateStoreMock.get(any<Long>()) }
                    coVerify {
                        bekreftelseHttpConsumerMock.mottaBekreftelse(
                            any<String>(),
                            any<InnloggetBruker>(),
                            any<BekreftelseRequest>()
                        )
                    }
                }
            }
        }

        "Test suite for autentisering og autorisering" - {
            "Skal få 401 ved manglende Bearer Token" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }

            "Skal få 401 ved token utstedt av ukjent issuer" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        issuerId = "whatever",
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }

            "Skal få 403 ved token uten noen claims" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken()

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.Forbidden
                    body.type shouldBe "PAW_UFULLSTENDIG_BEARER_TOKEN"
                }
            }

            "Skal få 403 ved TokenX-token uten pid claim" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.Forbidden
                    body.type shouldBe "PAW_UFULLSTENDIG_BEARER_TOKEN"
                }
            }

            "Skal få 403 ved TokenX-token når innsendt ident er ikke lik pid claim" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "acr" to "idporten-loa-high",
                            "pid" to "01017012345"
                        )
                    )

                    val response = client.post("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                        headers {
                            append(HttpHeaders.ContentType, ContentType.Application.Json)
                        }
                        setBody(TilgjengeligeBekreftelserRequest("02017012345"))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.Forbidden
                    body.type shouldBe "PAW_BRUKER_HAR_IKKE_TILGANG"
                }
            }

            "Skal få 403 ved Azure-token men GET-request" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "NAVident" to "12345"
                        )
                    )

                    val response = client.get("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.Forbidden
                    body.type shouldBe "PAW_BRUKER_HAR_IKKE_TILGANG"
                }
            }

            "Skal få 403 ved Azure-token med POST-request uten ident" {
                testApplication {
                    configureTestApplication(bekreftelseServiceMock)
                    val client = configureTestClient()

                    val token = mockOAuth2Server.issueToken(
                        claims = mapOf(
                            "NAVident" to "12345"
                        )
                    )

                    val response = client.post("/api/v1/tilgjengelige-bekreftelser") {
                        bearerAuth(token.serialize())
                        headers {
                            append(HttpHeaders.ContentType, ContentType.Application.Json)
                        }
                        setBody(TilgjengeligeBekreftelserRequest())
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    val body = response.body<ProblemDetails>()
                    body.status shouldBe HttpStatusCode.Forbidden
                    body.type shouldBe "PAW_BRUKER_HAR_IKKE_TILGANG"
                }
            }
        }
    }
})