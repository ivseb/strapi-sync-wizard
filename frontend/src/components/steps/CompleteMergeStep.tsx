import React, { useState } from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';
import {Accordion, AccordionTab} from 'primereact/accordion';
import {Tag} from 'primereact/tag';
import {Tooltip} from 'primereact/tooltip';
import {MergeRequestSelectionDTO, MergeRequestData, SelectionStatusInfo} from '../../types';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import EditorDialog from '../common/EditorDialog';

// Content type for files
const STRAPI_FILE_CONTENT_TYPE_NAME = "plugin::upload.file";

interface CompleteMergeStepProps {
    status: string;
    completing: boolean;
    completeMerge: () => void;
    selections?: MergeRequestSelectionDTO[];
    allMergeData?: MergeRequestData;
}

const CompleteMergeStep: React.FC<CompleteMergeStepProps> = ({
                                                                 status,
                                                                 completing,
                                                                 completeMerge,
                                                                 selections = [],
                                                                 allMergeData
                                                             }) => {
    // Count total items for each operation type
    const totalToCreate = selections.reduce((sum, selection) => sum + selection.entriesToCreate.length, 0);
    const totalToUpdate = selections.reduce((sum, selection) => sum + selection.entriesToUpdate.length, 0);
    const totalToDelete = selections.reduce((sum, selection) => sum + selection.entriesToDelete.length, 0);
    const totalItems = totalToCreate + totalToUpdate + totalToDelete;

    // State for editor modal
    const [editorDialogVisible, setEditorDialogVisible] = useState<boolean>(false);
    const [editorContent, setEditorContent] = useState<any>(null);
    const [isDiffEditor, setIsDiffEditor] = useState<boolean>(false);
    const [originalContent, setOriginalContent] = useState<any>(null);
    const [modifiedContent, setModifiedContent] = useState<any>(null);
    const [editorDialogHeader, setEditorDialogHeader] = useState<string>("View Content");

    // Function to find an entry in allMergeData based on contentType and documentId
    const findEntry = (contentType: string, documentId: string) => {
        if (!allMergeData) return null;

        // Handle file content type
        if (contentType === STRAPI_FILE_CONTENT_TYPE_NAME) {
            // Check in onlyInSource
            const sourceFile = allMergeData.files.onlyInSource.find(file => 
                file.metadata.documentId === documentId
            );
            if (sourceFile) return sourceFile;

            // Check in different
            const diffFile = allMergeData.files.different.find(file => 
                file.source.metadata.documentId === documentId
            );
            if (diffFile) return diffFile.source;

            // Check in onlyInTarget
            const targetFile = allMergeData.files.onlyInTarget.find(file => 
                file.metadata.documentId === documentId
            );
            if (targetFile) return targetFile;

            return null;
        }

        // Handle single content types
        if (allMergeData.singleTypes[contentType]) {
            const singleType = allMergeData.singleTypes[contentType];

            // Check in onlyInSource
            if (singleType.onlyInSource && singleType.onlyInSource.metadata.documentId === documentId) {
                return singleType.onlyInSource;
            }

            // Check in different
            if (singleType.different && singleType.different.source.metadata.documentId === documentId) {
                return singleType.different.source;
            }

            // Check in onlyInTarget
            if (singleType.onlyInTarget && singleType.onlyInTarget.metadata.documentId === documentId) {
                return singleType.onlyInTarget;
            }

            return null;
        }

        // Handle collection content types
        if (allMergeData.collectionTypes[contentType]) {
            const collectionType = allMergeData.collectionTypes[contentType];

            // Check in onlyInSource
            const sourceEntry = collectionType.onlyInSource.find(entry => 
                entry.metadata.documentId === documentId
            );
            if (sourceEntry) return sourceEntry;

            // Check in different
            const diffEntry = collectionType.different.find(entry => 
                entry.source.metadata.documentId === documentId
            );
            if (diffEntry) return diffEntry.source;

            // Check in onlyInTarget
            const targetEntry = collectionType.onlyInTarget.find(entry => 
                entry.metadata.documentId === documentId
            );
            if (targetEntry) return targetEntry;

            return null;
        }

        return null;
    };

    // Function to find status info for a document
    const findStatusInfo = (contentType: string, documentId: string, operation: 'create' | 'update' | 'delete'): SelectionStatusInfo | undefined => {
        const selection = selections.find(s => s.contentType === contentType);
        if (!selection) return undefined;

        let statusList: SelectionStatusInfo[] = [];
        if (operation === 'create' && selection.createStatus) {
            statusList = selection.createStatus;
        } else if (operation === 'update' && selection.updateStatus) {
            statusList = selection.updateStatus;
        } else if (operation === 'delete' && selection.deleteStatus) {
            statusList = selection.deleteStatus;
        }

        return statusList.find(s => s.documentId === documentId);
    };

    // Function to render status badge
    const renderStatusBadge = (status: SelectionStatusInfo | undefined) => {
        if (!status || status.syncSuccess === null) {
            return <Tag severity="info" value="Pending" />;
        }

        if (status.syncSuccess) {
            return <Tag severity="success" value="Success" />;
        } else {
            return (
                <div className="flex align-items-center">
                    <Tag 
                        className="failed-tag"
                        severity="danger" 
                        value="Failed" 
                        data-pr-tooltip={status.syncFailureResponse || "Unknown error"}
                    />
                    <Tooltip target=".failed-tag" position="top" />
                </div>
            );
        }
    };

    // Function to render representative attributes or images for an entry
    const renderRepresentativeContent = (contentType: string, documentId: string, operation: 'create' | 'update' | 'delete') => {
        const entry = findEntry(contentType, documentId);
        const statusInfo = findStatusInfo(contentType, documentId, operation);

        if (!entry) {
            return (
                <div className="flex align-items-center justify-content-between">
                    <span className="font-medium">{documentId}</span>
                    {renderStatusBadge(statusInfo)}
                </div>
            );
        }

        // Check if this is an update entry
        const { isUpdate, source, target } = isUpdateEntry(contentType, documentId);

        // Handle file content type (show image)
        if (contentType === STRAPI_FILE_CONTENT_TYPE_NAME) {
            // Type guard to check if metadata has url and name properties (StrapiImageMetadata)
            const hasImageProperties = 'url' in entry.metadata && 'name' in entry.metadata;

            return (
                <div className="flex align-items-center justify-content-between">
                    <div className="flex align-items-center">
                        {hasImageProperties ? (
                            <>
                                <img 
                                    src={(entry.metadata as any).url} 
                                    alt={(entry.metadata as any).name || 'Image'} 
                                    style={{ maxWidth: '50px', maxHeight: '50px', objectFit: 'contain', marginRight: '10px' }}
                                />
                                <span className="font-medium">{(entry.metadata as any).name || documentId}</span>
                            </>
                        ) : (
                            <span className="font-medium">{documentId}</span>
                        )}
                    </div>
                    <div className="flex align-items-center">
                        {renderStatusBadge(statusInfo)}
                        <Button
                            icon="pi pi-eye"
                            className="p-button-text p-button-sm ml-2"
                            tooltip="View Details"
                            onClick={() => {
                                if (isUpdate) {
                                    openEditorDialog(null, true, source, target, "View Differences");
                                } else {
                                    openEditorDialog(entry, false, null, null, "View Content");
                                }
                            }}
                        />
                    </div>
                </div>
            );
        }

        // Handle other content types (show representative attributes)
        const attributes = getRepresentativeAttributes(entry);

        if (attributes.length === 0) {
            return (
                <div className="flex align-items-center justify-content-between">
                    <span className="font-medium">{documentId}</span>
                    <div className="flex align-items-center">
                        {renderStatusBadge(statusInfo)}
                        <Button
                            icon="pi pi-eye"
                            className="p-button-text p-button-sm ml-2"
                            tooltip="View Details"
                            onClick={() => {
                                if (isUpdate) {
                                    openEditorDialog(null, true, source, target, "View Differences");
                                } else {
                                    openEditorDialog(entry, false, null, null, "View Content");
                                }
                            }}
                        />
                    </div>
                </div>
            );
        }

        return (
            <div className="flex align-items-start justify-content-between">
                <div>
                    {attributes.map((attr, index) => (
                        <div key={index} className="mb-1">
                            <span className="font-bold">{attr.key}: </span>
                            <span>{attr.value}</span>
                        </div>
                    ))}
                </div>
                <div className="flex align-items-center">
                    {renderStatusBadge(statusInfo)}
                    <Button
                        icon="pi pi-eye"
                        className="p-button-text p-button-sm ml-2"
                        tooltip="View Details"
                        onClick={() => {
                            if (isUpdate) {
                                openEditorDialog(null, true, source, target, "View Differences");
                            } else {
                                openEditorDialog(entry, false, null, null, "View Content");
                            }
                        }}
                    />
                </div>
            </div>
        );
    };

    // Function to open the editor dialog
    const openEditorDialog = (content: any, isDiff: boolean = false, source: any = null, target: any = null, header: string = "View Content") => {
        if (isDiff) {
            setIsDiffEditor(true);
            setOriginalContent(source);
            setModifiedContent(target);
        } else {
            setIsDiffEditor(false);
            setEditorContent(content);
        }
        setEditorDialogHeader(header);
        setEditorDialogVisible(true);
    };

    // Function to determine if an entry is an update (has both source and target)
    const isUpdateEntry = (contentType: string, documentId: string): { isUpdate: boolean, source: any, target: any } => {
        if (!allMergeData) return { isUpdate: false, source: null, target: null };

        // Handle file content type
        if (contentType === STRAPI_FILE_CONTENT_TYPE_NAME) {
            const diffFile = allMergeData.files.different.find(file => 
                file.source.metadata.documentId === documentId
            );
            if (diffFile) return { isUpdate: true, source: diffFile.source, target: diffFile.target };
        }

        // Handle single content types
        if (allMergeData.singleTypes[contentType]) {
            const singleType = allMergeData.singleTypes[contentType];
            if (singleType.different && singleType.different.source.metadata.documentId === documentId) {
                return { isUpdate: true, source: singleType.different.source, target: singleType.different.target };
            }
        }

        // Handle collection content types
        if (allMergeData.collectionTypes[contentType]) {
            const collectionType = allMergeData.collectionTypes[contentType];
            const diffEntry = collectionType.different.find(entry => 
                entry.source.metadata.documentId === documentId
            );
            if (diffEntry) return { isUpdate: true, source: diffEntry.source, target: diffEntry.target };
        }

        return { isUpdate: false, source: null, target: null };
    };

    return (
        <div>
            <h3>Complete Merge</h3>
            <p>
                This step completes the merge process and finalizes the synchronization between the source and target
                instances.
            </p>


            <h4>Merge Summary</h4>
            <p>The following items will be synchronized between the source and target instances:</p>

            <div className="flex justify-content-between mb-3">
                <div className="flex align-items-center">
                    <i className="pi pi-plus-circle text-success mr-2" style={{fontSize: '1.5rem'}}></i>
                    <span><strong>{totalToCreate}</strong> items to create</span>
                </div>
                <div className="flex align-items-center">
                    <i className="pi pi-sync text-warning mr-2" style={{fontSize: '1.5rem'}}></i>
                    <span><strong>{totalToUpdate}</strong> items to update</span>
                </div>
                <div className="flex align-items-center">
                    <i className="pi pi-trash text-danger mr-2" style={{fontSize: '1.5rem'}}></i>
                    <span><strong>{totalToDelete}</strong> items to delete</span>
                </div>
            </div>

            {totalItems === 0 && (
                <Message severity="info" text="No items selected for synchronization." className="w-full mb-3"/>
            )}

            {totalItems > 0 && (
                <Accordion multiple>
                    {selections.map((selection, index) => {
                        const hasItems = selection.entriesToCreate.length > 0 ||
                            selection.entriesToUpdate.length > 0 ||
                            selection.entriesToDelete.length > 0;

                        if (!hasItems) return null;

                        return (
                            <AccordionTab
                                key={index}
                                header={
                                    <div className="flex align-items-center">
                                        <span className="font-bold">{selection.contentType}</span>
                                        <span className="ml-3">
                                                    {selection.entriesToCreate.length > 0 && (
                                                        <span className="mr-2">
                                                            <i className="pi pi-plus-circle text-success mr-1"></i>
                                                            {selection.entriesToCreate.length}
                                                        </span>
                                                    )}
                                            {selection.entriesToUpdate.length > 0 && (
                                                <span className="mr-2">
                                                            <i className="pi pi-sync text-warning mr-1"></i>
                                                    {selection.entriesToUpdate.length}
                                                        </span>
                                            )}
                                            {selection.entriesToDelete.length > 0 && (
                                                <span>
                                                            <i className="pi pi-trash text-danger mr-1"></i>
                                                    {selection.entriesToDelete.length}
                                                        </span>
                                            )}
                                                </span>
                                    </div>
                                }
                            >
                                <div className="p-3">
                                    {selection.entriesToCreate.length > 0 && (
                                        <div className="mb-3">
                                            <h5 className="flex align-items-center">
                                                <i className="pi pi-plus-circle text-success mr-2"></i>
                                                Items to Create
                                            </h5>
                                            <ul className="list-none p-0 m-0">
                                                {selection.entriesToCreate.map((id, idx) => (
                                                    <li key={idx}
                                                        className="mb-2 p-2 border-1 border-round surface-border">
                                                        {renderRepresentativeContent(selection.contentType, id, 'create')}
                                                    </li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}

                                    {selection.entriesToUpdate.length > 0 && (
                                        <div className="mb-3">
                                            <h5 className="flex align-items-center">
                                                <i className="pi pi-sync text-warning mr-2"></i>
                                                Items to Update
                                            </h5>
                                            <ul className="list-none p-0 m-0">
                                                {selection.entriesToUpdate.map((id, idx) => (
                                                    <li key={idx}
                                                        className="mb-2 p-2 border-1 border-round surface-border">
                                                        {renderRepresentativeContent(selection.contentType, id, 'update')}
                                                    </li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}

                                    {selection.entriesToDelete.length > 0 && (
                                        <div>
                                            <h5 className="flex align-items-center">
                                                <i className="pi pi-trash text-danger mr-2"></i>
                                                Items to Delete
                                            </h5>
                                            <ul className="list-none p-0 m-0">
                                                {selection.entriesToDelete.map((id, idx) => (
                                                    <li key={idx}
                                                        className="mb-2 p-2 border-1 border-round surface-border">
                                                        {renderRepresentativeContent(selection.contentType, id, 'delete')}
                                                    </li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}
                                </div>
                            </AccordionTab>
                        );
                    })}
                </Accordion>
            )}

            <div className="flex flex-column align-items-center my-5">
                {status === 'COMPLETED' && (
                    <div className="mb-3">
                        <Message
                            severity="success"
                            text="Merge request completed successfully!"
                            className="w-full"
                        />
                    </div>
                )}
                {status !== 'COMPLETED' &&
                    <Button
                        label="Complete Merge"
                        icon="pi pi-check"
                        loading={completing}
                        // disabled={completing || status !== 'MERGED_COLLECTIONS'}
                        onClick={completeMerge}
                    />
                }
            </div>

            {/* Editor Dialog */}
            <EditorDialog
                visible={editorDialogVisible}
                onHide={() => setEditorDialogVisible(false)}
                header={editorDialogHeader}
                content={editorContent}
                isDiff={isDiffEditor}
                originalContent={originalContent}
                modifiedContent={modifiedContent}
            />
        </div>
    );
};

export default CompleteMergeStep;
