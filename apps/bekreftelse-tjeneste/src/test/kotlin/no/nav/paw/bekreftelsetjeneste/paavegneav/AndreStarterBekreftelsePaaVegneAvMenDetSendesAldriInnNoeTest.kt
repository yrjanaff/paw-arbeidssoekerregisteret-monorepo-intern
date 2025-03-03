package no.nav.paw.bekreftelsetjeneste.paavegneav

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import no.nav.paw.arbeidssoekerregisteret.testdata.bekreftelse.stoppPaaVegneAv
import no.nav.paw.arbeidssoekerregisteret.testdata.bekreftelse.startPaaVegneAv
import no.nav.paw.arbeidssoekerregisteret.testdata.kafkaKeyContext
import no.nav.paw.arbeidssoekerregisteret.testdata.mainavro.periode
import no.nav.paw.bekreftelse.internehendelser.BekreftelsePaaVegneAvStartet
import no.nav.paw.bekreftelse.internehendelser.BekreftelseTilgjengelig
import no.nav.paw.bekreftelse.internehendelser.RegisterGracePeriodeUtloeptEtterEksternInnsamling
import no.nav.paw.bekreftelsetjeneste.ApplicationTestContext
import no.nav.paw.test.assertEvent
import no.nav.paw.test.assertNoMessage
import no.nav.paw.test.days

class AndreStarterBekreftelsePaaVegneAvMenDetSendesAldriInnNoeTest: FreeSpec({
    with(ApplicationTestContext()) {
        val intervall = bekreftelseKonfigurasjon.interval
        val grace = bekreftelseKonfigurasjon.graceperiode
        with(kafkaKeyContext()) {
            "Applikasjonstest hvor noen starter bekreftelsePaaVegneAv rett etter at perioden er lest, men stopper igjen før en eneste" +
                    " bekreftelse er levert" - {
                val (id, key, periode) = periode(identitetsnummer = "12345678902")
                "Når perioden opprettes skal det ikke skje noe" {
                    periodeTopic.pipeInput(key, periode)
                    bekreftelseHendelseloggTopicOut.assertNoMessage()
                }
                "Når andre starter bekreftelsePaaVegneAv sendes en 'BekreftelsePaaVegneAvStartet' hendelse" {
                    val startPaaVegneAv = startPaaVegneAv(periodeId = periode.id)
                    bekreftelsePaaVegneAvTopic.pipeInput(key, startPaaVegneAv)
                    logger.info("startPaaVegneAv: $startPaaVegneAv")
                    bekreftelseHendelseloggTopicOut.assertEvent { hendelse: BekreftelsePaaVegneAvStartet ->
                        hendelse.periodeId shouldBe periode.id
                        hendelse.arbeidssoekerId shouldBe id
                    }
                    bekreftelseHendelseloggTopicOut.assertNoMessage()
                }
                "Når leveringsfristen utløper når andre har bekreftelsePaaVegneAv skjer det ingenting" {
                    testDriver.advanceWallClockTime(intervall + 1.days)
                    bekreftelseHendelseloggTopicOut.assertNoMessage()
                }
                "Når grace perioden utløpet når andre har bekreftelsePaaVegneAv skjer det ingenting" {
                    testDriver.advanceWallClockTime(grace + 1.days)
                    bekreftelseHendelseloggTopicOut.assertNoMessage()
                }
                "Når andre stopper bekreftelsePaaVegneAv og det er over inervall + grace siden sist leverte intervall sluttet det sendes ut RegisterGracePeriodeUtloeptEtterEksternInnsamling" {
                    bekreftelsePaaVegneAvTopic.pipeInput(key, stoppPaaVegneAv(periodeId = periode.id))
                    testDriver.advanceWallClockTime(1.days)
                    bekreftelseHendelseloggTopicOut.assertEvent { hendelse: RegisterGracePeriodeUtloeptEtterEksternInnsamling ->
                        hendelse.periodeId shouldBe periode.id
                    }
                }
            }
        }
    }
})