package no.nav.paw.arbeidssokerregisteret.application

import arrow.core.Either
import arrow.core.NonEmptyList
import io.opentelemetry.instrumentation.annotations.WithSpan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.paw.arbeidssokerregisteret.RequestScope
import no.nav.paw.arbeidssokerregisteret.domain.Identitetsnummer
import no.nav.paw.arbeidssokerregisteret.intern.v1.Hendelse
import no.nav.paw.kafka.producer.sendDeferred
import no.nav.paw.kafkakeygenerator.client.KafkaKeysClient
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class StartStoppRequestHandler(
    private val hendelseTopic: String,
    private val requestValidator: RequestValidator,
    private val producer: Producer<Long, Hendelse>,
    private val kafkaKeysClient: KafkaKeysClient
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @WithSpan
    suspend fun startArbeidssokerperiode(requestScope: RequestScope, identitetsnummer: Identitetsnummer, erForhaandsGodkjentAvVeileder: Boolean): Either<NonEmptyList<Problem>, GrunnlagForGodkjenning> =
        coroutineScope {
            val kafkaKeysResponse = async { kafkaKeysClient.getIdAndKey(identitetsnummer.verdi) }
            val resultat = requestValidator.validerStartAvPeriodeOenske(requestScope, identitetsnummer, erForhaandsGodkjentAvVeileder)
            val (id, key) = kafkaKeysResponse.await()
            val hendelse = somHendelse(requestScope, id, identitetsnummer, resultat)
            val record = ProducerRecord(
                hendelseTopic,
                key,
                hendelse
            )
            producer.sendDeferred(record).await()
            resultat
        }

    @WithSpan
    suspend fun avsluttArbeidssokerperiode(
        requestScope: RequestScope,
        identitetsnummer: Identitetsnummer,
        feilretting: Feilretting?
    ): Either<NonEmptyList<Problem>, GrunnlagForGodkjenning> {
        val (id, key) = kafkaKeysClient.getIdAndKey(identitetsnummer.verdi)
        val tilgangskontrollResultat = requestValidator.validerTilgang(
            requestScope = requestScope,
            identitetsnummer = identitetsnummer,
            feilretting = feilretting
        )
        val hendelse = stoppResultatSomHendelse(
            requestScope = requestScope,
            id = id,
            identitetsnummer = identitetsnummer,
            resultat = tilgangskontrollResultat,
            feilretting = feilretting
        )
        val record = ProducerRecord(
            hendelseTopic,
            key,
            hendelse
        )
        val recordMetadata = producer.sendDeferred(record).await()
        logger.trace("Sendte melding til kafka: type={}, offset={}", hendelse.hendelseType, recordMetadata.offset())
        return tilgangskontrollResultat
    }

    suspend fun kanRegistreresSomArbeidssoker(requestScope: RequestScope, identitetsnummer: Identitetsnummer): Either<NonEmptyList<Problem>, GrunnlagForGodkjenning> {
        val (id, key) = kafkaKeysClient.getIdAndKey(identitetsnummer.verdi)
        val resultat = requestValidator.validerStartAvPeriodeOenske(requestScope, identitetsnummer)
        if (resultat.isLeft()) {
            val hendelse = somHendelse(requestScope, id, identitetsnummer, resultat)
            val record = ProducerRecord(
                hendelseTopic,
                key,
                hendelse
            )
            val recordMetadata = producer.sendDeferred(record).await()
            logger.trace("Sendte melding til kafka: type={}, offset={}", hendelse.hendelseType, recordMetadata.offset())
        }
        return resultat
    }
}
