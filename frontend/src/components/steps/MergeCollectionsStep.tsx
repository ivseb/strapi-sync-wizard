import React, {useEffect, useRef, useState} from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {TabPanel, TabView} from 'primereact/tabview';
import {ProgressSpinner} from 'primereact/progressspinner';
import {Badge} from 'primereact/badge';
import axios from 'axios';
import {Dialog} from 'primereact/dialog';
import {Card} from 'primereact/card';
import {DiffEditor, Editor} from "@monaco-editor/react";
import {Tooltip} from 'primereact/tooltip';
import {Chip} from 'primereact/chip';

// Import types
import {
    ContentTypesComparisonResultWithRelationships,
    EntryRelationship,
    MergeRequestData,
    MergeRequestSelectionDTO
} from '../../types';
import { findTargetEntry } from '../../utils/entryUtils';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import RelationshipCard from '../common/RelationshipCard';
import EditorDialog from '../common/EditorDialog';


interface MergeCollectionsStepProps {
    status: string;
    mergingCollections: boolean;
    mergeCollections: () => void;
    mergeRequestId: number;
    collectionTypesData?: Record<string, ContentTypesComparisonResultWithRelationships>;
    selections?: MergeRequestSelectionDTO[];
    contentTypeRelationships?: any[];
    loading?: boolean;
    allMergeData?: MergeRequestData;
    updateSingleSelection?: (contentType: string, documentId: string, direction: string, isSelected: boolean) => Promise<boolean>;
}

// Use the imported types instead of redefining them
// ContentTypesComparisonResultWithRelationships, EntryRelationship are imported from '../../types'

// Use the imported ContentTypesComparisonResultWithRelationships instead of redefining it

interface SelectedContentTypeEntries {
    contentType: string;
    entriesToCreate: any[];
    entriesToUpdate: any[];
    entriesToDelete: any[];
}

const MergeCollectionsStep: React.FC<MergeCollectionsStepProps> = ({
                                                                       status,
                                                                       mergingCollections,
                                                                       mergeCollections,
                                                                       mergeRequestId,
                                                                       collectionTypesData,
                                                                       selections,
                                                                       contentTypeRelationships,
                                                                       loading: parentLoading,
                                                                       allMergeData,
                                                                       updateSingleSelection
                                                                   }) => {
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [contentTypes, setContentTypes] = useState<Record<string, ContentTypesComparisonResultWithRelationships>>({});
    const [collectionTypes, setCollectionTypes] = useState<ContentTypesComparisonResultWithRelationships[]>([]);
    const [selectedEntries, setSelectedEntries] = useState<Record<string, SelectedContentTypeEntries>>({});
    const [activeTabIndex, setActiveTabIndex] = useState<number>(0);
    const [activeContentType, setActiveContentType] = useState<string | null>(null);

    // State for expanded rows
    const [expandedContentTypes, setExpandedContentTypes] = useState<Record<string, boolean>>({});
    const [expandedCreateEntries, setExpandedCreateEntries] = useState<Record<string, boolean>>({});
    const [expandedUpdateEntries, setExpandedUpdateEntries] = useState<Record<string, boolean>>({});
    const [expandedDeleteEntries, setExpandedDeleteEntries] = useState<Record<string, boolean>>({});
    const [expandedIdenticalEntries, setExpandedIdenticalEntries] = useState<Record<string, boolean>>({});

    // Loading states for each table
    const [contentTypesTableLoading, setContentTypesTableLoading] = useState<boolean>(false);
    const [createTableLoading, setCreateTableLoading] = useState<boolean>(false);
    const [updateTableLoading, setUpdateTableLoading] = useState<boolean>(false);
    const [deleteTableLoading, setDeleteTableLoading] = useState<boolean>(false);

    // State for editor modal
    const [editorDialogVisible, setEditorDialogVisible] = useState<boolean>(false);
    const [editorContent, setEditorContent] = useState<any>(null);
    const [isDiffEditor, setIsDiffEditor] = useState<boolean>(false);
    const [originalContent, setOriginalContent] = useState<any>(null);
    const [modifiedContent, setModifiedContent] = useState<any>(null);
    const [editorDialogHeader, setEditorDialogHeader] = useState<string>("View Content");

    // Use refs to track the latest values for the cleanup function
    const selectedEntriesRef = useRef(selectedEntries);
    const contentTypesRef = useRef(contentTypes);
    const errorRef = useRef(error);

    // Update refs when values change
    useEffect(() => {
        selectedEntriesRef.current = selectedEntries;
    }, [selectedEntries]);

    useEffect(() => {
        contentTypesRef.current = contentTypes;
    }, [contentTypes]);

    useEffect(() => {
        errorRef.current = error;
    }, [error]);

    const isDisabled = mergingCollections ||
        (status !== 'MERGED_SINGLES' &&
            status !== 'MERGED_COLLECTIONS' &&
            status !== 'COMPLETED');

    // Use collectionTypesData from props if available, otherwise show loading state
    useEffect(() => {
        // If collectionTypesData is provided from parent, use it
        if (collectionTypesData) {
            // Set content types
            setContentTypes(collectionTypesData);

            // Convert the map to an array
            const collectionTypesArray = Object.values(collectionTypesData) as ContentTypesComparisonResultWithRelationships[];
            setCollectionTypes(collectionTypesArray);

            // Initialize selected entries
            const initialSelectedEntries: Record<string, SelectedContentTypeEntries> = {};

            // First initialize with empty arrays for all content types
            collectionTypesArray.forEach((ct: ContentTypesComparisonResultWithRelationships) => {
                initialSelectedEntries[ct.contentType] = {
                    contentType: ct.contentType,
                    entriesToCreate: [],
                    entriesToUpdate: [],
                    entriesToDelete: []
                };
            });

            // Then populate with selections from the props
            if (selections && selections.length > 0) {
                selections.forEach((selection: any) => {
                    if (initialSelectedEntries[selection.contentType]) {
                        const contentType = selection.contentType;
                        const contentTypeData = collectionTypesData[contentType];

                        // Filter the comparison results to match the selected entries
                        const entriesToCreate = contentTypeData.onlyInSource.filter(
                            entry => selection.entriesToCreate.includes(entry.metadata?.documentId)
                        );

                        const entriesToUpdate = contentTypeData.different.filter(
                            entry => selection.entriesToUpdate.includes(entry.source?.metadata?.documentId)
                        );

                        const entriesToDelete = contentTypeData.onlyInTarget.filter(
                            entry => selection.entriesToDelete.includes(entry.metadata?.documentId)
                        );

                        initialSelectedEntries[contentType] = {
                            ...initialSelectedEntries[contentType],
                            entriesToCreate: entriesToCreate,
                            entriesToUpdate: entriesToUpdate,
                            entriesToDelete: entriesToDelete
                        };
                    }
                });
            }

            setSelectedEntries(initialSelectedEntries);
            setLoading(false);
            setError(null);
        } else {
            // If parent is still loading, show loading state
            setLoading(true);
        }
    }, [mergeRequestId, collectionTypesData, selections]);

    // Function to save selected entries to the backend - no longer needed as we're tracking selections individually
    const saveSelectedEntries = async () => {
        // This function is kept for compatibility but no longer does anything
        return true;
    };

    // No need to save selections when the component unmounts as they're already saved
    useEffect(() => {
        return () => {
            // Cleanup function - no longer needs to save selections
        };
    }, []);

    // Check if a content type has dependencies
    const hasDependencies = (contentType: ContentTypesComparisonResultWithRelationships): boolean => {
        return !!((contentType.dependsOn && contentType.dependsOn.length > 0) ||
            (contentType.relationships && Object.keys(contentType.relationships || {}).length > 0));
    };

    // Check if a dependency is being resolved
    const isDependencyResolved = (contentType: string, entryId?: string): boolean => {
        if (entryId && entryId !== 'any') {
            // For specific entry dependencies, check if that entry is selected
            return Object.values(selectedEntries).some(entry =>
                entry.contentType === contentType &&
                (entry.entriesToCreate.some(e => e.metadata?.documentId === entryId) ||
                    entry.entriesToUpdate.some(e => e.source?.metadata?.documentId === entryId || e.source?.id === entryId))
            );
        } else {
            // For content type level dependencies, check if any entry of that type is selected
            return Object.values(selectedEntries).some(entry =>
                entry.contentType === contentType &&
                (entry.entriesToCreate.length > 0 || entry.entriesToUpdate.length > 0)
            );
        }
    };


    // Function to fetch the latest selections from the backend
    const fetchLatestSelections = async () => {
        try {
            const response = await axios.get(`/api/merge-requests/${mergeRequestId}`);
            if (response.data && response.data.mergeRequestData && response.data.mergeRequestData.selections) {
                const latestSelections = response.data.mergeRequestData.selections;

                // Initialize selected entries with the latest data from backend
                const initialSelectedEntries: Record<string, SelectedContentTypeEntries> = {};

                // First initialize with empty arrays for all content types
                collectionTypes.forEach((ct: ContentTypesComparisonResultWithRelationships) => {
                    initialSelectedEntries[ct.contentType] = {
                        contentType: ct.contentType,
                        entriesToCreate: [],
                        entriesToUpdate: [],
                        entriesToDelete: []
                    };
                });

                // Then populate with selections from the response
                if (latestSelections && latestSelections.length > 0) {
                    latestSelections.forEach((selection: any) => {
                        if (initialSelectedEntries[selection.contentType]) {
                            const contentType = selection.contentType;
                            const contentTypeData = contentTypes[contentType];

                            if (contentTypeData) {
                                // Filter the comparison results to match the selected entries
                                const entriesToCreate = contentTypeData.onlyInSource.filter(
                                    entry => selection.entriesToCreate.includes(entry.metadata?.documentId)
                                );

                                const entriesToUpdate = contentTypeData.different.filter(
                                    entry => selection.entriesToUpdate.includes(entry.source?.metadata?.documentId)
                                );

                                const entriesToDelete = contentTypeData.onlyInTarget.filter(
                                    entry => selection.entriesToDelete.includes(entry.metadata?.documentId)
                                );

                                initialSelectedEntries[contentType] = {
                                    ...initialSelectedEntries[contentType],
                                    entriesToCreate: entriesToCreate,
                                    entriesToUpdate: entriesToUpdate,
                                    entriesToDelete: entriesToDelete
                                };
                            }
                        }
                    });
                }

                // Update the state with the latest selections
                setSelectedEntries(initialSelectedEntries);
            }
        } catch (err) {
            console.error('Error fetching latest selections:', err);
        }
    };

    // Handle selection change for a specific content type
    const handleSelectionChange = async (contentType: string, type: 'create' | 'update' | 'delete', selection: any[]) => {
        const contentTypeObj = collectionTypes.find(ct => ct.contentType === contentType);
        const currentSelections = selectedEntries[contentType][type === 'create' ? 'entriesToCreate' : type === 'update' ? 'entriesToUpdate' : 'entriesToDelete'];

        // Find added and removed entries
        const addedEntries = selection.filter(entry => {
            const entryId = entry.metadata?.documentId || entry.source?.metadata?.documentId
            return !currentSelections.some(e => {
                const currentId = e.metadata?.documentId || entry.source?.metadata?.documentId
                return currentId === entryId;
            });
        });

        const removedEntries = currentSelections.filter(entry => {
            const entryId =  entry.metadata?.documentId || entry.source?.metadata?.documentId
            return !selection.some(e => {
                const newId =  e.metadata?.documentId || entry.source?.metadata?.documentId
                return newId === entryId;
            });
        });

        // Create a copy of the selected entries that we'll update
        let newSelectedEntries = {
            ...selectedEntries,
            [contentType]: {
                ...selectedEntries[contentType],
                [type === 'create' ? 'entriesToCreate' : type === 'update' ? 'entriesToUpdate' : 'entriesToDelete']: selection
            }
        };

        // Get the direction based on the type
        const direction = type === 'create' ? 'TO_CREATE' : type === 'update' ? 'TO_UPDATE' : 'TO_DELETE';

        // Set loading state based on the type
        if (type === 'create') {
            setCreateTableLoading(true);
        } else if (type === 'update') {
            setUpdateTableLoading(true);
        } else if (type === 'delete') {
            setDeleteTableLoading(true);
        }

        try {
            // Process added entries
            for (const entry of addedEntries) {
                const entryId = entry.metadata?.documentId || entry.source?.metadata?.documentId
                if (entryId) {
                    let success = false;

                    // Use the updateSingleSelection prop if available
                    if (updateSingleSelection) {
                        success = await updateSingleSelection(contentType, entryId, direction, true);
                    } else {
                        // Fallback to direct API call
                        try {
                            const response = await axios.post(`/api/merge-requests/${mergeRequestId}/selection`, {
                                contentType,
                                documentId: entryId,
                                direction,
                                isSelected: true
                            });
                            success = response.data.success;
                        } catch (err) {
                            console.error(`Error adding selection for ${entryId}:`, err);
                        }
                    }

                    // If the API call failed, we should not update the state
                    if (!success) {
                        // Clear loading state
                        if (type === 'create') {
                            setCreateTableLoading(false);
                        } else if (type === 'update') {
                            setUpdateTableLoading(false);
                        } else if (type === 'delete') {
                            setDeleteTableLoading(false);
                        }
                        return;
                    }
                }
            }

            // Process removed entries
            for (const entry of removedEntries) {
                const entryId = entry.metadata?.documentId || entry.source?.metadata?.documentId
                if (entryId) {
                    let success = false;

                    // Use the updateSingleSelection prop if available
                    if (updateSingleSelection) {
                        success = await updateSingleSelection(contentType, entryId, direction, false);
                    } else {
                        // Fallback to direct API call
                        try {
                            const response = await axios.post(`/api/merge-requests/${mergeRequestId}/selection`, {
                                contentType,
                                documentId: entryId,
                                direction,
                                isSelected: false
                            });
                            success = response.data.success;
                        } catch (err) {
                            console.error(`Error removing selection for ${entryId}:`, err);
                        }
                    }

                    // If the API call failed, we should not update the state
                    if (!success) {
                        // Clear loading state
                        if (type === 'create') {
                            setCreateTableLoading(false);
                        } else if (type === 'update') {
                            setUpdateTableLoading(false);
                        } else if (type === 'delete') {
                            setDeleteTableLoading(false);
                        }
                        return;
                    }
                }
            }

            // Temporarily update the local state with what we know
            setSelectedEntries(newSelectedEntries);

            // Fetch the latest selections from the backend to ensure we have the most up-to-date data
            await fetchLatestSelections();
        } catch (err) {
            console.error(`Error updating selections:`, err);
        } finally {
            // Clear loading state
            if (type === 'create') {
                setCreateTableLoading(false);
            } else if (type === 'update') {
                setUpdateTableLoading(false);
            } else if (type === 'delete') {
                setDeleteTableLoading(false);
            }
        }
    };

    // formatJson function removed as it's now handled by EditorDialog component

    // Using the shared getRepresentativeAttributes function from attributeUtils.ts

    // Render representative attributes
    const renderRepresentativeAttributes = (rowData: any) => {
        // For collection types, the entry data is in rawData
        let entry;
        if (rowData.rawData) {
            // Direct access to rawData
            entry = rowData.rawData;
        } else if (rowData.source?.rawData) {
            // For different entries, access source.rawData
            entry = rowData.source.rawData;
        } else if (rowData.metadata) {
            // Fallback to metadata if rawData is not available
            entry = rowData.metadata;
        } else if (rowData.source?.metadata) {
            // Fallback to source.metadata if rawData is not available
            entry = rowData.source.metadata;
        } else {
            // Last resort, use the rowData itself
            entry = rowData;
        }

        if (!entry) return null;

        const attributes = getRepresentativeAttributes(entry);

        if (attributes.length === 0) return <span className="text-muted">No representative attributes</span>;

        return (
            <div>
                {attributes.map((attr, index) => (
                    <div key={index} className="mb-1">
                        <span className="font-bold">{attr.key}: </span>
                        <span>{attr.value}</span>
                    </div>
                ))}
            </div>
        );
    };

    // Use the shared findTargetEntry function from utils
    const findTargetEntryLocal = (relationship: EntryRelationship): { targetEntry: any, contentTypeKind: string } => {
        return findTargetEntry(relationship, allMergeData);
    };

    // Helper function to render relationship card
    const renderRelationshipCard = (relationship: EntryRelationship, index: number) => {
        const {targetEntry, contentTypeKind} = findTargetEntryLocal(relationship);

        if (!targetEntry) return null;

        // Check if the relationship is resolved by checking if the target content type is selected
        const isResolved = isDependencyResolved(relationship.targetContentType, relationship.targetDocumentId);

        return (
            <RelationshipCard
                relationship={relationship}
                index={index}
                targetEntry={targetEntry}
                isResolved={isResolved}
                getRepresentativeAttributes={getRepresentativeAttributes}
            />
        );
    };

    // Helper function to render the relationships section
    const renderRelationshipsSection = (documentId: string) => {
        if (!activeContentType) return <div>No related records</div>;

        const relationships = contentTypes[activeContentType].relationships?.[documentId];
        if (!relationships || relationships.length === 0) return <div>No related records</div>;

        return (
            <div className="p-3">
                <h5>Related Records</h5>
                <div className="grid">
                    {relationships.map((relationship: EntryRelationship, index: number) =>
                        renderRelationshipCard(relationship, index)
                    )}
                </div>
            </div>
        );
    };

    // Function to render relationship indicators
    const renderDependencyIndicator = (rowData: ContentTypesComparisonResultWithRelationships) => {
        if (!hasDependencies(rowData)) return null;

        const relationshipsCount = rowData.dependsOn?.length || 0;
        const resolvedCount = rowData.dependsOn?.filter(dep => isDependencyResolved(dep))?.length || 0;
        const allResolved = resolvedCount === relationshipsCount;

        return (
            <div className="flex align-items-center">
                <Tooltip target=".relationship-indicator"/>
                <Chip
                    label={`${resolvedCount}/${relationshipsCount} relationships resolved`}
                    icon={allResolved ? "pi pi-check" : "pi pi-link"}
                    className={`relationship-indicator mr-2 ${allResolved ? "p-chip-success" : "p-chip-warning"}`}
                    data-pr-tooltip="This content type has relationships that must be selected"
                    data-pr-position="top"
                />
            </div>
        );
    };

    // Function to render relationship details for content types
    const renderDependencyDetails = (rowData: ContentTypesComparisonResultWithRelationships) => {
        if (!hasDependencies(rowData)) return <div>No related records</div>;

        return (
            <div className="p-3">
                <h5>Related Records</h5>
                {rowData.relationships && Object.keys(rowData.relationships).length > 0 && (
                    <div className="grid">
                        {Object.entries(rowData.relationships).map(([entryId, relationships], index) => {
                            if (!relationships || relationships.length === 0) return null;

                            // Group relationships by target content type for better display
                            const groupedRelationships: Record<string, EntryRelationship[]> = {};
                            relationships.forEach((rel: EntryRelationship) => {
                                if (!groupedRelationships[rel.targetContentType]) {
                                    groupedRelationships[rel.targetContentType] = [];
                                }
                                groupedRelationships[rel.targetContentType].push(rel);
                            });

                            return (
                                <div key={index} className="col-12 mb-2">
                                    <div className="p-card p-3">
                                        <div className="flex align-items-center mb-2">
                                            <i className="pi pi-id-card mr-2"></i>
                                            <span className="font-bold">Entry ID: {entryId}</span>
                                        </div>
                                        <div className="grid">
                                            {Object.entries(groupedRelationships).map(([targetType, rels], idx) => {
                                                const allResolved = rels.every(rel => isDependencyResolved(rel.targetContentType, rel.targetDocumentId));
                                                return (
                                                    <div key={idx} className="col-12 md:col-6 lg:col-4 mb-2">
                                                        <div
                                                            className={`p-card p-2 ${allResolved ? 'bg-green-50' : 'bg-yellow-50'}`}>
                                                            <div className="flex align-items-center">
                                                                <i className={`pi ${allResolved ? 'pi-check-circle text-green-500' : 'pi-exclamation-circle text-yellow-500'} mr-2`}></i>
                                                                <span className="font-bold">{targetType}</span>
                                                            </div>
                                                            <div className="mt-1">
                                                                <small>
                                                                    {rels.length} {rels.length === 1 ? 'reference' : 'references'} via {rels[0].sourceField}
                                                                </small>
                                                            </div>
                                                            <div className="mt-1">
                                                                <small
                                                                    className={allResolved ? 'text-green-500' : 'text-yellow-500'}>
                                                                    {allResolved ? 'All resolved' : 'Some relationships not resolved'}
                                                                </small>
                                                            </div>
                                                        </div>
                                                    </div>
                                                );
                                            })}
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}
            </div>
        );
    };


    // This function is no longer needed as dependencies are automatically selected
    // Kept as a comment for reference
    /*
    const selectAllDependencies = () => {
      // Function removed as dependencies are now automatically selected
    };
    */

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

    // Loading state - consider both local and parent loading states
    if (loading || parentLoading) {
        return (
            <div className="flex justify-content-center align-items-center" style={{minHeight: '300px'}}>
                <ProgressSpinner/>
            </div>
        );
    }

    // Error state
    if (error) {
        return (
            <div>
                <h3>Merge Collections</h3>
                <Message severity="error" text={error} className="w-full mb-3"/>
            </div>
        );
    }

    // No content types
    if (collectionTypes.length === 0) {
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

    return (
        <div>
            <h3>Merge Collections</h3>
            <p>
                This step allows you to select which collection content types to create, update, or delete on the target
                instance.
                Review the differences and make your selections before proceeding.
            </p>
            <Message severity="info" className="mb-3" content={messageContent}/>

            <div className="mb-4">
                <Card>
                    <h4>Content Types</h4>
                    <DataTable
                        value={collectionTypes}
                        selectionMode="single"
                        selection={activeContentType ? collectionTypes.find(ct => ct.contentType === activeContentType) : undefined}
                        onSelectionChange={(e) => setActiveContentType(e.value?.contentType || null)}
                        dataKey="contentType"
                        className="mb-3"
                        paginator
                        rows={5}
                        rowsPerPageOptions={[5, 10, 25,50]}
                    >
                        <Column field="contentType" header="Content Type" sortable/>
                        <Column field="kind" header="Kind" sortable/>
                        <Column header="Only in Source" body={(rowData) => {
                            const selectedCount = selectedEntries[rowData.contentType]?.entriesToCreate?.length || 0;
                            return (
                                <Badge value={`${selectedCount}/${rowData.onlyInSource.length}`}
                                       severity={rowData.onlyInSource.length > 0 ? "warning" : "success"}/>
                            );
                        }}/>
                        <Column header="Only in Target" body={(rowData) => {
                            const selectedCount = selectedEntries[rowData.contentType]?.entriesToDelete?.length || 0;
                            return (
                                <Badge value={`${selectedCount}/${rowData.onlyInTarget.length}`}
                                       severity={rowData.onlyInTarget.length > 0 ? "warning" : "success"}/>
                            );
                        }}/>
                        <Column header="Different" body={(rowData) => {
                            const selectedCount = selectedEntries[rowData.contentType]?.entriesToUpdate?.length || 0;
                            return (
                                <Badge value={`${selectedCount}/${rowData.different.length}`}
                                       severity={rowData.different.length > 0 ? "danger" : "success"}/>
                            );
                        }}/>
                        <Column header="Identical" body={(rowData) => (
                            <Badge value={rowData.identical.length} severity="success"/>
                        )}/>
                    </DataTable>
                </Card>
            </div>

            {activeContentType && (
                <div className="mb-4">
                    <Card>
                        <h4>{activeContentType}</h4>
                        <TabView activeIndex={activeTabIndex} onTabChange={(e) => setActiveTabIndex(e.index)}>
                            {/* To Create Tab */}
                            <TabPanel header={`To Create (${contentTypes[activeContentType].onlyInSource.length})`}>
                                {contentTypes[activeContentType].onlyInSource.length === 0 ? (
                                    <Message severity="info" text="No entries to create" className="w-full"/>
                                ) : (
                                    <DataTable
                                        value={contentTypes[activeContentType].onlyInSource}
                                        selectionMode="multiple"
                                        loading={createTableLoading}
                                        selection={selectedEntries[activeContentType]?.entriesToCreate || []}
                                        selectAll={contentTypes[activeContentType].onlyInSource.length > 0 && 
                                                 selectedEntries[activeContentType]?.entriesToCreate.length === contentTypes[activeContentType].onlyInSource.length}
                                        onSelectAllChange={e => {
                                            const allEntries = contentTypes[activeContentType].onlyInSource;
                                            if (allEntries.length === 0) return;

                                            // Collect all document IDs
                                            const documentIds = allEntries.map(entry => entry.metadata?.documentId ).filter(Boolean) as string[];
                                            if (documentIds.length === 0) return;

                                            // Set loading state
                                            setCreateTableLoading(true);

                                            // Make the bulk API call
                                            axios.post(`/api/merge-requests/${mergeRequestId}/bulk-selection`, {
                                                contentType: activeContentType,
                                                direction: 'TO_CREATE',
                                                documentIds,
                                                isSelected: e.checked
                                            })
                                            .then(response => {
                                                if (response.data.success) {
                                                    // If successful, update the local state
                                                    setSelectedEntries(prevState => {
                                                        const newState = { ...prevState };

                                                        if (e.checked) {
                                                            // Select all items
                                                            newState[activeContentType] = {
                                                                ...newState[activeContentType],
                                                                entriesToCreate: allEntries
                                                            };
                                                        } else {
                                                            // Deselect all items
                                                            newState[activeContentType] = {
                                                                ...newState[activeContentType],
                                                                entriesToCreate: []
                                                            };
                                                        }

                                                        return newState;
                                                    });
                                                }
                                                // Clear loading state
                                                setCreateTableLoading(false);
                                            })
                                            .catch(err => {
                                                console.error(`Error in select all for ${activeContentType}:`, err);
                                                // Clear loading state on error
                                                setCreateTableLoading(false);
                                            });
                                        }}
                                        onSelectionChange={(e) => handleSelectionChange(activeContentType, 'create', e.value)}
                                        dataKey="metadata.documentId"
                                        paginator
                                        rows={5}
                                        rowsPerPageOptions={[5, 10, 25,50]}
                                        expandedRows={expandedCreateEntries}
                                        onRowToggle={(e) => {
                                            // Convert DataTableExpandedRows to Record<string, boolean>
                                            if (Array.isArray(e.data)) {
                                                // If it's an array, create a new record with all values set to true
                                                const expanded: Record<string, boolean> = {};
                                                e.data.forEach((item: any) => {
                                                    expanded[item] = true;
                                                });
                                                setExpandedCreateEntries(expanded);
                                            } else {
                                                // If it's already a record object, use it directly
                                                setExpandedCreateEntries(e.data as Record<string, boolean>);
                                            }
                                        }}
                                        rowExpansionTemplate={(rowData) => {
                                            // Get the document ID from metadata.documentId or rawData.id
                                            const documentId = rowData.metadata?.documentId || rowData.rawData?.id || rowData.rawData?.documentId;
                                            return renderRelationshipsSection(documentId);
                                        }}
                                    >
                                        <Column expander={(rowData) => {
                                            // Get the document ID from metadata.documentId or rawData.id
                                            const documentId = rowData.metadata?.documentId || rowData.rawData?.id || rowData.rawData?.documentId;
                                            const relationships = contentTypes[activeContentType].relationships?.[documentId];
                                            return Boolean(relationships && relationships.length > 0);
                                        }} style={{width: '3rem'}}/>
                                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>
                                        <Column field="id" header="ID" sortable style={{width: '8rem'}}/>
                                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                                style={{minWidth: '20rem'}}/>
                                        <Column header="Content" body={(rowData) => (
                                            <Button
                                                label="View Content"
                                                icon="pi pi-eye"
                                                className="p-button-text"
                                                onClick={() => openEditorDialog(rowData, false, null, null, "View Content")}
                                            />
                                        )} style={{width: '10rem'}}/>
                                    </DataTable>
                                )}
                            </TabPanel>

                            {/* To Update Tab */}
                            <TabPanel header={`To Update (${contentTypes[activeContentType].different.length})`}>
                                {contentTypes[activeContentType].different.length === 0 ? (
                                    <Message severity="info" text="No entries to update" className="w-full"/>
                                ) : (
                                    <DataTable
                                        value={contentTypes[activeContentType].different}
                                        selectionMode="multiple"
                                        loading={updateTableLoading}
                                        selection={selectedEntries[activeContentType]?.entriesToUpdate || []}
                                        selectAll={contentTypes[activeContentType].different.length > 0 && 
                                                 selectedEntries[activeContentType]?.entriesToUpdate.length === contentTypes[activeContentType].different.length}
                                        onSelectAllChange={e => {
                                            const allEntries = contentTypes[activeContentType].different;
                                            if (allEntries.length === 0) return;

                                            // Collect all document IDs
                                            const documentIds = allEntries.map(entry => entry.source?.metadata?.documentId).filter(Boolean) as string[];
                                            if (documentIds.length === 0) return;

                                            // Set loading state
                                            setUpdateTableLoading(true);

                                            // Make the bulk API call
                                            axios.post(`/api/merge-requests/${mergeRequestId}/bulk-selection`, {
                                                contentType: activeContentType,
                                                direction: 'TO_UPDATE',
                                                documentIds,
                                                isSelected: e.checked
                                            })
                                            .then(response => {
                                                if (response.data.success) {
                                                    // If successful, update the local state
                                                    setSelectedEntries(prevState => {
                                                        const newState = { ...prevState };

                                                        if (e.checked) {
                                                            // Select all items
                                                            newState[activeContentType] = {
                                                                ...newState[activeContentType],
                                                                entriesToUpdate: allEntries
                                                            };
                                                        } else {
                                                            // Deselect all items
                                                            newState[activeContentType] = {
                                                                ...newState[activeContentType],
                                                                entriesToUpdate: []
                                                            };
                                                        }

                                                        return newState;
                                                    });
                                                }
                                                // Clear loading state
                                                setUpdateTableLoading(false);
                                            })
                                            .catch(err => {
                                                console.error(`Error in select all for ${activeContentType}:`, err);
                                                // Clear loading state on error
                                                setUpdateTableLoading(false);
                                            });
                                        }}
                                        onSelectionChange={(e) => handleSelectionChange(activeContentType, 'update', e.value)}
                                        dataKey="source.metadata.documentId"
                                        paginator
                                        rows={5}
                                        rowsPerPageOptions={[5, 10, 25,50]}
                                        expandedRows={expandedUpdateEntries}
                                        onRowToggle={(e) => {
                                            // Convert DataTableExpandedRows to Record<string, boolean>
                                            if (Array.isArray(e.data)) {
                                                // If it's an array, create a new record with all values set to true
                                                const expanded: Record<string, boolean> = {};
                                                e.data.forEach((item: any) => {
                                                    expanded[item] = true;
                                                });
                                                setExpandedUpdateEntries(expanded);
                                            } else {
                                                // If it's already a record object, use it directly
                                                setExpandedUpdateEntries(e.data as Record<string, boolean>);
                                            }
                                        }}
                                        rowExpansionTemplate={(rowData) => {
                                            // Get the document ID from source.metadata.documentId or source.id
                                            const documentId = rowData.source?.metadata?.documentId || rowData.source?.id;
                                            return renderRelationshipsSection(documentId);
                                        }}
                                    >
                                        <Column expander={(rowData) => {
                                            // Get the document ID from source.metadata.documentId or source.id
                                            const documentId = rowData.source?.metadata?.documentId || rowData.source?.id;
                                            const relationships = contentTypes[activeContentType].relationships?.[documentId];
                                            return Boolean(relationships && relationships.length > 0);
                                        }} style={{width: '3rem'}}/>
                                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>
                                        <Column field="source.id" header="ID" sortable style={{width: '8rem'}}/>
                                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                                style={{minWidth: '20rem'}}/>
                                        <Column header="Differences" body={(rowData) => (
                                            <Button
                                                label="View Differences"
                                                icon="pi pi-eye"
                                                className="p-button-text"
                                                onClick={() => openEditorDialog(null, true, rowData.source, rowData.target, "View Differences")}
                                            />
                                        )} style={{width: '12rem'}}/>
                                    </DataTable>
                                )}
                            </TabPanel>

                            {/* To Delete Tab */}
                            <TabPanel header={`To Delete (${contentTypes[activeContentType].onlyInTarget.length})`}>
                                {contentTypes[activeContentType].onlyInTarget.length === 0 ? (
                                    <Message severity="info" text="No entries to delete" className="w-full"/>
                                ) : (
                                    <DataTable
                                        value={contentTypes[activeContentType].onlyInTarget}
                                        selectionMode="multiple"
                                        selection={selectedEntries[activeContentType]?.entriesToDelete || []}
                                        selectAll={contentTypes[activeContentType].onlyInTarget.length > 0 && 
                                                 selectedEntries[activeContentType]?.entriesToDelete.length === contentTypes[activeContentType].onlyInTarget.length}
                                        onSelectAllChange={e => {
                                            const allEntries = contentTypes[activeContentType].onlyInTarget;
                                            if (allEntries.length === 0) return;

                                            // Collect all document IDs
                                            const documentIds = allEntries.map(entry => entry.metadata?.documentId ).filter(Boolean) as string[];
                                            if (documentIds.length === 0) return;

                                            // Make the bulk API call
                                            axios.post(`/api/merge-requests/${mergeRequestId}/bulk-selection`, {
                                                contentType: activeContentType,
                                                direction: 'TO_DELETE',
                                                documentIds,
                                                isSelected: e.checked
                                            })
                                            .then(response => {
                                                if (response.data.success) {
                                                    // If successful, update the local state
                                                    setSelectedEntries(prevState => {
                                                        const newState = { ...prevState };

                                                        if (e.checked) {
                                                            // Select all items
                                                            newState[activeContentType] = {
                                                                ...newState[activeContentType],
                                                                entriesToDelete: allEntries
                                                            };
                                                        } else {
                                                            // Deselect all items
                                                            newState[activeContentType] = {
                                                                ...newState[activeContentType],
                                                                entriesToDelete: []
                                                            };
                                                        }

                                                        return newState;
                                                    });
                                                }
                                            })
                                            .catch(err => {
                                                console.error(`Error in select all for ${activeContentType}:`, err);
                                            });
                                        }}
                                        onSelectionChange={(e) => handleSelectionChange(activeContentType, 'delete', e.value)}
                                        dataKey="metadata.documentId"
                                        paginator
                                        rows={5}
                                        rowsPerPageOptions={[5, 10, 25,50]}
                                        expandedRows={expandedDeleteEntries}
                                        onRowToggle={(e) => {
                                            // Convert DataTableExpandedRows to Record<string, boolean>
                                            if (Array.isArray(e.data)) {
                                                // If it's an array, create a new record with all values set to true
                                                const expanded: Record<string, boolean> = {};
                                                e.data.forEach((item: any) => {
                                                    expanded[item] = true;
                                                });
                                                setExpandedDeleteEntries(expanded);
                                            } else {
                                                // If it's already a record object, use it directly
                                                setExpandedDeleteEntries(e.data as Record<string, boolean>);
                                            }
                                        }}
                                        rowExpansionTemplate={(rowData) => {
                                            return <div>No related records</div>;
                                        }}
                                    >
                                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>
                                        <Column field="id" header="ID" sortable/>
                                        <Column header="Content" body={(rowData) => (
                                            <Button
                                                label="View Content"
                                                icon="pi pi-eye"
                                                className="p-button-text"
                                                onClick={() => openEditorDialog(rowData, false, null, null, "View Content")}
                                            />
                                        )}/>
                                    </DataTable>
                                )}
                            </TabPanel>

                            {/* Identical Tab */}
                            <TabPanel header={`Identical (${contentTypes[activeContentType].identical.length})`}>
                                {contentTypes[activeContentType].identical.length === 0 ? (
                                    <Message severity="info" text="No identical entries" className="w-full"/>
                                ) : (
                                    <DataTable
                                        value={contentTypes[activeContentType].identical}
                                        dataKey="metadata.documentId"
                                        paginator
                                        rows={5}
                                        rowsPerPageOptions={[5, 10, 25,50]}
                                        expandedRows={expandedIdenticalEntries}
                                        onRowToggle={(e) => {
                                            // Convert DataTableExpandedRows to Record<string, boolean>
                                            if (Array.isArray(e.data)) {
                                                // If it's an array, create a new record with all values set to true
                                                const expanded: Record<string, boolean> = {};
                                                e.data.forEach((item: any) => {
                                                    expanded[item] = true;
                                                });
                                                setExpandedIdenticalEntries(expanded);
                                            } else {
                                                // If it's already a record object, use it directly
                                                setExpandedIdenticalEntries(e.data as Record<string, boolean>);
                                            }
                                        }}
                                        rowExpansionTemplate={(rowData) => {
                                            // Get the document ID from metadata.documentId or rawData.id
                                            const documentId = rowData.metadata?.documentId || rowData.rawData?.id || rowData.rawData?.documentId;
                                            return renderRelationshipsSection(documentId);
                                        }}
                                    >
                                        <Column expander={(rowData) => {
                                            // Get the document ID from metadata.documentId or rawData.id
                                            const documentId = rowData.metadata?.documentId || rowData.rawData?.id || rowData.rawData?.documentId;
                                            const relationships = contentTypes[activeContentType].relationships?.[documentId];
                                            return Boolean(relationships && relationships.length > 0);
                                        }} style={{width: '3rem'}}/>
                                        <Column field="id" header="ID" sortable style={{width: '8rem'}}/>
                                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                                style={{minWidth: '20rem'}}/>
                                        <Column header="Content" body={(rowData) => (
                                            <Button
                                                label="View Content"
                                                icon="pi pi-eye"
                                                className="p-button-text"
                                                onClick={() => openEditorDialog(rowData, false, null, null, "View Content")}
                                            />
                                        )} style={{width: '10rem'}}/>
                                    </DataTable>
                                )}
                            </TabPanel>
                        </TabView>
                    </Card>
                </div>
            )}

            <div className="flex flex-column align-items-center my-5">
                <Message
                    severity="info"
                    text="Your selections are automatically saved. You can proceed to the next step when ready."
                    className="w-full"
                />
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

export default MergeCollectionsStep;
