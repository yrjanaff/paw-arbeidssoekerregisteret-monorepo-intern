package no.nav.paw.arbeidssoeker.synk

import no.nav.paw.arbeidssoeker.synk.config.JOB_CONFIG
import no.nav.paw.arbeidssoeker.synk.config.JobConfig
import no.nav.paw.arbeidssoeker.synk.consumer.InngangHttpConsumer
import no.nav.paw.arbeidssoeker.synk.repository.ArbeidssoekerSynkRepository
import no.nav.paw.arbeidssoeker.synk.service.ArbeidssoekerSynkService
import no.nav.paw.arbeidssoeker.synk.utils.flywayMigrate
import no.nav.paw.client.config.AZURE_M2M_CONFIG
import no.nav.paw.client.config.AzureAdM2MConfig
import no.nav.paw.client.factory.createAzureAdM2MTokenClient
import no.nav.paw.config.env.appNameOrDefaultForLocal
import no.nav.paw.config.hoplite.loadNaisOrLocalConfiguration
import no.nav.paw.database.config.DATABASE_CONFIG
import no.nav.paw.database.config.DatabaseConfig
import no.nav.paw.database.factory.createHikariDataSource
import no.nav.paw.logging.logger.buildApplicationLogger
import org.jetbrains.exposed.sql.Database
import java.nio.file.Paths

fun main() {
    val logger = buildApplicationLogger

    val jobConfig = loadNaisOrLocalConfiguration<JobConfig>(JOB_CONFIG)
    val databaseConfig = loadNaisOrLocalConfiguration<DatabaseConfig>(DATABASE_CONFIG)
    val azureAdM2MConfig = loadNaisOrLocalConfiguration<AzureAdM2MConfig>(AZURE_M2M_CONFIG)
    val name = jobConfig.runtimeEnvironment.appNameOrDefaultForLocal(default = "local-job")

    logger.info("Initialiserer $name")

    val dataSource = createHikariDataSource(databaseConfig)
    dataSource.flywayMigrate()
    Database.connect(dataSource)
    val azureAdM2MTokenClient = createAzureAdM2MTokenClient(jobConfig.runtimeEnvironment, azureAdM2MConfig)
    val arbeidssoekerSynkRepository = ArbeidssoekerSynkRepository()
    val inngangHttpConsumer = InngangHttpConsumer(jobConfig.apiInngangBaseUrl) {
        azureAdM2MTokenClient.createMachineToMachineToken(jobConfig.apiInngangScope)
    }
    val arbeidssoekerSynkService = ArbeidssoekerSynkService(jobConfig, arbeidssoekerSynkRepository, inngangHttpConsumer)

    try {
        logger.info("Starter $name")
        arbeidssoekerSynkService.synkArbeidssoekere(Paths.get(jobConfig.syncFilePath))
    } catch (throwable: Throwable) {
        logger.error("Kjøring feilet", throwable)
    } finally {
        logger.info("Avslutter $name")
    }
}
