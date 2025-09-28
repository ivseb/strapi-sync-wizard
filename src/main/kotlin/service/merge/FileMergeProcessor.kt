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
import org.jetbrains.exposed.v1.jdbc.update

class FileMergeProcessor(private val mergeRequestSelectionsRepository: MergeRequestSelectionsRepository) {
    private val logger = LoggerFactory.getLogger(FileMergeProcessor::class.java)

    suspend fun processFiles(
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        sourceStrapiInstance: StrapiInstance,
        targetStrapiInstance: StrapiInstance,
        mergeRequestFiles: List<MergeRequestSelection>,
        comparisonData: List<ContentTypeFileComparisonResult>
    ): Map<String, Int> {
        val sourceFolders = sourceClient.getFolders()
        val sourceFoldersMap = sourceFolders.associateBy { it.path }
        val targetFolders = targetClient.getFolders()
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
        return filesToProcess.mapNotNull { (file, direction) ->
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
                        dbQuery {
                            val sourceDocId = file.metadata.documentId
                            val targetDocId = uploadResponse.documentId
                            val sourceId: Int? = comparisonData.find { it.sourceImage?.metadata?.documentId == sourceDocId }?.sourceImage?.metadata?.id
                            val existing = MergeRequestDocumentMappingTable.selectAll().where {
                                (MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstance.id) and
                                (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstance.id) and
                                (MergeRequestDocumentMappingTable.contentType eq STRAPI_FILE_CONTENT_TYPE_NAME) and
                                (MergeRequestDocumentMappingTable.sourceDocumentId eq sourceDocId)
                            }.toList()

                            if (existing.isEmpty()) {
                                MergeRequestDocumentMappingTable.insert {
                                    it[MergeRequestDocumentMappingTable.sourceStrapiId] = sourceStrapiInstance.id
                                    it[MergeRequestDocumentMappingTable.targetStrapiId] = targetStrapiInstance.id
                                    it[MergeRequestDocumentMappingTable.contentType] = STRAPI_FILE_CONTENT_TYPE_NAME
                                    it[MergeRequestDocumentMappingTable.sourceId] = sourceId
                                    it[MergeRequestDocumentMappingTable.sourceDocumentId] = sourceDocId
                                    it[MergeRequestDocumentMappingTable.targetId] = uploadResponse.id
                                    it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocId
                                }
                            } else {
                                val exId = existing.first()[MergeRequestDocumentMappingTable.id].value
                                MergeRequestDocumentMappingTable.update({ MergeRequestDocumentMappingTable.id eq exId }) {
                                    it[MergeRequestDocumentMappingTable.targetId] = uploadResponse.id
                                    it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocId
                                }
                            }
                        }
                        sourceFile.delete()
                        mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                        file.metadata.documentId to uploadResponse.id
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
        }.toMap()
    }
}