package it.sebi.service.merge

import it.sebi.client.StrapiClient
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.repository.MergeRequestSelectionsRepository
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.slf4j.LoggerFactory

import it.sebi.tables.MergeRequestDocumentMappingTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class FileMergeProcessor(private val mergeRequestSelectionsRepository: MergeRequestSelectionsRepository) {
    private val maxParallelOperations = 4
    private val logger = LoggerFactory.getLogger(FileMergeProcessor::class.java)

    suspend fun processFiles(
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        sourceStrapiInstance: StrapiInstance,
        targetStrapiInstance: StrapiInstance,
        mergeRequestFiles: List<MergeRequestSelection>,
        comparisonData: List<ContentTypeFileComparisonResult>,
        mergeMapping: MutableMap<String, MergeRequestDocumentMapping>,
        // Optional: folders snapshots from prefetch cache (used to avoid live calls on source)
        sourceFoldersFromCache: List<StrapiFolder>? = null,
        targetFoldersFromCache: List<StrapiFolder>? = null
    ): List<MergeRequestDocumentMapping> {
        // Prefer cached folders for source; if missing and instance is virtual, fail with a clear message
        val sourceFolders = when {
            !sourceFoldersFromCache.isNullOrEmpty() -> sourceFoldersFromCache
            sourceStrapiInstance.isVirtual -> throw IllegalStateException("Source instance is virtual: folders must be provided in prefetch cache to run complete")
            else -> sourceClient.getFolders()
        }
        val sourceFoldersMap = sourceFolders.associateBy { it.path }
        // For target we can still fetch live; allow override from cache if given
        val targetFolders = when {
            !targetFoldersFromCache.isNullOrEmpty() -> targetFoldersFromCache
            else -> targetClient.getFolders()
        }
        val targetFolderPaths = targetFolders.map { it.pathFull }.toSet()
        val targetFolderMap = targetFolders.associateBy { it.pathFull }.toMutableMap()
        val requiredFolderPaths = mutableSetOf<String>()
        val filesToProcess = mutableListOf<Pair<StrapiImage, Direction>>()
        val filesToDelete = mutableListOf<StrapiImage>()
        for (file in mergeRequestFiles) {
            when (file.direction) {
                Direction.TO_CREATE -> {
                    val cmp =
                        comparisonData.find { it.compareState == ContentTypeComparisonResultKind.ONLY_IN_SOURCE && it.sourceImage?.metadata?.documentId == file.documentId }
                    val sourceFile = cmp?.sourceImage
                    if (sourceFile != null) {
                        filesToProcess.add(Pair(sourceFile, Direction.TO_CREATE))
                        sourceFoldersMap[sourceFile.metadata.folderPath]?.pathFull?.let { requiredFolderPaths.add(it) }
                    }
                }

                Direction.TO_UPDATE -> {
                    val cmp =
                        comparisonData.find { it.compareState == ContentTypeComparisonResultKind.DIFFERENT && it.sourceImage?.metadata?.documentId == file.documentId }
                    val src = cmp?.sourceImage
                    val tgt = cmp?.targetImage
                    if (src != null && tgt != null) {
                        val srcWithTargetId = src.copy(metadata = src.metadata.copy(id = tgt.metadata.id))
                        filesToProcess.add(Pair(srcWithTargetId, Direction.TO_UPDATE))
                        sourceFoldersMap[src.metadata.folderPath]?.pathFull?.let { requiredFolderPaths.add(it) }
                    }
                }

                Direction.TO_DELETE -> {
                    val cmp =
                        comparisonData.find { it.compareState == ContentTypeComparisonResultKind.ONLY_IN_TARGET && it.targetImage?.metadata?.documentId == file.documentId }
                    val targetFile = cmp?.targetImage
                    if (targetFile != null) filesToDelete.add(targetFile)
                }
            }
        }
        val missingFolderPaths = requiredFolderPaths.flatMap { folderPath ->
            folderPath.split("/")
                .filter { it.isNotEmpty() }
                .scan("") { acc, segment -> if (acc.isEmpty()) "/$segment" else "$acc/$segment" }
                .filter { it.isNotEmpty() }
        }.distinct().filter { folderPath -> !targetFolderPaths.contains(folderPath) }
        for (folderPath in missingFolderPaths.sortedBy { it.split("/").size }) {
            try {
                val folderName = folderPath.split("/").last()
                val parentPath = if (folderPath.contains("/")) {
                    folderPath.substring(0, folderPath.lastIndexOf("/"))
                } else {
                    ""
                }
                val parent = if (parentPath.isNotEmpty()) targetFolderMap[parentPath]?.id else null
                val createdFolder = targetClient.createFolder(folderName, parent)
                val fullPath = "$parentPath/$folderName"
                targetFolderMap[fullPath] = createdFolder.copy(pathFull = fullPath)
            } catch (e: Exception) {
                logger.warn("Error creating folder $folderPath: ${e.message}")
            }
        }
        for (selection in mergeRequestFiles.filter { it.direction == Direction.TO_DELETE }) {
            val cmp =
                comparisonData.find { it.compareState == ContentTypeComparisonResultKind.ONLY_IN_TARGET && it.targetImage?.metadata?.documentId == selection.documentId }
            val file = cmp?.targetImage
            if (file != null) {
                try {
                    dbQuery {
                        targetClient.deleteFile(file.metadata.id.toString())
                        mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                    }
                } catch (e: Exception) {
                    logger.error("Error deleting file ${file.metadata.name}: ${e.message}")
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unknown error")
                }
            }
        }
        return coroutineScope {
            val semaphore = Semaphore(maxParallelOperations)
            filesToProcess.map { (file, direction) ->
                async {
                    semaphore.withPermit {
                        mergeRequestFiles.find { it.documentId == file.metadata.documentId && (it.direction == Direction.TO_CREATE || it.direction == Direction.TO_UPDATE) }
                            ?.let { selection ->

                            try {
                        val sourceFile = sourceClient.downloadFile(file)
                        val folder =
                            sourceFoldersMap[file.metadata.folderPath]?.let { targetFolderMap[it.pathFull]?.id }
                        val id = if (direction == Direction.TO_UPDATE) {
                            file.metadata.id
                        } else null
                        val uploadResponse = targetClient.uploadFile(
                            id,
                            file.metadata.name,
                            sourceFile,
                            file.metadata.mime,
                            file.metadata.caption,
                            file.metadata.alternativeText,
                            folder
                        )
                        // Save mapping between source and target instead of updating target DB document_id
                        val record =  dbQuery {
                            val sourceDocId = file.metadata.documentId
                            val targetDocId = uploadResponse.documentId
                            val sourceId: Int? = comparisonData.find { it.sourceImage?.metadata?.documentId == sourceDocId }?.sourceImage?.metadata?.id
                            val existing = mergeMapping[sourceDocId]


                            if (existing == null) {
                                val res = MergeRequestDocumentMappingTable.insertAndGetId {
                                    it[MergeRequestDocumentMappingTable.sourceStrapiId] = sourceStrapiInstance.id
                                    it[MergeRequestDocumentMappingTable.targetStrapiId] = targetStrapiInstance.id
                                    it[MergeRequestDocumentMappingTable.contentType] = STRAPI_FILE_CONTENT_TYPE_NAME
                                    it[MergeRequestDocumentMappingTable.sourceId] = sourceId
                                    it[MergeRequestDocumentMappingTable.sourceDocumentId] = sourceDocId
                                    it[MergeRequestDocumentMappingTable.targetId] = uploadResponse.id
                                    it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocId
                                }
                                val newValue = MergeRequestDocumentMapping(
                                    id = res.value,
                                    sourceStrapiInstanceId = sourceStrapiInstance.id,
                                    targetStrapiInstanceId = targetStrapiInstance.id,
                                    contentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                                    sourceId = sourceId,
                                    sourceDocumentId = sourceDocId,
                                    targetId = uploadResponse.id,
                                    targetDocumentId = targetDocId
                                )
                                mergeMapping[sourceDocId] = newValue
                                newValue
                            } else {
                                val exId = existing.id
                                MergeRequestDocumentMappingTable.update({ MergeRequestDocumentMappingTable.id eq exId }) {
                                    it[MergeRequestDocumentMappingTable.targetId] = uploadResponse.id
                                    it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocId
                                }
                                val newValue = existing.copy(
                                    targetId = uploadResponse.id,
                                    targetDocumentId = targetDocId
                                )
                                mergeMapping[sourceDocId] = newValue
                                newValue
                            }
                        }
                        sourceFile.delete()
                        mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                        record
                    } catch (e: Exception) {
                        logger.error("Error ${if (direction == Direction.TO_CREATE) "creating" else "updating"} file ${file.metadata.name}: ${e.message}")
                        mergeRequestSelectionsRepository.updateSyncStatus(
                            selection.id,
                            false,
                            e.message ?: "Unknown error"
                        )
                        null
                    }
                            }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    }
}