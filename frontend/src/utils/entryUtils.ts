import {
    ContentTypeComparisonResultWithRelationships, ContentTypeFileComparisonResult,
    EntryRelationship,
    MergeRequestData,
    StrapiLinkRef
} from '../types';

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

    // Helper: locate single type by UID across values
    const findSingleByUid = (uid: string) => {
        const values = allMergeData.singleTypes ? Object.values(allMergeData.singleTypes) : [];
        return values.find(v => v.contentType === uid);
    };
    // Helper: locate collection list by UID across values
    const findCollectionByUid = (uid: string) => {
        const lists = allMergeData.collectionTypes ? Object.values(allMergeData.collectionTypes) : [];
        return lists.find(list => list && list.length > 0 && list[0].contentType === uid);
    };

    // Check if it's a single type
    const single = findSingleByUid(relationship.targetContentType);
    if (single) {
        targetContentType = single;
        contentTypeKind = 'singleType';

        // For single types, find the matching entry by documentId from source/target
        const docId = relationship.targetDocumentId;
        if (single.sourceContent && single.sourceContent.metadata.documentId === docId) {
            targetEntry = single.sourceContent;
        } else if (single.targetContent && single.targetContent.metadata.documentId === docId) {
            targetEntry = single.targetContent;
        }
    }
    // Check if it's a collection type
    else {
        const entries = findCollectionByUid(relationship.targetContentType);
        if (entries) {
            contentTypeKind = 'collectionType';
            const documentId = relationship.targetDocumentId;

            // Try ONLY_IN_SOURCE
            const srcEntry = entries.find((e: any) => e.compareState === 'ONLY_IN_SOURCE' && e.sourceContent && e.sourceContent.metadata.documentId === documentId)?.sourceContent;
            if (srcEntry) {
                targetEntry = srcEntry;
            } else {
                // Try DIFFERENT
                const diffEntry = entries.find((e: any) => e.compareState === 'DIFFERENT' && e.sourceContent && e.sourceContent.metadata.documentId === documentId);
                if (diffEntry?.sourceContent) {
                    targetEntry = diffEntry.sourceContent;
                } else {
                    // Try ONLY_IN_TARGET
                    const tgtEntry = entries.find((e: any) => e.compareState === 'ONLY_IN_TARGET' && e.targetContent && e.targetContent.metadata.documentId === documentId)?.targetContent;
                    if (tgtEntry) {
                        targetEntry = tgtEntry;
                    } else {
                        // Try IDENTICAL
                        const ident = entries.find((e: any) => e.compareState === 'IDENTICAL' && ((e.sourceContent && e.sourceContent.metadata.documentId === documentId) || (e.targetContent && e.targetContent.metadata.documentId === documentId)));
                        if (ident) {
                            targetEntry = ident.sourceContent ?? ident.targetContent;
                        }
                    }
                }
            }
        }
    }
    // Check if it's a file
    if (!targetEntry && allMergeData.files && relationship.targetContentType === 'plugin::upload.file') {
        contentTypeKind = 'plugin::upload.file';
        const documentId = relationship.targetDocumentId;

        // Try ONLY_IN_SOURCE
        const srcImg = allMergeData.files.find((f: any) => f.compareState === 'ONLY_IN_SOURCE' && f.sourceImage?.metadata.documentId === documentId)?.sourceImage;
        if (srcImg) {
            targetEntry = srcImg.metadata;
        } else {
            // Try DIFFERENT
            const diff = allMergeData.files.find((f: any) => f.compareState === 'DIFFERENT' && f.sourceImage?.metadata.documentId === documentId);
            if (diff?.sourceImage) {
                targetEntry = diff.sourceImage.metadata;
            } else {
                // Try ONLY_IN_TARGET
                const tgtImg = allMergeData.files.find((f: any) => f.compareState === 'ONLY_IN_TARGET' && f.targetImage?.metadata.documentId === documentId)?.targetImage;
                if (tgtImg) {
                    targetEntry = tgtImg.metadata;
                } else {
                    // Try IDENTICAL
                    const ident = allMergeData.files.find((f: any) => f.compareState === 'IDENTICAL' && ((f.sourceImage && f.sourceImage.metadata.documentId === documentId) || (f.targetImage && f.targetImage.metadata.documentId === documentId)));
                    const img = ident?.sourceImage ?? ident?.targetImage;
                    if (img) {
                        targetEntry = img.metadata;
                    }
                }
            }
        }
    }

    return { targetEntry, contentTypeKind };
};

export interface RelationshipStatus{
    link: StrapiLinkRef,
    data: (ContentTypeComparisonResultWithRelationships | ContentTypeFileComparisonResult),
    identical: boolean,
}
// Helpers to derive relationships from StrapiContent.links instead of precomputed relationships
const getBaseContent = (rowData: ContentTypeComparisonResultWithRelationships) => {
    return rowData.sourceContent ?? rowData.targetContent ?? null;
};

export const buildRelationshipsFromLinks = (allMergeData: MergeRequestData,rowData: ContentTypeComparisonResultWithRelationships):RelationshipStatus[] => {
    const base = getBaseContent(rowData);
    if (!base || !base.links || base.links.length === 0) return [];

    const relationships: RelationshipStatus[] = []
    for (const link of base.links) {
        if (link?.targetId == null) continue; // skip unresolved links
        if (link?.targetTable == 'files') {
            const c = allMergeData.files.find(x => x.sourceImage?.metadata?.id == link.targetId)
            if (c) {
                relationships.push({
                    link,
                    data: c,
                    identical: c.compareState === 'IDENTICAL'
                })
            }
            continue;
        }

        const c = allMergeData.singleTypes[link.targetTable] || allMergeData.collectionTypes[link.targetTable].find(x => x.sourceContent?.metadata?.id == link.targetId)
        if (c) {
            relationships.push({
                link,
                data: c,
                identical: c.compareState === 'IDENTICAL'
            })
        }

    }
    return relationships;
};

export const isComparisonItemResolved = (
    allMergeData: MergeRequestData,
    item: ContentTypeComparisonResultWithRelationships | ContentTypeFileComparisonResult
): boolean => {
    // Detect if item is a file comparison result
    const isFile = (item as any) && (('sourceImage' in (item as any)) || ('targetImage' in (item as any)));
    if (isFile) {
        const file = item as ContentTypeFileComparisonResult;
        const docId = file.sourceImage?.metadata?.documentId || file.targetImage?.metadata?.documentId;
        if (!docId) return false;
        return Boolean(allMergeData?.selections?.some(s => s.selections?.some(sel => sel.documentId == docId)));
    }
    // Otherwise it's a content type comparison result
    const content = item as ContentTypeComparisonResultWithRelationships;
    const id = (content as any)?.id;
    if (!id) return false;
    return Boolean(allMergeData?.selections?.some(s => s.selections?.some(sel => sel.documentId == id)));
};
