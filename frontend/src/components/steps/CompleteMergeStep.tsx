import React, { useState, useEffect, useRef } from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';
import {Tag} from 'primereact/tag';
import {Tooltip} from 'primereact/tooltip';
import {ProgressBar} from 'primereact/progressbar';
import {Toast} from 'primereact/toast';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {TabView, TabPanel} from 'primereact/tabview';
import {Card} from 'primereact/card';
import {MergeRequestSelectionDTO, MergeRequestData, SelectionStatusInfo} from '../../types';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import EditorDialog from '../common/EditorDialog';

// Content type for files
const STRAPI_FILE_CONTENT_TYPE_NAME = "plugin::upload.file";

// Interface for sync progress updates from the WebSocket
interface SyncProgressUpdate {
    mergeRequestId: number;
    totalItems: number;
    processedItems: number;
    currentItem: string;
    currentItemType: string;
    currentOperation: string;
    status: string;
    message?: string;
}

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

    // State for sync progress
    const [syncProgress, setSyncProgress] = useState<SyncProgressUpdate | null>(null);
    const [syncInProgress, setSyncInProgress] = useState<boolean>(false);
    const [syncCompleted, setSyncCompleted] = useState<boolean>(false);
    const [syncFailed, setSyncFailed] = useState<boolean>(false);
    const [syncItemsStatus, setSyncItemsStatus] = useState<Record<string, { status: string, message?: string }>>({});

    // Reference for EventSource connection
    const eventSourceRef = useRef<EventSource | null>(null);

    // Reference for Toast component
    const toast = useRef<Toast>(null);

    // Effect to handle EventSource connection and cleanup
    useEffect(() => {
        return () => {
            // Cleanup EventSource connection when component unmounts
            if (eventSourceRef.current) {
                eventSourceRef.current.close();
                eventSourceRef.current = null;
            }
        };
    }, []);

    // Function to start the SSE connection for sync progress updates
    const startSyncProgressSSE = (mergeRequestId: number) => {
        // Close existing connection if any
        if (eventSourceRef.current) {
            eventSourceRef.current.close();
            eventSourceRef.current = null;
        }

        // Reset sync state
        setSyncProgress(null);
        setSyncInProgress(true);
        setSyncCompleted(false);
        setSyncFailed(false);
        setSyncItemsStatus({});

        // Create new EventSource connection
        const sseUrl = `/api/sync-progress/${mergeRequestId}`;
        const eventSource = new EventSource(sseUrl);

        // Handle connection open
        eventSource.onopen = () => {
            console.log('SSE connection established');
        };

        // Handle connected event
        eventSource.addEventListener('connected', (event) => {
            console.log('SSE connected event received:', event);
        });

        // Handle progress events
        eventSource.addEventListener('progress', (event) => {
            try {
                const update: SyncProgressUpdate = JSON.parse(event.data);
                console.log('Received sync progress update:', update);

                // Update sync progress state
                setSyncProgress(update);

                // Update item status
                if (update.currentItem && update.currentItem !== 'Starting synchronization' && 
                    update.currentItem !== 'Processing content types' && 
                    update.currentItem !== 'Content types processed' &&
                    update.currentItem !== 'Synchronization completed' &&
                    update.currentItem !== 'Synchronization failed') {
                    setSyncItemsStatus(prev => ({
                        ...prev,
                        [update.currentItem]: {
                            status: update.status,
                            message: update.message
                        }
                    }));
                }

                // Handle completion or failure
                if (update.status === 'SUCCESS' && update.currentOperation === 'COMPLETED') {
                    setSyncInProgress(false);
                    setSyncCompleted(true);

                    // Close EventSource connection
                    eventSource.close();
                } else if (update.status === 'ERROR') {
                    setSyncInProgress(false);
                    setSyncFailed(true);

                    // Close EventSource connection
                    eventSource.close();
                }
            } catch (error) {
                console.error('Error parsing SSE message:', error);
            }
        });

        // Handle errors
        eventSource.onerror = (error) => {
            console.error('SSE error:', error);
            setSyncInProgress(false);
            setSyncFailed(true);

            // Close EventSource connection
            eventSource.close();
        };

        eventSourceRef.current = eventSource;
    };

    // Function to handle the complete merge button click
    const handleCompleteMerge = () => {
        // Get merge request ID from the URL
        const urlParts = window.location.pathname.split('/');
        const mergeRequestId = parseInt(urlParts[urlParts.length - 1], 10);


        if (!isNaN(mergeRequestId)) {
            // Start SSE connection for progress updates
            startSyncProgressSSE(mergeRequestId);

            // Call the original completeMerge function
            completeMerge();
        } else {
            console.error('Could not determine merge request ID from URL');

            // Show error toast
            toast.current?.show({
                severity: 'error',
                summary: 'Error',
                detail: 'Could not determine merge request ID.',
                life: 5000
            });
        }
    };

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
    const renderStatusBadge = (status: SelectionStatusInfo | undefined, contentType: string, documentId: string) => {
        // Check if we have live status from WebSocket
        const liveStatus = syncItemsStatus[documentId];

        if (liveStatus) {
            if (liveStatus.status === 'SUCCESS') {
                return <Tag severity="success" value="Success" />;
            } else if (liveStatus.status === 'ERROR') {
                return (
                    <div className="flex align-items-center">
                        <Tag 
                            className={`failed-tag-${documentId}`}
                            severity="danger" 
                            value="Failed" 
                            data-pr-tooltip={liveStatus.message || "Unknown error"}
                        />
                        <Tooltip target={`.failed-tag-${documentId}`} position="top" />
                    </div>
                );
            } else if (liveStatus.status === 'IN_PROGRESS') {
                return (
                    <div className="flex align-items-center">
                        <i className="pi pi-spin pi-spinner mr-2"></i>
                        <Tag severity="info" value="Processing" />
                    </div>
                );
            }
        }

        // Fall back to original status if no live status
        if (!status || status.syncSuccess === null) {
            return <Tag severity="info" value="Pending" />;
        }

        if (status.syncSuccess) {
            return <Tag severity="success" value="Success" />;
        } else {
            return (
                <div className="flex align-items-center">
                    <Tag 
                        className={`failed-tag-${documentId}`}
                        severity="danger" 
                        value="Failed" 
                        data-pr-tooltip={status.syncFailureResponse || "Unknown error"}
                    />
                    <Tooltip target={`.failed-tag-${documentId}`} position="top" />
                </div>
            );
        }
    };

    // Function to render representative attributes or images for an entry
    const renderRepresentativeContent = (contentType: string, documentId: string, operation: 'create' | 'update' | 'delete') => {
        const entry = findEntry(contentType, documentId);

        if (!entry) {
            return (
                <div className="flex align-items-center justify-content-between">
                    <span className="font-medium">{documentId}</span>
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
                </div>
            );
        }

        // Handle other content types (show representative attributes)
        const attributes = getRepresentativeAttributes(entry);

        if (attributes.length === 0) {
            return (
                <div className="flex align-items-center justify-content-between">
                    <span className="font-medium">{documentId}</span>
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

    // Prepare data for DataTable
    const prepareItemsForDataTable = () => {
        const items: any[] = [];

        selections.forEach(selection => {
            // Add items to create
            selection.entriesToCreate.forEach(id => {
                items.push({
                    contentType: selection.contentType,
                    documentId: id,
                    operation: 'create',
                    statusInfo: findStatusInfo(selection.contentType, id, 'create')
                });
            });

            // Add items to update
            selection.entriesToUpdate.forEach(id => {
                items.push({
                    contentType: selection.contentType,
                    documentId: id,
                    operation: 'update',
                    statusInfo: findStatusInfo(selection.contentType, id, 'update')
                });
            });

            // Add items to delete
            selection.entriesToDelete.forEach(id => {
                items.push({
                    contentType: selection.contentType,
                    documentId: id,
                    operation: 'delete',
                    statusInfo: findStatusInfo(selection.contentType, id, 'delete')
                });
            });
        });

        return items;
    };

    const allItems = prepareItemsForDataTable();
    const createItems = allItems.filter(item => item.operation === 'create');
    const updateItems = allItems.filter(item => item.operation === 'update');
    const deleteItems = allItems.filter(item => item.operation === 'delete');

    // Function to render operation icon
    const renderOperationIcon = (operation: string) => {
        switch (operation) {
            case 'create':
                return <i className="pi pi-plus-circle text-success mr-2"></i>;
            case 'update':
                return <i className="pi pi-sync text-warning mr-2"></i>;
            case 'delete':
                return <i className="pi pi-trash text-danger mr-2"></i>;
            default:
                return null;
        }
    };

    // Function to render operation badge
    const renderOperationBadge = (operation: string) => {
        switch (operation) {
            case 'create':
                return <Tag severity="success" value="Create" />;
            case 'update':
                return <Tag severity="warning" value="Update" />;
            case 'delete':
                return <Tag severity="danger" value="Delete" />;
            default:
                return null;
        }
    };

    // Function to render content
    const renderContent = (rowData: any) => {
        return renderRepresentativeContent(rowData.contentType, rowData.documentId, rowData.operation as 'create' | 'update' | 'delete');
    };

    // Function to render status
    const renderStatus = (rowData: any) => {
        return renderStatusBadge(rowData.statusInfo, rowData.contentType, rowData.documentId);
    };

    // Function to render view button
    const renderViewButton = (rowData: any) => {
        const entry = findEntry(rowData.contentType, rowData.documentId);
        if (!entry) return null;

        const { isUpdate, source, target } = isUpdateEntry(rowData.contentType, rowData.documentId);

        return (
            <Button
                icon="pi pi-eye"
                className="p-button-text p-button-sm"
                tooltip="View Details"
                onClick={() => {
                    if (isUpdate) {
                        openEditorDialog(null, true, source, target, "View Differences");
                    } else {
                        openEditorDialog(entry, false, null, null, "View Content");
                    }
                }}
            />
        );
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
                <Card>
                    <TabView>
                        <TabPanel header={`All Items (${totalItems})`}>
                            <DataTable 
                                value={allItems}
                                paginator 
                                rows={10} 
                                rowsPerPageOptions={[5, 10, 25, 50]}
                                sortField="contentType"
                                sortOrder={1}
                                filterDisplay="row"
                                emptyMessage="No items selected for synchronization."
                                className="p-datatable-sm"
                            >
                                <Column 
                                    field="contentType" 
                                    header="Content Type" 
                                    sortable 
                                    filter 
                                    filterPlaceholder="Search by content type"
                                    style={{ width: '20%' }}
                                />
                                <Column 
                                    field="operation" 
                                    header="Operation" 
                                    sortable 
                                    filter 
                                    filterPlaceholder="Search by operation"
                                    style={{ width: '10%' }}
                                    body={rowData => renderOperationBadge(rowData.operation)}
                                />
                                <Column 
                                    header="Content"
                                    body={renderContent}
                                    style={{ width: '50%' }}
                                />
                                <Column 
                                    header="Status" 
                                    body={renderStatus}
                                    style={{ width: '10%' }}
                                />
                                <Column 
                                    header="Actions" 
                                    body={renderViewButton}
                                    style={{ width: '10%' }}
                                />
                            </DataTable>
                        </TabPanel>

                        <TabPanel header={`Create (${totalToCreate})`}>
                            <DataTable 
                                value={createItems}
                                paginator 
                                rows={10} 
                                rowsPerPageOptions={[5, 10, 25, 50]}
                                sortField="contentType"
                                sortOrder={1}
                                filterDisplay="row"
                                emptyMessage="No items to create."
                                className="p-datatable-sm"
                            >
                                <Column 
                                    field="contentType" 
                                    header="Content Type" 
                                    sortable 
                                    filter 
                                    filterPlaceholder="Search by content type"
                                    style={{ width: '20%' }}
                                />
                                <Column 
                                    header="Content"
                                    body={renderContent}
                                    style={{ width: '60%' }}
                                />
                                <Column 
                                    header="Status" 
                                    body={renderStatus}
                                    style={{ width: '10%' }}
                                />
                                <Column 
                                    header="Actions" 
                                    body={renderViewButton}
                                    style={{ width: '10%' }}
                                />
                            </DataTable>
                        </TabPanel>

                        <TabPanel header={`Update (${totalToUpdate})`}>
                            <DataTable 
                                value={updateItems}
                                paginator 
                                rows={10} 
                                rowsPerPageOptions={[5, 10, 25, 50]}
                                sortField="contentType"
                                sortOrder={1}
                                filterDisplay="row"
                                emptyMessage="No items to update."
                                className="p-datatable-sm"
                            >
                                <Column 
                                    field="contentType" 
                                    header="Content Type" 
                                    sortable 
                                    filter 
                                    filterPlaceholder="Search by content type"
                                    style={{ width: '20%' }}
                                />
                                <Column 
                                    header="Content" 
                                    body={renderContent}
                                    style={{ width: '60%' }}
                                />
                                <Column 
                                    header="Status" 
                                    body={renderStatus}
                                    style={{ width: '10%' }}
                                />
                                <Column 
                                    header="Actions" 
                                    body={renderViewButton}
                                    style={{ width: '10%' }}
                                />
                            </DataTable>
                        </TabPanel>

                        <TabPanel header={`Delete (${totalToDelete})`}>
                            <DataTable 
                                value={deleteItems}
                                paginator 
                                rows={10} 
                                rowsPerPageOptions={[5, 10, 25, 50]}
                                sortField="contentType"
                                sortOrder={1}
                                filterDisplay="row"
                                emptyMessage="No items to delete."
                                className="p-datatable-sm"
                            >
                                <Column 
                                    field="contentType" 
                                    header="Content Type" 
                                    sortable 
                                    filter 
                                    filterPlaceholder="Search by content type"
                                    style={{ width: '20%' }}
                                />
                                <Column 
                                    header="Content" 
                                    body={renderContent}
                                    style={{ width: '60%' }}
                                />
                                <Column 
                                    header="Status" 
                                    body={renderStatus}
                                    style={{ width: '10%' }}
                                />
                                <Column 
                                    header="Actions" 
                                    body={renderViewButton}
                                    style={{ width: '10%' }}
                                />
                            </DataTable>
                        </TabPanel>
                    </TabView>
                </Card>
            )}

            {/* Sync Progress Section */}
            {(syncInProgress || syncCompleted || syncFailed) && (
                <div className="my-4 p-3 border-1 border-round surface-border">
                    <h4 className="mb-3">Synchronization Progress</h4>

                    {syncProgress && (
                        <div className="mb-3">
                            <div className="flex justify-content-between align-items-center mb-2">
                                <span className="font-medium">
                                    {syncProgress.processedItems} of {syncProgress.totalItems} items processed
                                </span>
                                <span className="font-medium">
                                    {Math.round((syncProgress.processedItems / syncProgress.totalItems) * 100)}%
                                </span>
                            </div>
                            <ProgressBar 
                                value={Math.round((syncProgress.processedItems / syncProgress.totalItems) * 100)} 
                                showValue={false}
                                className="mb-2"
                            />

                            <div className="p-2 border-1 border-round surface-border mb-3">
                                <div className="flex align-items-center">
                                    <i className={`pi ${syncProgress.status === 'IN_PROGRESS' ? 'pi-spin pi-spinner' : syncProgress.status === 'SUCCESS' ? 'pi-check-circle text-success' : 'pi-times-circle text-danger'} mr-2`}></i>
                                    <span className="font-medium">{syncProgress.currentItem}</span>
                                </div>
                                {syncProgress.message && (
                                    <div className="mt-2 p-2 bg-red-50 text-red-700 border-round">
                                        {syncProgress.message}
                                    </div>
                                )}
                            </div>
                        </div>
                    )}

                    {/* Status messages */}
                    {syncCompleted && (
                        <Message severity="success" text="Synchronization completed successfully!" className="w-full mb-3" />
                    )}
                    {syncFailed && (
                        <Message severity="error" text="Synchronization failed. Please check the logs for details." className="w-full mb-3" />
                    )}
                </div>
            )}

            <div className="flex flex-column align-items-center my-5">
                {status === 'COMPLETED' && !syncInProgress && !syncFailed && (
                    <div className="mb-3">
                        <Message
                            severity="success"
                            text="Merge request completed successfully!"
                            className="w-full"
                        />
                    </div>
                )}
                {/*{status !== 'COMPLETED' && !syncInProgress && !syncCompleted && (*/}
                    <Button
                        label="Complete Merge"
                        icon="pi pi-check"
                        loading={completing}
                        // disabled={completing || status !== 'MERGED_COLLECTIONS'}
                        onClick={handleCompleteMerge}
                    />
                {/*)}*/}
            </div>

            {/* Toast for notifications */}
            <Toast ref={toast} position="top-right" />

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
