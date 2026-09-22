package se.norrsken.tv.data.epg

import se.norrsken.tv.data.db.entity.ChannelEntity
import se.norrsken.tv.data.db.entity.EpgMappingEntity
import se.norrsken.tv.data.db.entity.MappingSource
import se.norrsken.tv.data.playlist.normalizeChannelName

/** En kanal som tablåfilen själv deklarerar i ett `<channel>`-element. */
data class XmltvChannel(
    val id: String,
    val names: List<String>,
)

/**
 * Kopplar kanaler till tablåns kanaler.
 *
 * Regeln: hellre ingen EPG än fel EPG. Fel tablå är värre än ingen — man
 * sätter på "Nyheterna" och får en matlagningsshow. Därför ingen fuzzy
 * matchning, och varje namnträff måste vara entydig: finns "SVT1" två gånger
 * i tablån (två regioner) kopplas ingen av dem på namn.
 *
 * Ordning:
 * 1. tvg-id som tablån deklarerar — leverantörens uttryckliga koppling.
 * 2. Exakt namn, entydigt.
 * 3. Normaliserat namn ("SVT1 HD" = "SVT 1"), entydigt.
 * 4. tvg-id som tablån inte deklarerar. Det är fortfarande leverantörens
 *    uttryckliga koppling och kan inte ge fel program — bara inga.
 *
 * Kanaler med manuell koppling rörs inte; de skickas inte hit.
 */
fun matchChannels(
    channels: List<ChannelEntity>,
    xmltv: List<XmltvChannel>,
): List<EpgMappingEntity> {
    val declaredIds = xmltv.associateBy { it.id.trim().lowercase() }

    val byExactName = xmltv
        .flatMap { channel -> channel.names.map { it.trim().lowercase() to channel.id } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.distinct() }

    val byNormalizedName = xmltv
        .flatMap { channel -> channel.names.map { normalizeChannelName(it) to channel.id } }
        .filter { it.first.isNotEmpty() }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.distinct() }

    return channels.mapNotNull { channel ->
        val tvgId = channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }

        tvgId?.let { declaredIds[it.lowercase()] }?.let {
            return@mapNotNull mapping(channel, it.id, MappingSource.TVG_ID, 100)
        }

        listOfNotNull(channel.name, channel.tvgName)
            .firstNotNullOfOrNull { name ->
                byExactName[name.trim().lowercase()]?.singleOrNull()
            }
            ?.let { return@mapNotNull mapping(channel, it, MappingSource.EXACT_NAME, 90) }

        listOfNotNull(channel.name, channel.tvgName)
            .firstNotNullOfOrNull { name ->
                byNormalizedName[normalizeChannelName(name)]?.singleOrNull()
            }
            ?.let { return@mapNotNull mapping(channel, it, MappingSource.NORMALIZED_NAME, 80) }

        tvgId?.let { mapping(channel, it, MappingSource.TVG_ID, 70) }
    }
}

private fun mapping(
    channel: ChannelEntity,
    epgChannelId: String,
    source: MappingSource,
    confidence: Int,
) = EpgMappingEntity(
    channelId = channel.id,
    epgChannelId = epgChannelId,
    source = source,
    confidence = confidence,
)
