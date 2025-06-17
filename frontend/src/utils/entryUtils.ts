import { EntryRelationship, MergeRequestData } from '../types';

/**
 * Helper function to find target entry based on relationship
 * This function is used by both MergeCollectionsStep and MergeSingleTypesStep
 */
export const findTargetEntry = (
    relationship: EntryRelationship, 
    allMergeData?: MergeRequestData
): { targetEntry: any, contentTypeKind: string } => {
    let targetContentType: any = null;
    let targetEntry: any = null;
    let contentTypeKind: string = '';

    if (!allMergeData) {
        return { targetEntry, contentTypeKind };
    }

    // Check if it's a single type
    if (allMergeData.singleTypes && allMergeData.singleTypes[relationship.targetContentType]) {
        targetContentType = allMergeData.singleTypes[relationship.targetContentType];
        contentTypeKind = 'singleType';

        // For single types, there's only one entry per content type
        if (targetContentType.compareKind === 'ONLY_IN_SOURCE') {
            targetEntry = targetContentType.onlyInSource;
        } else if (targetContentType.compareKind === 'DIFFERENT') {
            targetEntry = targetContentType.different?.source;
        } else if (targetContentType.compareKind === 'ONLY_IN_TARGET') {
            targetEntry = targetContentType.onlyInTarget;
        } else if (targetContentType.compareKind === 'IDENTICAL') {
            targetEntry = targetContentType.identical;
        }
    }
    // Check if it's a collection type
    else if (allMergeData.collectionTypes && allMergeData.collectionTypes[relationship.targetContentType]) {
        targetContentType = allMergeData.collectionTypes[relationship.targetContentType];
        contentTypeKind = 'collectionType';

        // For collection types, we need to find the specific entry by document ID
        const documentId = relationship.targetDocumentId;

        // Look in onlyInSource
        const sourceEntry = targetContentType.onlyInSource.find((entry: any) => entry.metadata.documentId === documentId);
        if (sourceEntry) {
            targetEntry = sourceEntry;
        }
        // Look in different
        else {
            const diffEntry = targetContentType.different.find((entry: any) => entry.source?.metadata.documentId === documentId);
            if (diffEntry) {
                targetEntry = diffEntry.source;
            }
            // Look in onlyInTarget
            else {
                const targetOnlyEntry = targetContentType.onlyInTarget.find((entry: any) => entry.metadata.documentId === documentId);
                if (targetOnlyEntry) {
                    targetEntry = targetOnlyEntry;
                }
                // Look in identical
                else {
                    const identicalEntry = targetContentType.identical.find((entry: any) => entry.metadata.documentId === documentId);
                    if (identicalEntry) {
                        targetEntry = identicalEntry;
                    }
                }
            }
        }
    }
    // Check if it's a file
    else if (allMergeData.files && relationship.targetContentType === 'plugin::upload.file') {
        contentTypeKind = 'plugin::upload.file';
        const documentId = relationship.targetDocumentId;

        // Look in onlyInSource
        const sourceFile = allMergeData.files.onlyInSource.find((file: any) => file.metadata.documentId === documentId);
        if (sourceFile) {
            targetEntry = sourceFile.metadata;
        }
        // Look in different
        else {
            const diffFile = allMergeData.files.different.find((file: any) => file.source.metadata.documentId === documentId);
            if (diffFile) {
                targetEntry = diffFile.source.metadata;
            }
            // Look in onlyInTarget
            else {
                const targetOnlyFile = allMergeData.files.onlyInTarget.find((file: any) => file.metadata.documentId === documentId);
                if (targetOnlyFile) {
                    targetEntry = targetOnlyFile.metadata;
                }
                // Look in identical
                else {
                    const identicalFile = allMergeData.files.identical.find((file: any) => file.metadata.documentId === documentId);
                    if (identicalFile) {
                        targetEntry = identicalFile.metadata;
                    }
                }
            }
        }
    }

    return { targetEntry, contentTypeKind };
};