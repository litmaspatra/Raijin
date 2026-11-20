package com.raijin.app.memory

import com.raijin.app.permissions.PermissionBroker
import io.raijin.contract.EntityCandidate
import io.raijin.contract.MemoryHit
import io.raijin.contract.MemoryQuery
import io.raijin.contract.MemoryWrite
import io.raijin.contract.PersonalMemoryKind
import io.raijin.contract.RaijinMemoryPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Room-backed implementation of [RaijinMemoryPort].
 *
 * Blends local private memories (facts, episodes, preferences) persisted in SQLite
 * with device contact resolution via [PermissionBroker].
 * All data remains strictly local and private.
 */
class RoomRaijinMemoryAdapter(
    private val memoryDao: MemoryDao,
    private val appAliasDao: com.raijin.app.apps.AppAliasDao,
    private val permissionBroker: PermissionBroker,
    private val contactResolver: com.raijin.app.contact.ContactResolverPort,
) : RaijinMemoryPort {

    override suspend fun search(query: MemoryQuery): List<MemoryHit> = withContext(Dispatchers.IO) {
        val matches = memoryDao.search(query.text, query.limit)
        matches.map { entity ->
            val kind = try {
                PersonalMemoryKind.valueOf(entity.kind)
            } catch (_: Exception) {
                PersonalMemoryKind.FACT
            }
            MemoryHit(
                id = "memory:${entity.id}",
                kind = kind,
                score = entity.score,
                summary = entity.text,
            )
        }
    }

    override suspend fun resolveEntity(nameOrAlias: String, limit: Int): List<EntityCandidate> =
        resolveEntityAlternatives(listOf(nameOrAlias), limit)

    override suspend fun resolveEntityAlternatives(names: List<String>, limit: Int): List<EntityCandidate> = withContext(Dispatchers.IO) {
        val nameOrAlias = names.firstOrNull().orEmpty()
        val results = mutableListOf<EntityCandidate>()

        // CT-6: the port ranks every address-book/learned contact, including
        // aliases. Preserve the resolved phone ref through confirmation.
        when (val resolved = contactResolver.resolveAlternatives(names)) {
            is com.raijin.app.contact.ResolveResult.Single -> {
                results += EntityCandidate("contact:${resolved.entity.phoneNumber}", resolved.entity.displayName, resolved.confidence)
            }
            is com.raijin.app.contact.ResolveResult.Multiple -> resolved.candidates.forEach { e ->
                results += EntityCandidate("contact:${e.phoneNumber}", e.displayName,
                    resolved.confidenceByPhone[e.phoneNumber] ?: 0.0f)
            }
            com.raijin.app.contact.ResolveResult.NotFound -> Unit
        }

        // 2. Resolve from learned memory aliases
        val learned = memoryDao.findEntities(nameOrAlias, limit)
        for (f in learned) {
            val label = f.entityAlias ?: f.text
            if (results.none { it.canonicalName.equals(label, ignoreCase = true) }) {
                results += EntityCandidate(
                    entityId = "memory:${f.id}",
                    canonicalName = label,
                    confidence = 0.90f,
                )
            }
        }

        results.sortedByDescending { it.confidence }.take(limit)
    }

    override suspend fun learnEntityAlias(spokenForm: String, entityId: String, displayName: String) = withContext(Dispatchers.IO) {
        // CT-7: only contact refs map onto the contact alias store; memory refs
        // already are learned records.
        if (!entityId.startsWith("contact:")) return@withContext
        contactResolver.learnAlias(spokenForm, entityId.removePrefix("contact:"), displayName)
    }

    override suspend fun learnAppAlias(spokenForm: String, intendedAppName: String) = withContext(Dispatchers.IO) {
        // AP-2: key by the normalized spoken form so resolver lookups hit.
        val key = io.raijin.brain.ContactNameNormalizer.normalize(spokenForm)
        if (key.isEmpty()) return@withContext
        appAliasDao.upsert(
            com.raijin.app.apps.AppAliasEntity(
                spokenAlias = key,
                intendedApp = intendedAppName.trim(),
                learnedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun recordContactCorrection(wrongAlias: String, entityId: String) = withContext(Dispatchers.IO) {
        if (!entityId.startsWith("contact:")) return@withContext
        contactResolver.recordCorrection(wrongAlias, entityId.removePrefix("contact:"))
    }

    override suspend fun remember(write: MemoryWrite): String = withContext(Dispatchers.IO) {
        val entity = MemoryFactEntity(
            kind = write.kind.name,
            text = write.text,
            entityAlias = write.entityIds.firstOrNull(),
            createdAt = System.currentTimeMillis(),
        )
        val id = memoryDao.insert(entity)
        "memory:$id"
    }

    override suspend fun forget(memoryId: String): Boolean = withContext(Dispatchers.IO) {
        val id = memoryId.removePrefix("memory:").toLongOrNull() ?: return@withContext false
        val deleted = memoryDao.deleteById(id)
        deleted > 0
    }
}
