import React, {useEffect, useRef, useState} from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';

// Import types
import {
    ContentTypeComparisonResultWithRelationships,
    MergeRequestData,
    MergeRequestSelectionDTO,
    StrapiContentTypeKind
} from '../../types';
import ContentTypesSummaryTable from './components/ContentTypesSummaryTable';
import MergeSingleTypesStep from "./MergeSingleTypesStep";


interface MergeCollectionsStepProps {
    status: string;
    mergingCollections: boolean;
    mergeCollections: () => void;
    mergeRequestId: number;
    collectionTypesData: Record<string, ContentTypeComparisonResultWithRelationships[]>;
    selections?: MergeRequestSelectionDTO[];
    allMergeData: MergeRequestData;
    updateAllSelections: (kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string, documentIds?: string[]) => Promise<boolean>;
    onSaved?: (data?: MergeRequestData) => void | Promise<void>;
}

const MergeCollectionsStep: React.FC<MergeCollectionsStepProps> = ({
                                                                       status,
                                                                       mergingCollections,
                                                                       mergeCollections,
                                                                       mergeRequestId,
                                                                       collectionTypesData,
                                                                       selections,
                                                                       allMergeData,
                                                                       updateAllSelections,
                                                                       onSaved
                                                                   }) => {




    const [activeContentType, setActiveContentType] = useState<string | null>(null);
    // const [showManualMapper, setShowManualMapper] = useState<boolean>(false);

    // State for editor modal





    // Mapping helper: documentId -> comparison id per tableName
    const docIdToIdMapRef = useRef<Record<string, Record<string, string>>>({});

    useEffect(() => {
        if (!collectionTypesData) return;
        const docMap: Record<string, Record<string, string>> = {};
        Object.entries(collectionTypesData).forEach(([tableName, entries]) => {
            if (!entries || entries.length === 0) return;
            if (!docMap[tableName]) docMap[tableName] = {};
            entries.forEach(e => {
                const cmpId = (e as any).id as string;
                const sc = (e as any).sourceContent?.metadata?.documentId as string | undefined;
                const tc = (e as any).targetContent?.metadata?.documentId as string | undefined;
                if (sc) docMap[tableName][sc] = cmpId;
                if (tc) docMap[tableName][tc] = cmpId;
            });
        });
        docIdToIdMapRef.current = docMap;
    }, [collectionTypesData]);


    const isDisabled = mergingCollections ||
        (status !== 'MERGED_SINGLES' &&
            status !== 'MERGED_COLLECTIONS' &&
            status !== 'COMPLETED');







    // No content types
    if (Object.keys(collectionTypesData).length === 0) {
        return (
            <div>
                <h3>Merge Collections</h3>
                <Message severity="info" text="No collection content types found for comparison."
                         className="w-full mb-3"/>

                <div className="flex flex-column align-items-center my-5">
                    {status === 'MERGED_COLLECTIONS' && (
                        <div className="mb-3">
                            <Message
                                severity="success"
                                text="Collection content types merged successfully. You can proceed to the next step."
                                className="w-full"
                            />
                        </div>
                    )}

                    <Button
                        label="Merge Collections"
                        icon="pi pi-list"
                        loading={mergingCollections}
                        disabled={isDisabled}
                        onClick={mergeCollections}
                    />
                </div>
            </div>
        );
    }

    const messageContent = <div className="flex align-items-center">
        <i className="pi pi-info-circle mr-2" style={{fontSize: '1.5rem'}}></i>
        <div>
            <h5 className="mt-0 mb-1">Selection Management</h5>
            <p className="m-0">
                Select or deselect content types to include in the merge.
                Each selection will be immediately saved to the server.
                When you select a content type, you can view its details below.
            </p>
        </div>
    </div>

    const selectedContent = activeContentType ? {
        activeContentType: collectionTypesData[activeContentType]
    } as Record<string, ContentTypeComparisonResultWithRelationships[]> : null

    return (
        <div>
            <h3>Merge Collections</h3>
            <p>
                This step allows you to select which collection content types to create, update, or delete on the target
                instance.
                Review the differences and make your selections before proceeding.
            </p>
            <Message severity="info" className="mb-3" content={messageContent}/>

            <ContentTypesSummaryTable
                collectionTypes={collectionTypesData}
                activeContentType={activeContentType}
                onActiveContentTypeChange={setActiveContentType}
                selections={selections || []}
            />


            {selectedContent && (
                <MergeSingleTypesStep status={status}
                                      mergeSingleTypes={mergeCollections}
                                      kind={StrapiContentTypeKind.CollectionType}
                                      contentData={selectedContent} mergeRequestId={mergeRequestId}
                                      selections={selections || []}
                                      allMergeData={allMergeData}
                                      onSaved={onSaved}
                                      updateAllSelections={updateAllSelections}></MergeSingleTypesStep>
            )}


        </div>
    );
};

export default MergeCollectionsStep;
