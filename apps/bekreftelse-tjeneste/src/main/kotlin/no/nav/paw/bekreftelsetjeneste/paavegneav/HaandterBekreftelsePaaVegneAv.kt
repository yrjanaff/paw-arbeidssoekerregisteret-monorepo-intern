package no.nav.paw.bekreftelsetjeneste.paavegneav

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.instrumentation.annotations.WithSpan
import no.nav.paw.bekreftelse.paavegneav.v1.PaaVegneAv
import no.nav.paw.bekreftelse.paavegneav.v1.vo.Start
import no.nav.paw.bekreftelse.internehendelser.BekreftelsePaaVegneAvStartet
import no.nav.paw.bekreftelse.internehendelser.BekreftelseHendelse
import no.nav.paw.bekreftelse.paavegneav.v1.vo.Stopp
import no.nav.paw.bekreftelsetjeneste.tilstand.*
import no.nav.paw.bekreftelsetjeneste.topology.bekreftelseloesingKey
import no.nav.paw.bekreftelsetjeneste.topology.forespoerselTypeKey
import no.nav.paw.bekreftelsetjeneste.topology.harAnsvarKey
import no.nav.paw.bekreftelsetjeneste.topology.paaVegneAvMottattFeil
import no.nav.paw.bekreftelsetjeneste.topology.paaVegneAvMottattOk
import no.nav.paw.bekreftelsetjeneste.topology.periodeFunnetKey
import java.time.Duration
import java.time.Instant
import java.util.*

@JvmInline
value class WallClock(val value: Instant)

@WithSpan(
    value = "haandter_bekreftelse_paa_vegne_av_endret",
    kind = SpanKind.INTERNAL
)
fun haandterBekreftelsePaaVegneAvEndret(
    wallclock: WallClock,
    bekreftelseTilstand: BekreftelseTilstand?,
    paaVegneAvTilstand: PaaVegneAvTilstand?,
    paaVegneAvHendelse: PaaVegneAv
): List<Handling> {
    return when (val handling = paaVegneAvHendelse.handling) {
        is Start -> startPaaVegneAv(
            wallclock = wallclock,
            bekreftelseTilstand = bekreftelseTilstand,
            paaVegneAvTilstand = paaVegneAvTilstand,
            paaVegneAvHendelse = paaVegneAvHendelse,
            handling = handling
        )

        is Stopp -> stoppPaaVegneAv(
            paaVegneAvTilstand = paaVegneAvTilstand,
            paaVegneAvHendelse = paaVegneAvHendelse
        )

        else -> emptyList()
    }.also { handlinger ->
        val hendelse = handlinger
            .filterIsInstance<SendHendelse>()
            .firstOrNull()
            ?.hendelse
        val forespoerselType = when (paaVegneAvHendelse.handling) {
            is Start -> "startet"
            is Stopp -> "stoppet"
            else -> "ukjent"
        }
        val attributes = Attributes.of(
            forespoerselTypeKey, forespoerselType,
            bekreftelseloesingKey, paaVegneAvHendelse.bekreftelsesloesning.name,
            periodeFunnetKey, bekreftelseTilstand != null,
            harAnsvarKey, paaVegneAvTilstand?.paaVegneAvList
                ?.map { it.loesning }
                ?.contains(Loesning.from(paaVegneAvHendelse.bekreftelsesloesning)) ?: false
        )
        if (hendelse == null) {
            Span.current().addEvent(
                paaVegneAvMottattFeil, attributes
            )
            Span.current()
                .setStatus(StatusCode.ERROR, "ingen endring utført, se trace 'event' '$paaVegneAvMottattFeil' for detaljer")
        } else {
            with(Span.current()) {
                Span.current().addEvent(paaVegneAvMottattOk, attributes)
                setStatus(StatusCode.OK, "endring utført")
            }
        }
    }
}

fun stoppPaaVegneAv(
    paaVegneAvTilstand: PaaVegneAvTilstand?,
    paaVegneAvHendelse: PaaVegneAv
): List<Handling> {
    val oppdatertPaaVegneAv = paaVegneAvTilstand - Loesning.from(paaVegneAvHendelse.bekreftelsesloesning)
    val paaVegneAvHandling = when {
        paaVegneAvTilstand != null && oppdatertPaaVegneAv == null -> SlettPaaVegneAvTilstand(paaVegneAvHendelse.periodeId)
        paaVegneAvTilstand != null && oppdatertPaaVegneAv != null -> SkrivPaaVegneAvTilstand(
            paaVegneAvHendelse.periodeId,
            oppdatertPaaVegneAv
        )

        else -> null
    }
    return listOfNotNull(paaVegneAvHandling)
}

fun startPaaVegneAv(
    wallclock: WallClock,
    bekreftelseTilstand: BekreftelseTilstand?,
    paaVegneAvTilstand: PaaVegneAvTilstand?,
    paaVegneAvHendelse: PaaVegneAv,
    handling: Start
): List<Handling> {
    val oppdatertPaaVegneAvTilstand =
        (paaVegneAvTilstand ?: opprettPaaVegneAvTilstand(paaVegneAvHendelse.periodeId)) +
                InternPaaVegneAv(
                    loesning = Loesning.from(paaVegneAvHendelse.bekreftelsesloesning),
                    intervall = Duration.ofMillis(handling.intervalMS),
                    gracePeriode = Duration.ofMillis(handling.graceMS)
                )
    val hendelse = bekreftelseTilstand?.let {
        BekreftelsePaaVegneAvStartet(
            hendelseId = UUID.randomUUID(),
            periodeId = paaVegneAvHendelse.periodeId,
            arbeidssoekerId = bekreftelseTilstand.periode.arbeidsoekerId,
            hendelseTidspunkt = wallclock.value,
        )
    }

    val oppdaterBekreftelseTilstand = bekreftelseTilstand?.let {
        val oppdaterteBekreftelser = it.bekreftelser
            .map { bekreftelse ->
                when (bekreftelse.sisteTilstand()) {
                    is VenterSvar,
                    is KlarForUtfylling,
                    is GracePeriodeVarselet,
                    is IkkeKlarForUtfylling -> bekreftelse + InternBekreftelsePaaVegneAvStartet(wallclock.value)

                    else -> bekreftelse
                }
            }
        it.copy(bekreftelser = oppdaterteBekreftelser)
    }
        ?.takeIf { oppdatertBekreftelseTilstand -> oppdatertBekreftelseTilstand != bekreftelseTilstand }
        ?.let { oppdatertBekreftelseTilstand ->
            SkrivBekreftelseTilstand(
                oppdatertBekreftelseTilstand.periode.periodeId,
                oppdatertBekreftelseTilstand
            )
        }

    return listOfNotNull(
        if (paaVegneAvTilstand != oppdatertPaaVegneAvTilstand) SkrivPaaVegneAvTilstand(
            paaVegneAvHendelse.periodeId,
            oppdatertPaaVegneAvTilstand
        ) else null,
        oppdaterBekreftelseTilstand,
        hendelse?.let(::SendHendelse)
    )
}


sealed interface Handling
data class SlettPaaVegneAvTilstand(val id: UUID) : Handling
data class SkrivPaaVegneAvTilstand(val id: UUID, val value: PaaVegneAvTilstand) : Handling
data class SkrivBekreftelseTilstand(val id: UUID, val value: BekreftelseTilstand) : Handling
data class SendHendelse(val hendelse: BekreftelseHendelse) : Handling
