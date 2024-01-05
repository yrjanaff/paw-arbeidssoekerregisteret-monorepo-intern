package no.nav.paw.arbeidssokerregisteret.evaluering

enum class Evaluation {
    SAMME_SOM_INNLOGGER_BRUKER,
    IKKE_SAMME_SOM_INNLOGGER_BRUKER,
    ANSATT_IKKE_TILGANG,
    ANSATT_TILGANG,
    IKKE_ANSATT,
    ER_OVER_18_AAR,
    ER_UNDER_18_AAR,
    UKJENT_FOEDSELSDATO,
    UKJENT_FOEDSELSAAR,
    TOKENX_PID_IKKE_FUNNET,
    OPPHOERT_IDENTITET,
    IKKE_BOSATT,
    DOED,
    SAVNET,
    HAR_NORSK_ADRESSE,
    HAR_UTENLANDSK_ADRESSE,
    INGEN_ADRESSE_FUNNET,
    BOSATT_ETTER_FREG_LOVEN,
    DNUMMER,
    UKJENT_FORENKLET_FREG_STATUS,
    HAR_GYLDIG_OPPHOLDSTILLATELSE,
    OPPHOLDSTILATELSE_UTGAATT,
    BARN_FOEDT_I_NORGE_UTEN_OPPHOLDSTILLATELSE,
    INGEN_INFORMASJON_OM_OPPHOLDSTILLATELSE,
    UKJENT_STATUS_FOR_OPPHOLDSTILLATELSE,
    PERSON_IKKE_FUNNET,
    SISTE_FLYTTING_VAR_UT_AV_NORGE,
    SISTE_FLYTTING_VAR_INN_TIL_NORGE,
    IKKE_MULIG_AA_IDENTIFISERE_SISTE_FLYTTING,
    INGEN_FLYTTE_INFORMASJON
}

fun <R: Any> haandterResultat(
    regler: Map<String, List<Evaluation>>,
    resultat: Set<Evaluation>,
    transformasjon: (String, List<Evaluation>) -> R
): List<R> =
    regler.filter { (_, evalueringer) ->
        evalueringer.all { resultat.contains(it) }
    }.map { (melding, evalueringer) ->
        transformasjon(melding, evalueringer)
    }

