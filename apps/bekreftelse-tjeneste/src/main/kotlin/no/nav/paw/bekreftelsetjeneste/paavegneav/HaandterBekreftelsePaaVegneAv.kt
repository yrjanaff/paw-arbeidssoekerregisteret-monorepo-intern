package no.nav.paw.bekreftelsetjeneste.paavegneav

import no.nav.paw.bekreftelse.internehendelser.BekreftelseHendelse
import no.nav.paw.bekreftelse.internehendelser.BekreftelsePaaVegneAvStartet
import no.nav.paw.bekreftelse.paavegneav.v1.PaaVegneAv
import no.nav.paw.bekreftelse.paavegneav.v1.vo.Start
import no.nav.paw.bekreftelse.paavegneav.v1.vo.Stopp
import no.nav.paw.bekreftelsetjeneste.tilstand.BekreftelseTilstand
import no.nav.paw.bekreftelsetjeneste.tilstand.GracePeriodeVarselet
import no.nav.paw.bekreftelsetjeneste.tilstand.IkkeKlarForUtfylling
import no.nav.paw.bekreftelsetjeneste.tilstand.InternBekreftelsePaaVegneAvStartet
import no.nav.paw.bekreftelsetjeneste.tilstand.KlarForUtfylling
import no.nav.paw.bekreftelsetjeneste.tilstand.VenterSvar
import no.nav.paw.bekreftelsetjeneste.tilstand.plus
import no.nav.paw.bekreftelsetjeneste.tilstand.sisteTilstand
import no.nav.paw.bekreftelsetjeneste.topology.Feil
import no.nav.paw.bekreftelsetjeneste.topology.log
import no.nav.paw.bekreftelsetjeneste.topology.logWarning
import no.nav.paw.bekreftelsetjeneste.topology.paaVegneAvStartet
import no.nav.paw.bekreftelsetjeneste.topology.paaVegneAvStoppet
import java.time.Duration
import java.time.Instant
import java.util.*

@JvmInline
value class WallClock(val value: Instant)

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
    }.also { _ ->
        val action = when (paaVegneAvHendelse.handling) {
            is Start -> paaVegneAvStartet
            is Stopp -> paaVegneAvStoppet
            else -> "ukjent"
        }
        val periodeFunnet = bekreftelseTilstand != null
        val harAnsvar = paaVegneAvTilstand?.paaVegneAvList
            ?.map { it.loesning }
            ?.contains(Loesning.from(paaVegneAvHendelse.bekreftelsesloesning)) ?: false
        if (!periodeFunnet) {
            logWarning(
                loesning = Loesning.from(paaVegneAvHendelse.bekreftelsesloesning),
                handling = action,
                feil = Feil.PERIODE_IKKE_FUNNET,
                harAnsvar = harAnsvar
            )
        } else {
            log(
                loesning = Loesning.from(paaVegneAvHendelse.bekreftelsesloesning),
                handling = action,
                periodeFunnet = periodeFunnet,
                harAnsvar = harAnsvar
            )
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
