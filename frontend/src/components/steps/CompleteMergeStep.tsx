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
import {MergeRequestSelectionDTO, MergeRequestData, MergeRequestSelection, StrapiContent, SyncPlanDTO} from '../../types';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import EditorDialog from '../common/EditorDialog';
import SyncPlanGraph from './components/SyncPlanGraph';


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
    const totalToCreate = selections.reduce((sum, dto) => sum + ((dto.selections || []).filter(s => s.direction === 'TO_CREATE').length), 0);
    const totalToUpdate = selections.reduce((sum, dto) => sum + ((dto.selections || []).filter(s => s.direction === 'TO_UPDATE').length), 0);
    const totalToDelete = selections.reduce((sum, dto) => sum + ((dto.selections || []).filter(s => s.direction === 'TO_DELETE').length), 0);
    const totalItems = totalToCreate + totalToUpdate + totalToDelete;

    // State for editor modal
    const [editorDialogVisible, setEditorDialogVisible] = useState<boolean>(false);
    const [editorContent, setEditorContent] = useState<any>(null);
    const [isDiffEditor, setIsDiffEditor] = useState<boolean>(false);
    const [originalContent, setOriginalContent] = useState<any>(null);
    const [modifiedContent, setModifiedContent] = useState<any>(null);
    const [editorDialogHeader, setEditorDialogHeader] = useState<string>("View Content");
    const [editorErrorMessage, setEditorErrorMessage] = useState<string | undefined>(undefined);

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

    // Sync plan state
    const [syncPlan, setSyncPlan] = useState<SyncPlanDTO | null>(null);
    const [planLoading, setPlanLoading] = useState<boolean>(false);
    const [planError, setPlanError] = useState<string | null>(null);

    // Load sync plan to preview order before starting
    const loadPlan = () => {
        const urlParts = window.location.pathname.split('/');
        const last = urlParts[urlParts.length - 1];
        const mergeRequestId = parseInt(last, 10);
        if (isNaN(mergeRequestId)) return;
        setPlanLoading(true);
        setPlanError(null);
        fetch(`/api/merge-requests/${mergeRequestId}/sync-plan`)
            .then(res => {
                if (!res.ok) throw new Error(`Failed to load sync plan (HTTP ${res.status})`);
                return res.json();
            })
            .then((data: SyncPlanDTO) => setSyncPlan(data))
            .catch(err => setPlanError(err.message || 'Failed to load sync plan'))
            .finally(() => setPlanLoading(false));
    };

    useEffect(() => {
        loadPlan();
        // re-load if selection counts change
    }, [selections?.length]);

    // Align initial node statuses with existing selection state when graph loads
    useEffect(() => {
        if (!syncPlan || syncInProgress) return;
        // Build key set of plan items
        const planKeys = new Set<string>();
        (syncPlan.batches || []).forEach(batch => {
            batch.forEach(it => {
                planKeys.add(`${it.tableName}:${it.documentId}`);
            });
        });
        const initial: Record<string, { status: string; message?: string }> = {};
        (selections || []).forEach(dto => {
            (dto.selections || []).forEach(sel => {
                const key = `${dto.tableName}:${sel.documentId}`;
                if (!planKeys.has(key)) return;
                if (sel.syncSuccess === true) {
                    initial[key] = { status: 'SUCCESS' };
                } else if (sel.syncSuccess === false) {
                    initial[key] = { status: 'ERROR', message: sel.syncFailureResponse || undefined };
                }
            });
        });
        // Merge with existing statuses without overriding live SSE updates
        setSyncItemsStatus(prev => ({ ...initial, ...prev }));
    }, [syncPlan, selections, syncInProgress]);

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

    const isDevelopment = window.location.hostname === 'localhost' && window.location.port === '3000';

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
        let sseUrl = `/api/sync-progress/${mergeRequestId}`;
        if(isDevelopment)
            sseUrl = `http://localhost:8080${sseUrl}`;
        const eventSource = new EventSource(sseUrl);

        // Handle connection open
        eventSource.onopen = () => {
            console.log('SSE connection established');
        };

        // Handle connected event
        eventSource.addEventListener('connected', (event) => {
            console.log('SSE connected event received:', event);
        });

        // Handle default message events (Ktor SSE sends unnamed events by default)
        eventSource.onmessage = (event) => {
            if (!event.data) return;
            if (event.data === 'connected') return;
            if (event.data === 'heartbeat') return;
            console.log('SSE message received:', event);
            try {
                const update: SyncProgressUpdate = JSON.parse(event.data);
                console.log('Received sync progress update (message):', update);

                // Update sync progress state
                setSyncProgress(update);

                // Update item status (use composite key table:documentId)
                if (update.currentItem && update.currentItem !== 'Starting synchronization' && 
                    update.currentItem !== 'Processing content types' && 
                    update.currentItem !== 'Content types processed' &&
                    update.currentItem !== 'Synchronization completed' &&
                    update.currentItem !== 'Synchronization failed') {
                    // Expect format "table:documentId"
                    const parts = update.currentItem.split(':');
                    const key = parts.length >= 2 ? `${parts[0]}:${parts.slice(1).join(':')}` : update.currentItem;
                    setSyncItemsStatus(prev => ({
                        ...prev,
                        [key]: {
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
        };

        // Backward compatibility: handle named 'progress' events if server uses them
        eventSource.addEventListener('progress', (event) => {
            try {
                const update: SyncProgressUpdate = JSON.parse(event.data);
                console.log('Received sync progress update (progress event):', update);

                // Update sync progress state
                setSyncProgress(update);

                // Update item status (use composite key table:documentId)
                if (update.currentItem && update.currentItem !== 'Starting synchronization' && 
                    update.currentItem !== 'Processing content types' && 
                    update.currentItem !== 'Content types processed' &&
                    update.currentItem !== 'Synchronization completed' &&
                    update.currentItem !== 'Synchronization failed') {
                    // Expect format "table:documentId"
                    const parts = update.currentItem.split(':');
                    const key = parts.length >= 2 ? `${parts[0]}:${parts.slice(1).join(':')}` : update.currentItem;
                    setSyncItemsStatus(prev => ({
                        ...prev,
                        [key]: {
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
        if (contentType === 'files') {
            // Check ONLY_IN_SOURCE
            const sourceFile = allMergeData.files.find(f => f.compareState === 'ONLY_IN_SOURCE' && f.sourceImage?.metadata.documentId === documentId)?.sourceImage;
            if (sourceFile) return sourceFile;

            // Check DIFFERENT
            const diffEntry = allMergeData.files.find(f => f.compareState === 'DIFFERENT' && f.sourceImage?.metadata.documentId === documentId);
            if (diffEntry?.sourceImage) return diffEntry.sourceImage;

            // Check ONLY_IN_TARGET
            const targetFile = allMergeData.files.find(f => f.compareState === 'ONLY_IN_TARGET' && f.targetImage?.metadata.documentId === documentId)?.targetImage;
            if (targetFile) return targetFile;

            return null;
        }

        // Handle single content types
        if (allMergeData.singleTypes[contentType]) {
            const singleType = allMergeData.singleTypes[contentType];

            if (singleType.sourceContent && singleType.sourceContent.metadata.documentId === documentId) {
                return singleType.sourceContent.cleanData;
            }
            if (singleType.targetContent && singleType.targetContent.metadata.documentId === documentId) {
                return singleType.targetContent.cleanData;
            }
            return null;
        }

        // Handle collection content types (list-based)
        if (allMergeData.collectionTypes[contentType]) {
            const entries = allMergeData.collectionTypes[contentType];

            // ONLY_IN_SOURCE -> return source
            const src = entries.find(e => e.compareState === 'ONLY_IN_SOURCE' && e.sourceContent?.metadata.documentId === documentId)?.sourceContent;
            if (src) return src.cleanData;

            // DIFFERENT -> return source
            const diff = entries.find(e => e.compareState === 'DIFFERENT' && e.sourceContent?.metadata.documentId === documentId)?.sourceContent;
            if (diff) return diff.cleanData;

            // ONLY_IN_TARGET -> return target
            const tgt = entries.find(e => e.compareState === 'ONLY_IN_TARGET' && e.targetContent?.metadata.documentId === documentId)?.targetContent;
            if (tgt) return tgt.cleanData;

            // IDENTICAL -> return either
            const ident = entries.find(e => e.compareState === 'IDENTICAL' && ((e.sourceContent && e.sourceContent.metadata.documentId === documentId) || (e.targetContent && e.targetContent.metadata.documentId === documentId)));
            if (ident) return ident.sourceContent?.cleanData ?? ident.targetContent?.cleanData;

            return null;
        }

        return null;
    };

    // Function to find status info for a document
    const findStatusInfo = (contentType: string, documentId: string, operation: 'create' | 'update' | 'delete'): MergeRequestSelection | undefined => {
        const dto = selections.find(s => s.tableName === contentType);
        if (!dto) return undefined;
        const dir = operation === 'create' ? 'TO_CREATE' : operation === 'update' ? 'TO_UPDATE' : 'TO_DELETE';
        return (dto.selections || []).find(s => s.documentId === documentId && s.direction === dir);
    };

    // Function to render status badge
    const renderStatusBadge = (status: MergeRequestSelection | undefined, contentType: string, documentId: string) => {
        // Check if we have live status from SSE (composite key table:documentId)
        const liveStatus = syncItemsStatus[`${contentType}:${documentId}`];

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
        if (contentType === 'files') {
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
        const attributes = getRepresentativeAttributes(entry as StrapiContent);

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
    const openEditorDialog = (
        content: any,
        isDiff: boolean = false,
        source: any = null,
        target: any = null,
        header: string = "View Content",
        tableName?: string,
        documentId?: string
    ) => {
        if (isDiff) {
            setIsDiffEditor(true);
            setOriginalContent(source);
            setModifiedContent(target);
            setEditorErrorMessage(undefined);
        } else {
            setIsDiffEditor(false);
            setEditorContent(content);
            // compute error message from live status or stored selection failure
            if (tableName && documentId) {
                const key = `${tableName}:${documentId}`;
                const live = syncItemsStatus[key];
                let err: string | undefined = undefined;
                if (live && live.status === 'ERROR') {
                    err = live.message || 'Unknown error';
                } else {
                    const dto = selections.find(s => s.tableName === tableName);
                    const sel = dto?.selections?.find(s => s.documentId === documentId && s.syncSuccess === false);
                    if (sel && sel.syncFailureResponse) err = sel.syncFailureResponse;
                }
                setEditorErrorMessage(err);
            } else {
                setEditorErrorMessage(undefined);
            }
        }
        setEditorDialogHeader(header);
        setEditorDialogVisible(true);
    };

    // Function to determine if an entry is an update (has both source and target)
    const isUpdateEntry = (contentType: string, documentId: string): { isUpdate: boolean, source: any, target: any } => {
        if (!allMergeData) return { isUpdate: false, source: null, target: null };

        // Handle file content type
        if (contentType === 'files') {
            const diffEntry = allMergeData.files.find(f => f.compareState === 'DIFFERENT' && f.sourceImage?.metadata.documentId === documentId);
            if (diffEntry && diffEntry.sourceImage && diffEntry.targetImage) return { isUpdate: true, source: diffEntry.sourceImage, target: diffEntry.targetImage };
        }

        // Handle single content types
        if (allMergeData.singleTypes[contentType]) {
            const singleType = allMergeData.singleTypes[contentType];
            if (singleType.compareState === 'DIFFERENT' && singleType.sourceContent && singleType.targetContent && singleType.sourceContent.metadata.documentId === documentId) {
                return { isUpdate: true, source: singleType.sourceContent.cleanData, target: singleType.targetContent.cleanData};
            }
        }

        // Handle collection content types (list-based)
        if (allMergeData.collectionTypes[contentType]) {
            const entries = allMergeData.collectionTypes[contentType];
            const diffEntry = entries.find(e => e.compareState === 'DIFFERENT' && e.sourceContent?.metadata.documentId === documentId);
            if (diffEntry && diffEntry.sourceContent && diffEntry.targetContent) return { isUpdate: true, source: diffEntry.sourceContent.cleanData, target: diffEntry.targetContent.cleanData };
        }

        return { isUpdate: false, source: null, target: null };
    };

    // Prepare data for DataTable
    const prepareItemsForDataTable = () => {
        const items: any[] = [];

        selections.forEach(dto => {
            (dto.selections || []).forEach(sel => {
                const operation = sel.direction === 'TO_CREATE' ? 'create' : sel.direction === 'TO_UPDATE' ? 'update' : 'delete';
                items.push({
                    contentType: dto.tableName,
                    documentId: sel.documentId,
                    operation,
                    statusInfo: findStatusInfo(dto.tableName, sel.documentId, operation)
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
                        openEditorDialog(entry, false, null, null, "View Content", rowData.contentType, rowData.documentId);
                    }
                }}
            />
        );
    };

    const inspectItem = (tableName: string, documentId: string) => {
        const entry = findEntry(tableName, documentId);
        if (!entry) return;
        const diff = isUpdateEntry(tableName, documentId);
        if (diff.isUpdate) {
            openEditorDialog(null, true, diff.source, diff.target, 'View Differences');
        } else {
            openEditorDialog(entry, false, null, null, 'View Content', tableName, documentId);
        }
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

            {/* Execution Plan (batches with live status) */}
            <TabView>
                <TabPanel header="Graph">
            <Card className="mb-4">
                <div className="flex align-items-center justify-content-between mb-3">
                    <h4 className="m-0">Execution Plan</h4>
                    <div className="flex gap-2">
                        <Button label="Recompute Plan" icon="pi pi-refresh" className="p-button-text" onClick={loadPlan} disabled={planLoading} />
                    </div>
                </div>
                {planLoading && (
                    <Message severity="info" text="Loading sync plan..." className="w-full mb-3" />
                )}
                {planError && (
                    <Message severity="error" text={planError} className="w-full mb-3" />
                )}
                {!planLoading && !planError && syncPlan && (
                    <div>
                        {syncPlan.batches.length === 0 ? (
                            <Message severity="info" text="No content dependencies to resolve. Items will be processed directly." className="w-full" />
                        ) : (
                            <div>
                                <p className="text-600 mb-3">The graph groups items by table. Each table has a single container listing its selected items; item colors indicate the operation (create/update/delete), and the icon next to each id shows the live status. Arrows represent dependencies; dashed arrows indicate circular dependencies handled in a second pass.</p>
                                <SyncPlanGraph syncPlan={syncPlan} syncItemsStatus={syncItemsStatus} onInspect={inspectItem} />
                            </div>
                        )}

                        {syncPlan.missingDependencies.length > 0 && (
                            <div className="mt-3">
                                <Message severity="warn" text="Some items have missing dependencies. They may fail during synchronization." className="w-full mb-2" />
                                <ul className="pl-3">
                                    {syncPlan.missingDependencies.map((m, midx) => (
                                        <li key={midx} className="mb-1">
                                            <span className="font-medium">{m.fromTable}:{m.fromDocumentId}</span>
                                            <span className="ml-2">requires</span>
                                            <Tag value={`${m.linkTargetTable}.${m.linkField}`} className="ml-2 mr-2" />
                                            <span>- {m.reason}</span>
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}

                        {syncPlan.circularEdges.length > 0 && (
                            <div className="mt-3">
                                <Message severity="info" text="Detected circular dependencies. These are handled with a second pass when possible." className="w-full mb-2" />
                                <ul className="pl-3">
                                    {syncPlan.circularEdges.map((c, cidx) => (
                                        <li key={cidx} className="mb-1">
                                            <span className="font-medium">{c.fromTable}:{c.fromDocumentId}</span>
                                            <i className="pi pi-arrow-right mx-2"></i>
                                            <span className="font-medium">{c.toTable}:{c.toDocumentId}</span>
                                            <Tag value={`via ${c.viaField}`} className="ml-2" />
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </div>
                )}
            </Card>
                </TabPanel>


                <TabPanel header="Data">
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
                </TabPanel>
            </TabView>

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
                {(status === 'MERGED_COLLECTIONS' || status === 'FAILED'|| status === 'IN_PROGRESS' || status === 'REVIEW') && !syncInProgress && (
                    <Button
                        label="Complete Merge"
                        icon="pi pi-check"
                        loading={completing}
                        disabled={completing}
                        onClick={handleCompleteMerge}
                    />
                )}
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
                errorMessage={editorErrorMessage}
            />
        </div>
    );
};

export default CompleteMergeStep;
