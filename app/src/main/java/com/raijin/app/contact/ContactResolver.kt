package com.raijin.app.contact

import com.raijin.app.permissions.PermissionBroker
import io.raijin.brain.ContactScorer

/**
 * CT-6: device-backed [ContactResolverPort] implementation.
 *
 * Candidates come from two sources, merged and ranked with the brain-core
 * phonetic scorer (the same pipeline the CT-9 golden eval measures):
 *   - the device address book via [PermissionBroker.queryAllContacts]
 *   - learned contact entities (with their aliases) via [ContactEntityDao]
 *
 * The CT-6 confidence bands map onto [ResolveResult]:
 *   PROCEED -> [ResolveResult.Single], ASK -> [ResolveResult.Multiple]
 *   (up to 4 candidates), MISS -> [ResolveResult.NotFound].
 */
class ContactResolver(
    private val permissionBroker: PermissionBroker,
    private val contactDao: ContactEntityDao,
    private val correctionDao: CorrectionEntryDao? = null,
) : ContactResolverPort {

    override suspend fun resolve(query: String): ResolveResult = resolveAlternatives(listOf(query))

    override suspend fun resolveAlternatives(queries: List<String>): ResolveResult {
        val query = queries.firstOrNull().orEmpty()
        // CT-2: relationship terms ("maa"/"mom"/"मम्मी") are equivalent on the
        // query side, so any synonym reaches a learned alias.
        val allQueries = queries.flatMap { io.raijin.brain.RelationshipAliases.expand(it) }.distinct()
        val entries = mutableListOf<ContactScorer.Entry>()
        val entitiesByRef = mutableMapOf<String, ContactEntity>()

        // Device contacts: transient entities keyed by phone number.
        for (c in permissionBroker.queryAllContacts()) {
            val ref = "device:${c.phoneNumber}"
            entries += ContactScorer.Entry(contactId = ref, displayName = c.displayName)
            entitiesByRef[ref] = ContactEntity(
                id = 0,
                canonicalName = c.displayName,
                displayName = c.displayName,
                aliases = "",
                phoneNumber = c.phoneNumber,
                group = c.group.name,
            )
        }

        // Learned entities carry aliases, so alias queries resolve here.
        for (e in contactDao.findAll()) {
            val ref = "entity:${e.id}"
            entries += ContactScorer.Entry(
                contactId = ref,
                displayName = e.displayName,
                aliases = e.aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
            entitiesByRef[ref] = e
        }

        if (entries.isEmpty()) return ResolveResult.NotFound

        val ranking = ContactScorer.rankEntries(entries, allQueries)
            .distinctBy { entitiesByRef.getValue(it.contactId).phoneNumber }

        // CT-7: contacts the owner has corrected for any of these spoken
        // forms are deprioritized; they only come back when nothing else
        // matches at all.
        val correctedPhones: Set<String> = correctionDao?.let { dao ->
            allQueries.flatMap { q -> runCatching { dao.findByWrongAlias(q) }.getOrDefault(emptyList()) }
                .mapNotNull { entry -> contactDao.findById(entry.contactId)?.phoneNumber }
                .toSet()
        }.orEmpty()
        val uncorrected = ranking.filter {
            entitiesByRef.getValue(it.contactId).phoneNumber !in correctedPhones
        }
        val effective = if (correctedPhones.isNotEmpty() && uncorrected.isNotEmpty()) uncorrected else ranking

        return when (ContactScorer.confidenceOf(effective)) {
            ContactScorer.Confidence.PROCEED -> {
                val top = effective.first()
                ResolveResult.Single(
                    entity = entitiesByRef.getValue(top.contactId),
                    confidence = top.score.toFloat(),
                )
            }
            ContactScorer.Confidence.ASK -> ResolveResult.Multiple(
                candidates = effective.filter { it.score >= 0.50 && it.score >= effective.first().score - 0.15 }
                    .take(4).map { entitiesByRef.getValue(it.contactId) },
                query = query,
                confidenceByPhone = effective.associate {
                    entitiesByRef.getValue(it.contactId).phoneNumber to it.score.toFloat()
                },
            )
            ContactScorer.Confidence.MISS -> ResolveResult.NotFound
        }
    }

    override suspend fun recordCorrection(wrongAlias: String, phoneNumber: String) {
        val alias = wrongAlias.trim().lowercase()
        if (alias.isEmpty() || phoneNumber.isBlank()) return
        val dao = correctionDao ?: return
        // Device contacts are transient (id 0); persist a stable entity so
        // the correction's foreign key has something to point at.
        val entity = contactDao.findByPhone(phoneNumber) ?: run {
            val stub = ContactEntity(
                canonicalName = phoneNumber,
                displayName = phoneNumber,
                aliases = "",
                phoneNumber = phoneNumber,
                group = "UNKNOWN",
            )
            stub.copy(id = contactDao.insert(stub))
        }
        dao.insert(
            CorrectionEntry(
                contactId = entity.id,
                wrongAlias = alias,
                correctContactId = 0,
            ),
        )
    }

    override suspend fun learnAlias(spokenForm: String, phoneNumber: String, displayName: String) {
        val alias = spokenForm.trim()
        if (alias.isEmpty() || phoneNumber.isBlank()) return
        val existing = contactDao.findByPhone(phoneNumber)
        if (existing == null) {
            contactDao.insert(
                ContactEntity(
                    canonicalName = displayName.ifBlank { alias },
                    displayName = displayName.ifBlank { alias },
                    aliases = alias,
                    phoneNumber = phoneNumber,
                    group = "UNKNOWN",
                ),
            )
        } else {
            val merged = mergeAliases(existing.aliases, alias)
            if (merged != existing.aliases) {
                contactDao.update(
                    existing.copy(aliases = merged, updatedAt = System.currentTimeMillis()),
                )
            }
        }
    }
}

/** Max aliases kept per contact; the oldest learned entries drop off first. */
private const val MAX_ALIASES_PER_CONTACT = 20

/**
 * Merges [newAlias] into a CSV alias list, case-insensitively de-duplicated.
 * Returns the original string unchanged when the alias is already present.
 */
fun mergeAliases(existingCsv: String, newAlias: String): String {
    val alias = newAlias.trim()
    if (alias.isEmpty()) return existingCsv
    val parts = existingCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { it.equals(alias, ignoreCase = true) }) return existingCsv
    val merged = (parts + alias).takeLast(MAX_ALIASES_PER_CONTACT)
    return merged.joinToString(",")
}
