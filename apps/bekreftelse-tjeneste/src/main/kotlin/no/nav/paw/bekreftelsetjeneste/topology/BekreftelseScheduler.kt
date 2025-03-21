package no.nav.paw.bekreftelsetjeneste.topology

import arrow.core.andThen
import no.nav.paw.bekreftelse.internehendelser.BekreftelseHendelse
import no.nav.paw.bekreftelse.internehendelser.BekreftelseTilgjengelig
import no.nav.paw.bekreftelse.internehendelser.LeveringsfristUtloept
import no.nav.paw.bekreftelse.internehendelser.RegisterGracePeriodeGjenstaaendeTid
import no.nav.paw.bekreftelse.internehendelser.RegisterGracePeriodeUtloept
import no.nav.paw.bekreftelsetjeneste.config.BekreftelseKonfigurasjon
import no.nav.paw.bekreftelsetjeneste.config.tidligsteStartUke
import no.nav.paw.bekreftelsetjeneste.paavegneav.WallClock
import no.nav.paw.bekreftelsetjeneste.startdatohaandtering.OddetallPartallMap
import no.nav.paw.bekreftelsetjeneste.startdatohaandtering.Oddetallsuke
import no.nav.paw.bekreftelsetjeneste.startdatohaandtering.Partallsuke
import no.nav.paw.bekreftelsetjeneste.startdatohaandtering.Ukenummer
import no.nav.paw.bekreftelsetjeneste.startdatohaandtering.Ukjent
import no.nav.paw.bekreftelsetjeneste.tilstand.Bekreftelse
import no.nav.paw.bekreftelsetjeneste.tilstand.BekreftelseTilstand
import no.nav.paw.bekreftelsetjeneste.tilstand.BekreftelseTilstandsLogg
import no.nav.paw.bekreftelsetjeneste.tilstand.GracePeriodeUtloept
import no.nav.paw.bekreftelsetjeneste.tilstand.GracePeriodeVarselet
import no.nav.paw.bekreftelsetjeneste.tilstand.IkkeKlarForUtfylling
import no.nav.paw.bekreftelsetjeneste.tilstand.KlarForUtfylling
import no.nav.paw.bekreftelsetjeneste.tilstand.PeriodeInfo
import no.nav.paw.bekreftelsetjeneste.tilstand.VenterPaaSvar
import no.nav.paw.bekreftelsetjeneste.tilstand.VenterSvar
import no.nav.paw.bekreftelsetjeneste.tilstand.erKlarForUtfylling
import no.nav.paw.bekreftelsetjeneste.tilstand.erSisteVarselOmGjenstaaendeGraceTid
import no.nav.paw.bekreftelsetjeneste.tilstand.gjenstaendeGraceperiode
import no.nav.paw.bekreftelsetjeneste.tilstand.harFristUtloept
import no.nav.paw.bekreftelsetjeneste.tilstand.harGraceperiodeUtloept
import no.nav.paw.bekreftelsetjeneste.tilstand.plus
import no.nav.paw.bekreftelsetjeneste.tilstand.sisteTilstand
import no.nav.paw.bekreftelsetjeneste.tilstand.sluttTidForBekreftelsePeriode
import no.nav.paw.collections.PawNonEmptyList
import no.nav.paw.collections.pawNonEmptyListOf
import no.nav.paw.model.Identitetsnummer
import java.time.Instant
import java.time.ZoneId
import java.util.*

private val tidssone = ZoneId.of("Europe/Oslo")

class BekreftelseContext(
    val konfigurasjon: BekreftelseKonfigurasjon,
    val wallClock: WallClock,
    val periodeInfo: PeriodeInfo,
    private val oddetallPartallMap: OddetallPartallMap
) {
    val identitetsnummer = Identitetsnummer(periodeInfo.identitetsnummer)

    operator fun get(identitetsnummer: Identitetsnummer): Ukenummer = oddetallPartallMap[identitetsnummer]

    fun tidligsteBekreftelsePeriodeStart(): Instant {
        if (periodeInfo.startet.isAfter(
                konfigurasjon.tidligsteBekreftelsePeriodeStart.atStartOfDay(tidssone).toInstant()
            )
        ) {
            return periodeInfo.startet
        }
        val partallOddetall = this[identitetsnummer]
        val tidligsteStartUke = when {
            konfigurasjon.tidligsteStartUke % 2 == 0 -> Partallsuke
            else -> Oddetallsuke
        }
        return if (partallOddetall == tidligsteStartUke || partallOddetall is Ukjent) {
            konfigurasjon.tidligsteBekreftelsePeriodeStart.atStartOfDay(tidssone).toInstant()
        } else {
            konfigurasjon.tidligsteBekreftelsePeriodeStart.plusWeeks(1).atStartOfDay(tidssone).toInstant()
        }
    }
}

data class BekreftelseProsesseringsResultat(
    val oppdatertTilstand: BekreftelseTilstand,
    val hendelser: List<BekreftelseHendelse>
)

fun BekreftelseContext.prosesser(bekreftelseTilstand: BekreftelseTilstand): BekreftelseProsesseringsResultat =
    (::opprettInitielBekreftelse andThen
            ::opprettManglendeBekreftelser andThen
            ::oppdaterBekreftelser andThen
            { (bekreftelser, hendelser) ->
                BekreftelseProsesseringsResultat(
                    oppdatertTilstand = bekreftelseTilstand.copy(bekreftelser = bekreftelser.toList()),
                    hendelser = hendelser
                )
            })(bekreftelseTilstand.bekreftelser)


fun BekreftelseContext.opprettInitielBekreftelse(bekreftelser: List<Bekreftelse>): PawNonEmptyList<Bekreftelse> {
    val foerste = bekreftelser.firstOrNull()
    return if (foerste != null) {
        pawNonEmptyListOf(foerste, bekreftelser.drop(1))
    } else {
        val fra = tidligsteBekreftelsePeriodeStart()
        val til = sluttTidForBekreftelsePeriode(
            startTid = fra,
            interval = konfigurasjon.interval
        )
        pawNonEmptyListOf(
            Bekreftelse(
                bekreftelseId = UUID.randomUUID(),
                tilstandsLogg = BekreftelseTilstandsLogg(
                    siste = IkkeKlarForUtfylling(wallClock.value),
                    tidligere = emptyList()
                ),
                gjelderFra = fra,
                gjelderTil = til,
            )
        )
    }
}

fun BekreftelseContext.opprettManglendeBekreftelser(bekreftelser: PawNonEmptyList<Bekreftelse>): PawNonEmptyList<Bekreftelse> {
    val siste = bekreftelser.maxBy { it.gjelderTil }
    val ventende = bekreftelser.toList().filter { it.sisteTilstand() is VenterPaaSvar }
    return if (siste.gjelderTil.isBefore(wallClock.value) && ventende.size < konfigurasjon.maksAntallVentendeBekreftelser) {
        val neste = Bekreftelse(
            bekreftelseId = UUID.randomUUID(),
            gjelderFra = siste.gjelderTil,
            gjelderTil = sluttTidForBekreftelsePeriode(siste.gjelderTil, konfigurasjon.interval),
            tilstandsLogg = BekreftelseTilstandsLogg(
                siste = IkkeKlarForUtfylling(wallClock.value),
                tidligere = emptyList()
            )
        )
        bekreftelser + neste
    } else {
        bekreftelser
    }
}

fun BekreftelseContext.oppdaterBekreftelser(bekreftelser: PawNonEmptyList<Bekreftelse>): Pair<PawNonEmptyList<Bekreftelse>, List<BekreftelseHendelse>> =
    bekreftelser.map { bekreftelse ->
        when {
            bekreftelse.erKlarForUtfylling(wallClock.value, konfigurasjon.tilgjengeligOffset) -> {
                bekreftelse.plus(KlarForUtfylling(wallClock.value)) to BekreftelseTilgjengelig(
                    hendelseId = UUID.randomUUID(),
                    periodeId = periodeInfo.periodeId,
                    arbeidssoekerId = periodeInfo.arbeidsoekerId,
                    hendelseTidspunkt = wallClock.value,
                    bekreftelseId = bekreftelse.bekreftelseId,
                    gjelderFra = bekreftelse.gjelderFra,
                    gjelderTil = bekreftelse.gjelderTil
                )
            }

            bekreftelse.harFristUtloept(wallClock.value) -> {
                bekreftelse.plus(VenterSvar(wallClock.value)) to LeveringsfristUtloept(
                    hendelseId = UUID.randomUUID(),
                    periodeId = periodeInfo.periodeId,
                    arbeidssoekerId = periodeInfo.arbeidsoekerId,
                    hendelseTidspunkt = wallClock.value,
                    bekreftelseId = bekreftelse.bekreftelseId,
                    leveringsfrist = bekreftelse.gjelderTil
                )
            }

            bekreftelse.erSisteVarselOmGjenstaaendeGraceTid(
                wallClock.value,
                konfigurasjon.varselFoerGraceperiodeUtloept
            ) -> {
                bekreftelse.plus(GracePeriodeVarselet(wallClock.value)) to RegisterGracePeriodeGjenstaaendeTid(
                    hendelseId = UUID.randomUUID(),
                    periodeId = periodeInfo.periodeId,
                    arbeidssoekerId = periodeInfo.arbeidsoekerId,
                    hendelseTidspunkt = wallClock.value,
                    bekreftelseId = bekreftelse.bekreftelseId,
                    gjenstaandeTid = bekreftelse.gjenstaendeGraceperiode(
                        wallClock.value,
                        konfigurasjon.graceperiode
                    )
                )
            }

            bekreftelse.harGraceperiodeUtloept(wallClock.value, konfigurasjon.graceperiode) -> {
                bekreftelse.plus(GracePeriodeUtloept(wallClock.value)) to RegisterGracePeriodeUtloept(
                    hendelseId = UUID.randomUUID(),
                    periodeId = periodeInfo.periodeId,
                    arbeidssoekerId = periodeInfo.arbeidsoekerId,
                    hendelseTidspunkt = wallClock.value,
                    bekreftelseId = bekreftelse.bekreftelseId
                )
            }

            else -> bekreftelse to null
        }
    }.let { bekreftelserOgHendelser ->
        val oppdaterteBekreftelser = bekreftelserOgHendelser.map { it.first }
        val hendelser = bekreftelserOgHendelser.toList().mapNotNull { it.second }
        oppdaterteBekreftelser to hendelser
    }

