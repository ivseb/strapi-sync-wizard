package it.sebi.service

import it.sebi.database.dbQuery
import it.sebi.repository.MergeRequestRepository
import it.sebi.repository.StrapiInstanceRepository
import it.sebi.models.SnapshotActivityDTO
import it.sebi.models.SnapshotDTO
import it.sebi.tables.MergeRequestSnapshotsTable
import it.sebi.tables.MergeRequestsTable
import it.sebi.tables.SnapshotActivityTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class PostgresSnapshotService(
    private val mergeRequestRepository: MergeRequestRepository
) {
    private val logger = LoggerFactory.getLogger(PostgresSnapshotService::class.java)

    private val systemTables = listOf(
        "strapi_database_schema",
        "strapi_migrations",
        "strapi_sessions",
        "strapi_api_tokens",
        "strapi_api_token_permissions",
        "strapi_transfer_tokens",
        "strapi_transfer_token_permissions",
        "admin_users",
        "admin_roles",
        "admin_permissions",
        "up_users", // Opzionale: dipende se vuoi preservare gli utenti del frontend
        "up_roles",
        "up_permissions"
    )

    private suspend fun logActivity(
        mergeRequestId: Int,
        type: String,
        status: String,
        schemaName: String? = null,
        message: String? = null
    ) {
        dbQuery {
            SnapshotActivityTable.insert {
                it[this.mergeRequestId] = mergeRequestId
                it[this.activityType] = type
                it[this.status] = status
                it[this.snapshotSchemaName] = schemaName
                it[this.message] = message
                it[this.createdAt] = OffsetDateTime.now()
            }
        }
    }

    suspend fun getSnapshotsForMergeRequest(mergeRequestId: Int): List<SnapshotDTO> = dbQuery {
        MergeRequestSnapshotsTable.selectAll()
            .where { MergeRequestSnapshotsTable.mergeRequestId eq mergeRequestId }
            .orderBy(MergeRequestSnapshotsTable.createdAt to SortOrder.DESC)
            .map {
                SnapshotDTO(
                    id = it[MergeRequestSnapshotsTable.id].value,
                    mergeRequestId = it[MergeRequestSnapshotsTable.mergeRequestId],
                    snapshotSchemaName = it[MergeRequestSnapshotsTable.snapshotSchemaName],
                    createdAt = it[MergeRequestSnapshotsTable.createdAt]
                )
            }
    }

    suspend fun getSnapshotActivityHistory(mergeRequestId: Int): List<SnapshotActivityDTO> = dbQuery {
        SnapshotActivityTable.selectAll()
            .where { SnapshotActivityTable.mergeRequestId eq mergeRequestId }
            .orderBy(SnapshotActivityTable.createdAt to SortOrder.DESC)
            .map {
                SnapshotActivityDTO(
                    id = it[SnapshotActivityTable.id].value,
                    mergeRequestId = it[SnapshotActivityTable.mergeRequestId],
                    activityType = it[SnapshotActivityTable.activityType],
                    status = it[SnapshotActivityTable.status],
                    snapshotSchemaName = it[SnapshotActivityTable.snapshotSchemaName],
                    message = it[SnapshotActivityTable.message],
                    createdAt = it[SnapshotActivityTable.createdAt]
                )
            }
    }

    suspend fun takeSnapshot(mergeRequestId: Int) {
        val mr = mergeRequestRepository.getMergeRequestWithInstances(mergeRequestId) ?: throw Exception("Merge request not found")
        val targetInstance = mr.targetInstance
        val db = targetInstance.database ?: throw Exception("Target instance DB connection not configured")

        val schemaName = "snapshot_mr_${mergeRequestId}_${System.currentTimeMillis()}"

        try {
            dbQuery(db) {
                // 1. Create dedicated schema
                exec("CREATE SCHEMA \"$schemaName\"")
                logger.info("Created snapshot schema: $schemaName for MR: $mergeRequestId")

                // 2. Get all tables in public schema (ignoring system tables)
                val tables = mutableListOf<String>()
                exec("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'") { rs ->
                    while (rs.next()) {
                        val tableName = rs.getString("table_name")
                        if (!systemTables.contains(tableName) && !tableName.startsWith("strapi_")) {
                            tables.add(tableName)
                        }
                    }
                }

                // 3. Copy tables structure and data
                tables.forEach { tableName ->
                    exec("CREATE TABLE \"$schemaName\".\"$tableName\" AS TABLE public.\"$tableName\"")
                    logger.debug("Copied table $tableName to schema $schemaName")
                }
            }

            // 4. Record snapshot in metadata table
            dbQuery {
                MergeRequestSnapshotsTable.insert {
                    it[this.mergeRequestId] = mergeRequestId
                    it[this.snapshotSchemaName] = schemaName
                    it[this.createdAt] = OffsetDateTime.now()
                }
            }

            logActivity(mergeRequestId, "TAKE_SNAPSHOT", "SUCCESS", schemaName)
            
            // 5. Cleanup old snapshots for this target instance (maintaining only last 3)
            cleanupOldSnapshots(mr.targetInstance.id)
        } catch (e: Exception) {
            logActivity(mergeRequestId, "TAKE_SNAPSHOT", "FAILED", schemaName, e.message)
            throw e
        }
    }

    suspend fun restoreSnapshot(mergeRequestId: Int, snapshotSchemaName: String? = null) {
        val mr = mergeRequestRepository.getMergeRequestWithInstances(mergeRequestId) ?: throw Exception("Merge request not found")
        val targetInstance = mr.targetInstance
        val db = targetInstance.database ?: throw Exception("Target instance DB connection not configured")

        // Find the snapshot to restore
        val targetSnapshot = if (snapshotSchemaName != null) {
            snapshotSchemaName
        } else {
            dbQuery {
                MergeRequestSnapshotsTable.selectAll()
                    .where { MergeRequestSnapshotsTable.mergeRequestId eq mergeRequestId }
                    .orderBy(MergeRequestSnapshotsTable.createdAt to SortOrder.DESC)
                    .limit(1)
                    .map { it[MergeRequestSnapshotsTable.snapshotSchemaName] }
                    .singleOrNull()
            } ?: throw Exception("No snapshot found for MR: $mergeRequestId")
        }

        logger.info("Restoring snapshot: $targetSnapshot for MR: $mergeRequestId")

        try {
            dbQuery(db) {
                // 1. Disable FK checks
                exec("SET session_replication_role = 'replica';")

                try {
                    // 2. Get all tables available in the SNAPSHOT schema
                    val tablesToRestore = mutableListOf<String>()
                    exec("SELECT table_name FROM information_schema.tables WHERE table_schema = '$targetSnapshot' AND table_type = 'BASE TABLE'") { rs ->
                        while (rs.next()) {
                            tablesToRestore.add(rs.getString("table_name"))
                        }
                    }

                    tablesToRestore.forEach { tableName ->
                        if (systemTables.contains(tableName)) return@forEach
                        exec("TRUNCATE TABLE public.\"$tableName\" CASCADE")
                    }

                    // 3. Truncate and Restore only what is in the snapshot
                    tablesToRestore.forEach { tableName ->
                        // Saltiamo comunque le tabelle di sistema per sicurezza, 
                        // anche se non dovrebbero essere nello snapshot
                        if (systemTables.contains(tableName)) return@forEach

                        exec("""
                                INSERT INTO public."$tableName" 
                                OVERRIDING SYSTEM VALUE 
                                SELECT * FROM "$targetSnapshot"."$tableName"
                            """.trimIndent())
                        
                        // FIX 1: Reset sequence for each table to avoid ID conflicts
                        exec("""
                                DO $$ 
                                BEGIN
                                    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = '$tableName' AND column_name = 'id' AND table_schema = 'public') THEN
                                        PERFORM setval(pg_get_serial_sequence('public."$tableName"', 'id'), coalesce(max(id), 1), max(id) IS NOT NULL) FROM public."$tableName";
                                    END IF;
                                END $$;
                            """.trimIndent())
                        
                        logger.debug("Restored and re-sequenced table $tableName from schema $targetSnapshot")
                    }
                } finally {
                    // 4. Re-enable FK checks
                    exec("SET session_replication_role = 'origin';")
                }
            }
            
            // FIX 2: Strapi Refresh (Opzionale ma raccomandato)
            // Se hai un endpoint di webhook su Strapi per svuotare la cache, chiamalo qui.
            // In alternativa, Strapi richiede spesso un riavvio se lo schema (CTDs) è cambiato,
            // ma per i soli dati il reset delle sequenze dovrebbe bastare a renderlo responsivo.
            
            logActivity(mergeRequestId, "RESTORE_SNAPSHOT", "SUCCESS", targetSnapshot)
        } catch (e: Exception) {
            logActivity(mergeRequestId, "RESTORE_SNAPSHOT", "FAILED", targetSnapshot, e.message)
            throw e
        }
    }

    private suspend fun cleanupOldSnapshots(targetInstanceId: Int) {
        // Find all snapshots for MRs that use this target instance
        val snapshots = dbQuery {
            (MergeRequestSnapshotsTable innerJoin MergeRequestsTable)
                .selectAll()
                .where { MergeRequestsTable.targetInstanceId eq targetInstanceId }
                .orderBy(MergeRequestSnapshotsTable.createdAt to SortOrder.DESC)
                .map { 
                    Triple(it[MergeRequestSnapshotsTable.id].value, it[MergeRequestSnapshotsTable.snapshotSchemaName], it[MergeRequestsTable.targetInstanceId])
                }
        }

        if (snapshots.size > 3) {
            val toDelete = snapshots.drop(3)
            val instanceRepository = StrapiInstanceRepository()
            toDelete.forEach { (id, schemaName, targetId) ->
                logger.info("Cleaning up old snapshot: $schemaName")
                
                // We need the database connection of the target instance to drop the schema
                val targetInstance = dbQuery {
                    instanceRepository.getInstance(targetId)
                }
                
                targetInstance?.database?.let { db ->
                    try {
                        dbQuery(db) {
                            exec("DROP SCHEMA IF EXISTS \"$schemaName\" CASCADE")
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to drop schema $schemaName: ${e.message}")
                    }
                }

                // Remove from metadata table
                dbQuery {
                    MergeRequestSnapshotsTable.deleteWhere { MergeRequestSnapshotsTable.id eq id }
                }
            }
        }
    }
}
