package no.nav.paw.bekreftelsetjeneste

import arrow.core.toNonEmptyListOrNull
import no.nav.paw.bekreftelse.internehendelser.BekreftelseHendelse
import no.nav.paw.bekreftelse.internehendelser.BekreftelseTilgjengelig
import no.nav.paw.bekreftelse.internehendelser.LeveringsfristUtloept
import no.nav.paw.bekreftelse.internehendelser.RegisterGracePeriodeGjenstaaendeTid
import no.nav.paw.bekreftelse.internehendelser.RegisterGracePeriodeUtloept
import no.nav.paw.bekreftelsetjeneste.tilstand.Bekreftelse
import no.nav.paw.bekreftelsetjeneste.tilstand.InternTilstand
import no.nav.paw.bekreftelsetjeneste.tilstand.Tilstand
import no.nav.paw.bekreftelsetjeneste.tilstand.gjenstaendeGracePeriode
import no.nav.paw.bekreftelsetjeneste.tilstand.initBekreftelsePeriode
import no.nav.paw.bekreftelsetjeneste.tilstand.initNewBekreftelse
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import java.time.Instant
import java.util.*

fun bekreftelsePunctuator(
    stateStoreName: String,
    timestamp: Instant,
    ctx: ProcessorContext<Long, BekreftelseHendelse>
) {
    val stateStore: StateStore = ctx.getStateStore(stateStoreName)

    stateStore.all().use { states ->
        states.forEach { (key, value) ->
            val (updatedState, bekreftelseHendelser) = processBekreftelser(value, timestamp)

            bekreftelseHendelser.forEach {
                ctx.forward(Record(value.periode.recordKey, it, Instant.now().toEpochMilli()))
            }
            stateStore.put(key, updatedState)
        }
    }
}

private fun processBekreftelser(
    currentState: InternTilstand,
    timestamp: Instant,
): Pair<InternTilstand, List<BekreftelseHendelse>> {
    val existingBekreftelse = currentState.bekreftelser.firstOrNull()

    val (tilstand, hendelse) = if (existingBekreftelse == null) {
        currentState.createInitialBekreftelse() to null
    } else {
        currentState.checkAndCreateNewBekreftelse(timestamp)
    }

    val (updatedTilstand, additionalHendelse) = tilstand.handleUpdateBekreftelser(timestamp)

    return updatedTilstand to listOfNotNull(hendelse, additionalHendelse)
}

private fun InternTilstand.createInitialBekreftelse(): InternTilstand =
    copy(bekreftelser = listOf(initBekreftelsePeriode(periode)))

private fun InternTilstand.checkAndCreateNewBekreftelse(
    timestamp: Instant
): Pair<InternTilstand, BekreftelseHendelse?> {
    val nonEmptyBekreftelser = bekreftelser.toNonEmptyListOrNull() ?: return this to null

    return if (nonEmptyBekreftelser.shouldCreateNewBekreftelse(timestamp)) {
        val newBekreftelse = bekreftelser.initNewBekreftelse(tilgjengeliggjort = timestamp)
        copy(bekreftelser = nonEmptyBekreftelser + newBekreftelse) to createNewBekreftelseTilgjengelig(newBekreftelse)
    } else {
        this to null
    }
}

private fun InternTilstand.handleUpdateBekreftelser(
    timestamp: Instant,
): Pair<InternTilstand, BekreftelseHendelse?> {
    val updatedBekreftelser = bekreftelser.map { bekreftelse ->
        generateSequence(bekreftelse to null as BekreftelseHendelse?) { (currentBekreftelse, _) ->
            getProcessedBekreftelseTilstandAndHendelse(currentBekreftelse, timestamp).takeIf { it.second != null }
        }.last().first
    }

    val hendelse: BekreftelseHendelse? = bekreftelser.flatMap { bekreftelse ->
        generateSequence(bekreftelse to null as BekreftelseHendelse?) { (currentBekreftelse, _) ->
            getProcessedBekreftelseTilstandAndHendelse(currentBekreftelse, timestamp).takeIf { it.second != null }
        }.mapNotNull { it.second }
    }.lastOrNull()

    return copy(bekreftelser = updatedBekreftelser) to hendelse
}

private fun InternTilstand.getProcessedBekreftelseTilstandAndHendelse(
    bekreftelse: Bekreftelse,
    timestamp: Instant
): Pair<Bekreftelse, BekreftelseHendelse?> {
    return when {
        bekreftelse.tilstand is Tilstand.IkkeKlarForUtfylling && bekreftelse.erKlarForUtfylling(timestamp) -> {
            val updatedBekreftelse = bekreftelse.copy(tilstand = Tilstand.KlarForUtfylling, tilgjengeliggjort = timestamp)
            val hendelse = BekreftelseTilgjengelig(
                hendelseId = UUID.randomUUID(),
                periodeId = periode.periodeId,
                arbeidssoekerId = periode.arbeidsoekerId,
                bekreftelseId = bekreftelse.bekreftelseId,
                gjelderFra = bekreftelse.gjelderFra,
                gjelderTil = bekreftelse.gjelderTil,
            hendelseTidspunkt = Instant.now())
            updatedBekreftelse to hendelse
        }

        bekreftelse.tilstand is Tilstand.KlarForUtfylling && bekreftelse.harFristUtloept(timestamp) -> {
            val updatedBekreftelse = bekreftelse.copy(tilstand = Tilstand.VenterSvar, fristUtloept = timestamp)
            val hendelse = LeveringsfristUtloept(
                hendelseId = UUID.randomUUID(),
                periodeId = periode.periodeId,
                arbeidssoekerId = periode.arbeidsoekerId,
                bekreftelseId = bekreftelse.bekreftelseId,
            hendelseTidspunkt = Instant.now(),
                    leveringsfrist = bekreftelse.gjelderTil)
            updatedBekreftelse to hendelse
        }

        bekreftelse.tilstand == Tilstand.VenterSvar && bekreftelse.erSisteVarselOmGjenstaaendeGraceTid(timestamp) -> {
            val updatedBekreftelse = bekreftelse.copy(sisteVarselOmGjenstaaendeGraceTid = timestamp)
            val hendelse = RegisterGracePeriodeGjenstaaendeTid(
                hendelseId = UUID.randomUUID(),
                periodeId = periode.periodeId,
                arbeidssoekerId = periode.arbeidsoekerId,
                bekreftelseId = bekreftelse.bekreftelseId,
                gjenstaandeTid = bekreftelse.gjenstaendeGracePeriode(timestamp),
            hendelseTidspunkt = Instant.now())
            updatedBekreftelse to hendelse
        }

        bekreftelse.tilstand == Tilstand.VenterSvar && bekreftelse.harGracePeriodeUtloept(timestamp) -> {
            val updatedBekreftelse = bekreftelse.copy(tilstand = Tilstand.GracePeriodeUtloept)
            val hendelse = RegisterGracePeriodeUtloept(
                hendelseId = UUID.randomUUID(),
                periodeId = periode.periodeId,
                arbeidssoekerId = periode.arbeidsoekerId,
                bekreftelseId = bekreftelse.bekreftelseId,
            hendelseTidspunkt = Instant.now())
            updatedBekreftelse to hendelse
        }

        else -> {
            bekreftelse to null
        }
    }
}

private fun InternTilstand.createNewBekreftelseTilgjengelig(newBekreftelse: Bekreftelse) =
    BekreftelseTilgjengelig(
        hendelseId = UUID.randomUUID(),
        periodeId = periode.periodeId,
        arbeidssoekerId = periode.arbeidsoekerId,
        bekreftelseId = newBekreftelse.bekreftelseId,
        gjelderFra = newBekreftelse.gjelderFra,
        gjelderTil = newBekreftelse.gjelderTil,
        hendelseTidspunkt = Instant.now()
    )

private operator fun <K, V> KeyValue<K, V>.component1(): K = key
private operator fun <K, V> KeyValue<K, V>.component2(): V = value
