package it.sebi.service.identity

import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.ContentTypeComparisonResultWithRelationships
import it.sebi.models.MergeRequestWithInstancesDTO
import it.sebi.models.StrapiContent
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

/** What reconciliation did (or would do, in dry-run) for one matched pair. */
enum class ReconciliationActionKind {
    ALREADY_LINKED,        // both sides already share the same sync_id
    LINK_TARGET_TO_SOURCE, // source has a sync_id, target gets it
    LINK_SOURCE_TO_TARGET, // target has a sync_id, source gets it
    ASSIGN_NEW_BOTH,       // neither side had one; a fresh shared sync_id is written to both
    CONFLICT_PREFER_SOURCE // both had DIFFERENT sync_ids; source's id wins (bootstrap policy)
}

@Serializable
data class IdentityReconciliationAction(
    val contentType: String,
    val sourceDocumentId: String?,
    val targetDocumentId: String?,
    val locale: String?,
    val canonicalSyncId: String,
    val kind: String
)

@Serializable
data class IdentityReconciliationReport(
    val mergeRequestId: Int,
    val applied: Boolean,
    val totalPairs: Int,
    val alreadyLinked: Int,
    val linked: Int,
    val assignedNew: Int,
    val conflicts: Int,
    val actions: List<IdentityReconciliationAction>
)

/**
 * Cross-instance identity reconciliation (Phase 1, bootstrap + recurring).
 *
 * The comparison has already paired source/target entries (by syncId first, then by the legacy
 * heuristics). For every confident pair this links a SHARED sync_id on both instances so that all
 * future comparisons match by exact join instead of heuristics.
 *
 * Bootstrap policy: when both sides already carry DIFFERENT sync_ids, the SOURCE id wins. Pairs that
 * the heuristics could only guess positionally are inherently as confident as today's behaviour; the
 * UI surfacing of ambiguous candidates (reusing ManualCollectionMapper) is the remaining follow-up.
 */
object IdentityReconciliationService {

    private val logger = LoggerFactory.getLogger(IdentityReconciliationService::class.java)

    suspend fun reconcile(
        mergeRequest: MergeRequestWithInstancesDTO,
        comparison: ContentTypeComparisonResultMapWithRelationships,
        apply: Boolean
    ): IdentityReconciliationReport {
        val source = mergeRequest.sourceInstance
        val target = mergeRequest.targetInstance
        if (source.isVirtual || target.isVirtual) {
            throw IllegalStateException("Identity reconciliation requires two real (non-virtual) instances")
        }
        if (apply) {
            SyncIdentityService.ensureTable(source)
            SyncIdentityService.ensureTable(target)
        }

        val pairs: List<ContentTypeComparisonResultWithRelationships> =
            comparison.singleTypes.values + comparison.collectionTypes.values.flatten()

        val actions = mutableListOf<IdentityReconciliationAction>()

        // Link one confirmed source<->target pair, writing a shared sync_id where missing.
        // Only entries paired on BOTH sides are reconciled (conservative: ambiguous/one-sided
        // entries are never auto-linked).
        suspend fun linkPair(
            uid: String,
            sDoc: String, sLocale: String?, ss: String?,
            tDoc: String, tLocale: String?, ts: String?,
        ) {
            val (canonical, kind) = when {
                ss != null && ts != null && ss == ts -> ss to ReconciliationActionKind.ALREADY_LINKED
                ss != null && ts == null -> ss to ReconciliationActionKind.LINK_TARGET_TO_SOURCE
                ss != null && ts != ss -> ss to ReconciliationActionKind.CONFLICT_PREFER_SOURCE
                ss == null && ts != null -> ts to ReconciliationActionKind.LINK_SOURCE_TO_TARGET
                else -> UUID.randomUUID().toString() to ReconciliationActionKind.ASSIGN_NEW_BOTH
            }
            if (apply && kind != ReconciliationActionKind.ALREADY_LINKED) {
                when (kind) {
                    ReconciliationActionKind.LINK_TARGET_TO_SOURCE,
                    ReconciliationActionKind.CONFLICT_PREFER_SOURCE ->
                        SyncIdentityService.upsertIdentity(target, uid, tDoc, tLocale, canonical)

                    ReconciliationActionKind.LINK_SOURCE_TO_TARGET ->
                        SyncIdentityService.upsertIdentity(source, uid, sDoc, sLocale, canonical)

                    ReconciliationActionKind.ASSIGN_NEW_BOTH -> {
                        SyncIdentityService.upsertIdentity(source, uid, sDoc, sLocale, canonical)
                        SyncIdentityService.upsertIdentity(target, uid, tDoc, tLocale, canonical)
                    }

                    ReconciliationActionKind.ALREADY_LINKED -> {}
                }
            }
            actions.add(
                IdentityReconciliationAction(
                    contentType = uid,
                    sourceDocumentId = sDoc,
                    targetDocumentId = tDoc,
                    locale = sLocale ?: tLocale,
                    canonicalSyncId = canonical,
                    kind = kind.name
                )
            )
        }

        // Content (single + collection) pairs.
        for (e in pairs) {
            val s: StrapiContent = e.sourceContent ?: continue
            val t: StrapiContent = e.targetContent ?: continue // only entries present on BOTH sides
            linkPair(
                e.contentType,
                s.metadata.documentId, s.metadata.locale, s.metadata.syncId?.takeIf { it.isNotBlank() },
                t.metadata.documentId, t.metadata.locale, t.metadata.syncId?.takeIf { it.isNotBlank() },
            )
        }

        // File pairs (uid plugin::upload.file). Only matched-on-both-sides pairs are reconciled;
        // ambiguous duplicates / one-sided files are left for manual review.
        for (f in comparison.files) {
            val s = f.sourceImage ?: continue
            val t = f.targetImage ?: continue
            linkPair(
                it.sebi.models.STRAPI_FILE_CONTENT_TYPE_NAME,
                s.metadata.documentId, s.metadata.locale, s.metadata.syncId?.takeIf { it.isNotBlank() },
                t.metadata.documentId, t.metadata.locale, t.metadata.syncId?.takeIf { it.isNotBlank() },
            )
        }

        val report = IdentityReconciliationReport(
            mergeRequestId = mergeRequest.id,
            applied = apply,
            totalPairs = actions.size,
            alreadyLinked = actions.count { it.kind == ReconciliationActionKind.ALREADY_LINKED.name },
            linked = actions.count {
                it.kind == ReconciliationActionKind.LINK_TARGET_TO_SOURCE.name ||
                        it.kind == ReconciliationActionKind.LINK_SOURCE_TO_TARGET.name
            },
            assignedNew = actions.count { it.kind == ReconciliationActionKind.ASSIGN_NEW_BOTH.name },
            conflicts = actions.count { it.kind == ReconciliationActionKind.CONFLICT_PREFER_SOURCE.name },
            actions = actions
        )
        logger.info(
            "Identity reconciliation MR ${mergeRequest.id} (apply=$apply): " +
                    "${report.totalPairs} pairs, linked=${report.linked}, new=${report.assignedNew}, " +
                    "conflicts=${report.conflicts}, alreadyLinked=${report.alreadyLinked}"
        )
        return report
    }
}
