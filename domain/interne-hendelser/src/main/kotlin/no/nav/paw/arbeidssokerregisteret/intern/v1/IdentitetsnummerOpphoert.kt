package no.nav.paw.arbeidssokerregisteret.intern.v1

import no.nav.paw.arbeidssokerregisteret.intern.v1.vo.Metadata
import java.util.*

data class IdentitetsnummerOpphoert(
    override val id: Long,
    override val hendelseId: UUID,
    override val identitetsnummer: String,
    override val metadata: Metadata,
    val alleIdentitetsnummer: List<String>
): Hendelse {
    override val hendelseType: String = identitetsnummerOpphoertHendelseType
}