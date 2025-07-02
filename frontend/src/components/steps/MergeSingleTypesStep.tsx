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
import {DiffEditor, Editor} from "@monaco-editor/react";
import {Tooltip} from 'primereact/tooltip';
import {Chip} from 'primereact/chip';

// Import types
import {
    ContentTypeComparisonResultWithRelationships,
    MergeRequestData,
    MergeRequestSelectionDTO,
    SelectedContentTypeDependency,
    EntryRelationship
} from '../../types';
import { findTargetEntry } from '../../utils/entryUtils';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import RelationshipCard from '../common/RelationshipCard';
import EditorDialog from '../common/EditorDialog';


interface MergeSingleTypesStepProps {
    status: string;
    mergingSingles: boolean;
    mergeSingleTypes: () => void;
    mergeRequestId: number;
    singleTypesData?: Record<string, ContentTypeComparisonResultWithRelationships>;
    selections?: MergeRequestSelectionDTO[];
    loading?: boolean;
    allMergeData?: MergeRequestData;
    updateSingleSelection?: (contentType: string, documentId: string, direction: string, isSelected: boolean) => Promise<boolean>;
}

// Use the imported types instead of redefining them
// ContentTypeComparisonResultWithRelationships, EntryRelationship, SelectedContentTypeDependency are imported from '../../types'

interface SelectedContentTypeEntries {
    contentType: string;
    entriesToCreate: string[];
    entriesToUpdate: string[];
    entriesToDelete: string[];
    requiredDependencies?: SelectedContentTypeDependency[];
}

const MergeSingleTypesStep: React.FC<MergeSingleTypesStepProps> = ({
                                                                       status,
                                                                       mergingSingles,
                                                                       mergeSingleTypes,
                                                                       mergeRequestId,
                                                                       singleTypesData,
                                                                       selections,
                                                                       loading: parentLoading,
                                                                       allMergeData,
                                                                       updateSingleSelection
                                                                   }) => {
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [contentTypes, setContentTypes] = useState<Record<string, ContentTypeComparisonResultWithRelationships>>({});
    const [singleTypes, setSingleTypes] = useState<ContentTypeComparisonResultWithRelationships[]>([]);
    const [selectedEntries, setSelectedEntries] = useState<Record<string, SelectedContentTypeEntries>>({});
    const [activeTabIndex, setActiveTabIndex] = useState<number>(0);
    // Removed modal-related state variables

    // State for expanded rows
    const [expandedToCreateTypes, setExpandedToCreateTypes] = useState<Record<string, boolean>>({});
    const [expandedToUpdateTypes, setExpandedToUpdateTypes] = useState<Record<string, boolean>>({});
    const [expandedToDeleteTypes, setExpandedToDeleteTypes] = useState<Record<string, boolean>>({});
    const [expandedIdenticalTypes, setExpandedIdenticalTypes] = useState<Record<string, boolean>>({});

    // Loading states for each table
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

    const isDisabled = mergingSingles ||
        (status !== 'MERGED_FILES' &&
            status !== 'MERGED_SINGLES' &&
            status !== 'MERGED_COLLECTIONS' &&
            status !== 'COMPLETED');

    // Use singleTypesData from props if available, otherwise show loading state
    useEffect(() => {
        // If singleTypesData is provided from parent, use it
        if (singleTypesData) {
            setContentTypes(singleTypesData);

            // Convert the map to an array
            const singleTypesArray = Object.values(singleTypesData) as ContentTypeComparisonResultWithRelationships[];
            setSingleTypes(singleTypesArray);

            // Initialize selected entries for each content type with empty arrays
            const initialSelectedEntries: Record<string, SelectedContentTypeEntries> = {};
            singleTypesArray.forEach((ct: ContentTypeComparisonResultWithRelationships) => {
                initialSelectedEntries[ct.contentType] = {
                    contentType: ct.contentType,
                    entriesToCreate: [],
                    entriesToUpdate: [],
                    entriesToDelete: []
                };
            });

            // If selections are provided, use them
            if (selections && selections.length > 0) {
                selections.forEach((selection: any) => {
                    if (initialSelectedEntries[selection.contentType]) {
                        initialSelectedEntries[selection.contentType] = {
                            ...initialSelectedEntries[selection.contentType],
                            entriesToCreate: selection.entriesToCreate || [],
                            entriesToUpdate: selection.entriesToUpdate || [],
                            entriesToDelete: selection.entriesToDelete || []
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
    }, [mergeRequestId, singleTypesData, selections]);

    // Check if a content type has dependencies
    const hasDependencies = (contentType: ContentTypeComparisonResultWithRelationships): boolean => {
        return (contentType.relationships && contentType.relationships.length > 0);
    };

    // Handle selection change for a specific content type
    const handleSelectionChange = async (contentType: string, type: 'create' | 'update' | 'delete', selection: any[]) => {
        // Get the direction based on the type
        const direction = type === 'create' ? 'TO_CREATE' : type === 'update' ? 'TO_UPDATE' : 'TO_DELETE';

        // Determine if we're selecting or deselecting
        const isSelecting = selection.length > 0;

        // Get the document ID
        let documentId = '';
        if (isSelecting) {
            // For selection, we get the document ID from the selection array
            documentId = selection[0];
        } else {
            // For deselection, we get the document ID from the current selected entries
            const previousEntries = selectedEntries[contentType][
                type === 'create' ? 'entriesToCreate' :
                    type === 'update' ? 'entriesToUpdate' :
                        'entriesToDelete'
                ];
            documentId = previousEntries[0];
        }

        // Set loading state based on the type
        if (type === 'create') {
            setCreateTableLoading(true);
        } else if (type === 'update') {
            setUpdateTableLoading(true);
        } else if (type === 'delete') {
            setDeleteTableLoading(true);
        }

        try {
            let success = false;

            // Use the parent's updateSingleSelection function if available
            if (updateSingleSelection) {
                success = await updateSingleSelection(contentType, documentId, direction, isSelecting);
            } else {
                // Fallback to the original implementation if updateSingleSelection is not provided
                const response = await axios.post(`/api/merge-requests/${mergeRequestId}/selection`, {
                    contentType,
                    documentId,
                    direction,
                    isSelected: isSelecting
                });
                success = response.data.success;
            }

            // Only update the local state if the API call was successful
            if (success) {
                // Update the local state for the main selection
                setSelectedEntries(prevState => {
                    const newState = {
                        ...prevState,
                        [contentType]: {
                            ...prevState[contentType],
                            [type === 'create' ? 'entriesToCreate' :
                                type === 'update' ? 'entriesToUpdate' :
                                    'entriesToDelete']: selection
                        }
                    };
                    return newState;
                });
            }
        } catch (err: any) {
            console.error(`Error updating selection for ${contentType}:`, err);
        } finally {
            // Clear loading state based on the type
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
        // For single types, the entry might be in different places depending on the compareKind
        let entry;
        if (rowData.compareKind === 'ONLY_IN_SOURCE') {
            entry = rowData.onlyInSource;
        } else if (rowData.compareKind === 'DIFFERENT') {
            entry = rowData.different?.source;
        } else if (rowData.compareKind === 'ONLY_IN_TARGET') {
            entry = rowData.onlyInTarget;
        } else if (rowData.compareKind === 'IDENTICAL') {
            entry = rowData.identical;
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

    // This function is no longer needed as dependencies are automatically selected
    // Kept as a comment for reference
    /*
    const selectAllDependencies = () => {
      // Function removed as dependencies are now automatically selected
    };
    */

    // Check if a dependency is being resolved
    const isDependencyResolved = (contentType: string, entryId?: string): boolean => {
        // For single types, we just need to check if the content type is selected
        const contentTypeSelected = Object.values(selectedEntries).some(entry =>
            entry.contentType === contentType &&
            (entry.entriesToCreate.length > 0 || entry.entriesToUpdate.length > 0)
        );

        return contentTypeSelected;
    };

    // Function to render dependency indicators
    const renderDependencyIndicator = (rowData: ContentTypeComparisonResultWithRelationships) => {
        if (!hasDependencies(rowData)) return null;

        const dependenciesCount = rowData.dependsOn?.length || 0;
        const resolvedCount = rowData.dependsOn?.filter(dep => isDependencyResolved(dep))?.length || 0;
        const allResolved = resolvedCount === dependenciesCount && dependenciesCount > 0;

        return (
            <div className="flex align-items-center">
                <Tooltip target=".dependency-indicator"/>
                <Chip
                    label={`${resolvedCount}/${dependenciesCount} dependencies resolved`}
                    icon={allResolved ? "pi pi-check" : "pi pi-link"}
                    className={`dependency-indicator mr-2 ${allResolved ? "p-chip-success" : "p-chip-warning"}`}
                    data-pr-tooltip="This content type has dependencies that must be selected"
                    data-pr-position="top"
                />
            </div>
        );
    };

    // Function to render relationship details
    const renderDependencyDetails = (rowData: ContentTypeComparisonResultWithRelationships) => {
        if (!hasDependencies(rowData)) return <div>No related records</div>;

        return (
            <div className="p-3">
                <h5>Related Records</h5>
                <div className="grid">
                    {rowData.relationships?.map((relationship, index) => {
                        // Use the shared findTargetEntry function
                        const { targetEntry, contentTypeKind } = findTargetEntry(relationship, allMergeData);

                        if (!targetEntry) return null;

                        // Check if the relationship is resolved by checking if the target content type is selected
                        // For single types, we just need to check if any entry of the target content type is selected
                        const isResolved = isDependencyResolved(relationship.targetContentType);

                        return (
                            <RelationshipCard
                                relationship={relationship}
                                index={index}
                                targetEntry={targetEntry}
                                isResolved={isResolved}
                                getRepresentativeAttributes={getRepresentativeAttributes}
                            />
                        );
                    })}
                </div>
            </div>
        );
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
                <h3>Merge Single Types</h3>
                <Message severity="error" text={error} className="w-full mb-3"/>
            </div>
        );
    }

    // No content types
    if (singleTypes.length === 0) {
        return (
            <div>
                <h3>Merge Single Types</h3>
                <Message severity="info" text="No single content types found for comparison." className="w-full mb-3"/>

                <div className="flex flex-column align-items-center my-5">
                    {status === 'MERGED_SINGLES' && (
                        <div className="mb-3">
                            <Message
                                severity="success"
                                text="Single content types merged successfully. You can proceed to the next step."
                                className="w-full"
                            />
                        </div>
                    )}

                    <Button
                        label="Merge Single Types"
                        icon="pi pi-file"
                        loading={mergingSingles}
                        disabled={isDisabled}
                        onClick={mergeSingleTypes}
                    />
                </div>
            </div>
        );
    }

    // Filter single types by compareKind
    const toCreateTypes = singleTypes.filter(ct => ct.compareKind === 'ONLY_IN_SOURCE');
    const toUpdateTypes = singleTypes.filter(ct => ct.compareKind === 'DIFFERENT');
    const toDeleteTypes = singleTypes.filter(ct => ct.compareKind === 'ONLY_IN_TARGET');
    const identicalTypes = singleTypes.filter(ct => ct.compareKind === 'IDENTICAL');

    // Custom tab headers with counters
    const createTabHeader = (options: any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{cursor: 'pointer'}} onClick={options.onClick}>
                {Object.values(selectedEntries).reduce((count, entry) => count + (entry.entriesToCreate.length > 0 ? 1 : 0), 0) > 0 && (
                    <Badge
                        value={Object.values(selectedEntries).reduce((count, entry) => count + (entry.entriesToCreate.length > 0 ? 1 : 0), 0)}
                        severity="info" className="ml-2"/>
                )}
                <span className="font-bold white-space-nowrap">To Create ({toCreateTypes.length})</span>
            </div>
        );
    };

    const updateTabHeader = (options: any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{cursor: 'pointer'}} onClick={options.onClick}>
                {Object.values(selectedEntries).reduce((count, entry) => count + (entry.entriesToUpdate.length > 0 ? 1 : 0), 0) > 0 && (
                    <Badge
                        value={Object.values(selectedEntries).reduce((count, entry) => count + (entry.entriesToUpdate.length > 0 ? 1 : 0), 0)}
                        severity="warning" className="ml-2"/>
                )}
                <span className="font-bold white-space-nowrap">To Update ({toUpdateTypes.length})</span>
            </div>
        );
    };

    const deleteTabHeader = (options: any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{cursor: 'pointer'}} onClick={options.onClick}>
                {Object.values(selectedEntries).reduce((count, entry) => count + (entry.entriesToDelete.length > 0 ? 1 : 0), 0) > 0 && (
                    <Badge
                        value={Object.values(selectedEntries).reduce((count, entry) => count + (entry.entriesToDelete.length > 0 ? 1 : 0), 0)}
                        severity="danger" className="ml-2"/>
                )}
                <span className="font-bold white-space-nowrap">To Delete ({toDeleteTypes.length})</span>
            </div>
        );
    };

    const identicalTabHeader = (options: any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{cursor: 'pointer'}} onClick={options.onClick}>
                <span className="font-bold white-space-nowrap">Identical ({identicalTypes.length})</span>
            </div>
        );
    };

    const messageContent = <div className="flex align-items-center">
        <i className="pi pi-info-circle mr-2" style={{fontSize: '1.5rem'}}></i>
        <div>
            <h5 className="mt-0 mb-1">Selection Management</h5>
            <p className="m-0">
                Select or deselect content types to include in the merge.
                Each selection will be immediately saved to the server.
            </p>
        </div>
    </div>

    return (
        <div>
            <h3>Merge Single Types</h3>
            <p>
                This step allows you to select which single content types to create, update, or delete on the target
                instance.
                Review the differences and make your selections before proceeding.
            </p>
            <Message severity="info" className="mb-3" content={messageContent}/>

            <TabView activeIndex={activeTabIndex} onTabChange={(e) => setActiveTabIndex(e.index)}>
                {/* To Create Tab */}
                <TabPanel headerTemplate={createTabHeader}>
                    <DataTable
                        value={toCreateTypes}
                        selectionMode="multiple"
                        loading={createTableLoading}
                        selection={toCreateTypes.filter(ct => selectedEntries[ct.contentType]?.entriesToCreate.length > 0)}
                        selectAll={toCreateTypes.length > 0 && 
                                  toCreateTypes.length === toCreateTypes.filter(ct => 
                                    selectedEntries[ct.contentType]?.entriesToCreate.length > 0).length}
                        onSelectAllChange={e => {
                            // For each content type, collect the document IDs
                            const contentTypeDocumentIds: Record<string, string[]> = {};

                            toCreateTypes.forEach(ct => {
                                if (ct.onlyInSource) {
                                    const contentType = ct.contentType;
                                    const documentId = ct.onlyInSource.metadata.documentId;

                                    if (!contentTypeDocumentIds[contentType]) {
                                        contentTypeDocumentIds[contentType] = [];
                                    }

                                    contentTypeDocumentIds[contentType].push(documentId);
                                }
                            });

                            // Set loading state
                            setCreateTableLoading(true);

                            // For each content type, make a bulk API call
                            const promises = Object.entries(contentTypeDocumentIds).map(([contentType, documentIds]) => {
                                if (documentIds.length === 0) return Promise.resolve();

                                // Make the API call
                                return axios.post(`/api/merge-requests/${mergeRequestId}/bulk-selection`, {
                                    contentType,
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
                                                newState[contentType] = {
                                                    ...newState[contentType],
                                                    entriesToCreate: documentIds
                                                };
                                            } else {
                                                // Deselect all items
                                                newState[contentType] = {
                                                    ...newState[contentType],
                                                    entriesToCreate: []
                                                };
                                            }

                                            return newState;
                                        });
                                    }
                                })
                                .catch(err => {
                                    console.error(`Error in select all for ${contentType}:`, err);
                                });
                            });

                            // Clear loading state when all promises are resolved
                            Promise.all(promises)
                                .finally(() => {
                                    setCreateTableLoading(false);
                                });
                        }}
                        onSelectionChange={(e) => {
                            const selectedContentTypes = e.value as ContentTypeComparisonResultWithRelationships[];
                            const previouslySelected = toCreateTypes.filter(ct =>
                                selectedEntries[ct.contentType]?.entriesToCreate.length > 0
                            );

                            // Find newly selected items (items in selectedContentTypes but not in previouslySelected)
                            const newlySelected = selectedContentTypes.filter(
                                ct => !previouslySelected.some(pct => pct.contentType === ct.contentType)
                            );

                            // Find newly deselected items (items in previouslySelected but not in selectedContentTypes)
                            const newlyDeselected = previouslySelected.filter(
                                ct => !selectedContentTypes.some(sct => sct.contentType === ct.contentType)
                            );

                            // Handle newly selected items
                            newlySelected.forEach(ct => {
                                if (ct.onlyInSource) {
                                    handleSelectionChange(
                                        ct.contentType,
                                        'create',
                                        [ct.onlyInSource.metadata.documentId]
                                    );
                                }
                            });

                            // Handle newly deselected items
                            newlyDeselected.forEach(ct => {
                                handleSelectionChange(
                                    ct.contentType,
                                    'create',
                                    []
                                );
                            });
                        }}
                        dataKey="contentType"
                        paginator
                        rows={5}
                        rowsPerPageOptions={[5, 10, 25]}
                        emptyMessage="No content types to create"
                        expandedRows={expandedToCreateTypes}
                        onRowToggle={(e) => {
                            // Convert DataTableExpandedRows to Record<string, boolean>
                            if (Array.isArray(e.data)) {
                                // If it's an array, create a new record with all values set to true
                                const expanded: Record<string, boolean> = {};
                                e.data.forEach((item: any) => {
                                    expanded[item] = true;
                                });
                                setExpandedToCreateTypes(expanded);
                            } else {
                                // If it's already a record object, use it directly
                                setExpandedToCreateTypes(e.data as Record<string, boolean>);
                            }
                        }}
                        rowExpansionTemplate={renderDependencyDetails}
                    >
                        <Column expander={hasDependencies} style={{width: '3rem'}}/>
                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>
                        <Column field="contentType" header="Content Type" sortable/>
                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                style={{minWidth: '20rem'}}/>
                        <Column header="Content" body={(rowData) => (
                            <Button
                                label="View Content"
                                icon="pi pi-eye"
                                className="p-button-text"
                                onClick={() => openEditorDialog(rowData.onlyInSource?.rawData, false, null, null, "View Content")}
                            />
                        )} style={{width: '10rem'}}/>
                    </DataTable>
                </TabPanel>

                {/* To Update Tab */}
                <TabPanel headerTemplate={updateTabHeader}>
                    <DataTable
                        value={toUpdateTypes}
                        selectionMode="multiple"
                        loading={updateTableLoading}
                        selection={toUpdateTypes.filter(ct => selectedEntries[ct.contentType]?.entriesToUpdate.indexOf(ct.different!!.source.metadata.documentId) > -1)}
                        selectAll={toUpdateTypes.length > 0 && 
                                  toUpdateTypes.length === toUpdateTypes.filter(ct => 
                                    selectedEntries[ct.contentType]?.entriesToUpdate.indexOf(ct.different!!.source.metadata.documentId) > -1).length}
                        onSelectAllChange={e => {
                            // For each content type, collect the document IDs
                            const contentTypeDocumentIds: Record<string, string[]> = {};

                            toUpdateTypes.forEach(ct => {
                                if (ct.different) {
                                    const contentType = ct.contentType;
                                    const documentId = ct.different.source.metadata.documentId;

                                    if (!contentTypeDocumentIds[contentType]) {
                                        contentTypeDocumentIds[contentType] = [];
                                    }

                                    contentTypeDocumentIds[contentType].push(documentId);
                                }
                            });

                            // Set loading state
                            setUpdateTableLoading(true);

                            // For each content type, make a bulk API call
                            const promises = Object.entries(contentTypeDocumentIds).map(([contentType, documentIds]) => {
                                if (documentIds.length === 0) return Promise.resolve();

                                // Make the API call
                                return axios.post(`/api/merge-requests/${mergeRequestId}/bulk-selection`, {
                                    contentType,
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
                                                newState[contentType] = {
                                                    ...newState[contentType],
                                                    entriesToUpdate: documentIds
                                                };
                                            } else {
                                                // Deselect all items
                                                newState[contentType] = {
                                                    ...newState[contentType],
                                                    entriesToUpdate: []
                                                };
                                            }

                                            return newState;
                                        });
                                    }
                                })
                                .catch(err => {
                                    console.error(`Error in select all for ${contentType}:`, err);
                                });
                            });

                            // Clear loading state when all promises are resolved
                            Promise.all(promises)
                                .finally(() => {
                                    setUpdateTableLoading(false);
                                });
                        }}
                        onSelectionChange={(e) => {
                            const selectedContentTypes = e.value as ContentTypeComparisonResultWithRelationships[];
                            const previouslySelected = toUpdateTypes.filter(ct =>
                                selectedEntries[ct.contentType]?.entriesToUpdate.length > 0
                            );

                            // Find newly selected items (items in selectedContentTypes but not in previouslySelected)
                            const newlySelected = selectedContentTypes.filter(
                                ct => !previouslySelected.some(pct => pct.contentType === ct.contentType)
                            );

                            // Find newly deselected items (items in previouslySelected but not in selectedContentTypes)
                            const newlyDeselected = previouslySelected.filter(
                                ct => !selectedContentTypes.some(sct => sct.contentType === ct.contentType)
                            );

                            // Handle newly selected items
                            newlySelected.forEach(ct => {
                                if (ct.different) {
                                    handleSelectionChange(
                                        ct.contentType,
                                        'update',
                                        [ct.different.source.metadata.documentId]
                                    );
                                }
                            });

                            // Handle newly deselected items
                            newlyDeselected.forEach(ct => {
                                handleSelectionChange(
                                    ct.contentType,
                                    'update',
                                    []
                                );
                            });
                        }}
                        dataKey="different.source.metadata.documentId"
                        paginator
                        rows={5}
                        rowsPerPageOptions={[5, 10, 25,50]}
                        emptyMessage="No content types to update"
                        expandedRows={expandedToUpdateTypes}
                        onRowToggle={(e) => {
                            // Convert DataTableExpandedRows to Record<string, boolean>
                            if (Array.isArray(e.data)) {
                                // If it's an array, create a new record with all values set to true
                                const expanded: Record<string, boolean> = {};
                                e.data.forEach((item: any) => {
                                    expanded[item] = true;
                                });
                                setExpandedToUpdateTypes(expanded);
                            } else {
                                // If it's already a record object, use it directly
                                setExpandedToUpdateTypes(e.data as Record<string, boolean>);
                            }
                        }}
                        rowExpansionTemplate={renderDependencyDetails}
                    >
                        <Column expander={hasDependencies} style={{width: '3rem'}}/>
                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>
                        <Column field="contentType" header="Content Type" sortable/>
                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                style={{minWidth: '20rem'}}/>
                        <Column header="Differences" body={(rowData) => (
                            <Button
                                label="View Differences"
                                icon="pi pi-eye"
                                className="p-button-text"
                                onClick={() => openEditorDialog(null, true, rowData.different?.source.cleanData, rowData.different?.target.cleanData, "View Differences")}
                            />
                        )} style={{width: '12rem'}}/>
                    </DataTable>
                </TabPanel>

                {/* To Delete Tab */}
                <TabPanel headerTemplate={deleteTabHeader}>
                    <DataTable
                        value={toDeleteTypes}
                        selectionMode="multiple"
                        loading={deleteTableLoading}
                        selection={toDeleteTypes.filter(ct => selectedEntries[ct.contentType]?.entriesToDelete.length > 0)}
                        selectAll={toDeleteTypes.length > 0 && 
                                  toDeleteTypes.length === toDeleteTypes.filter(ct => 
                                    selectedEntries[ct.contentType]?.entriesToDelete.length > 0).length}
                        onSelectAllChange={e => {
                            // For each content type, collect the document IDs
                            const contentTypeDocumentIds: Record<string, string[]> = {};

                            toDeleteTypes.forEach(ct => {
                                if (ct.onlyInTarget) {
                                    const contentType = ct.contentType;
                                    const documentId = ct.onlyInTarget.metadata.documentId;

                                    if (!contentTypeDocumentIds[contentType]) {
                                        contentTypeDocumentIds[contentType] = [];
                                    }

                                    contentTypeDocumentIds[contentType].push(documentId);
                                }
                            });

                            // Set loading state
                            setDeleteTableLoading(true);

                            // For each content type, make a bulk API call
                            const promises = Object.entries(contentTypeDocumentIds).map(([contentType, documentIds]) => {
                                if (documentIds.length === 0) return Promise.resolve();

                                // Make the API call
                                return axios.post(`/api/merge-requests/${mergeRequestId}/bulk-selection`, {
                                    contentType,
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
                                                newState[contentType] = {
                                                    ...newState[contentType],
                                                    entriesToDelete: documentIds
                                                };
                                            } else {
                                                // Deselect all items
                                                newState[contentType] = {
                                                    ...newState[contentType],
                                                    entriesToDelete: []
                                                };
                                            }

                                            return newState;
                                        });
                                    }
                                })
                                .catch(err => {
                                    console.error(`Error in select all for ${contentType}:`, err);
                                });
                            });

                            // Clear loading state when all promises are resolved
                            Promise.all(promises)
                                .finally(() => {
                                    setDeleteTableLoading(false);
                                });
                        }}
                        onSelectionChange={(e) => {
                            const selectedContentTypes = e.value as ContentTypeComparisonResultWithRelationships[];
                            const previouslySelected = toDeleteTypes.filter(ct =>
                                selectedEntries[ct.contentType]?.entriesToDelete.length > 0
                            );

                            // Find newly selected items (items in selectedContentTypes but not in previouslySelected)
                            const newlySelected = selectedContentTypes.filter(
                                ct => !previouslySelected.some(pct => pct.contentType === ct.contentType)
                            );

                            // Find newly deselected items (items in previouslySelected but not in selectedContentTypes)
                            const newlyDeselected = previouslySelected.filter(
                                ct => !selectedContentTypes.some(sct => sct.contentType === ct.contentType)
                            );

                            // Handle newly selected items
                            newlySelected.forEach(ct => {
                                if (ct.onlyInTarget) {
                                    handleSelectionChange(
                                        ct.contentType,
                                        'delete',
                                        [ct.onlyInTarget.metadata.documentId]
                                    );
                                }
                            });

                            // Handle newly deselected items
                            newlyDeselected.forEach(ct => {
                                handleSelectionChange(
                                    ct.contentType,
                                    'delete',
                                    []
                                );
                            });
                        }}
                        dataKey="contentType"
                        paginator
                        rows={5}
                        rowsPerPageOptions={[5, 10, 25,50]}
                        emptyMessage="No content types to delete"
                        expandedRows={expandedToDeleteTypes}
                        onRowToggle={(e) => {
                            // Convert DataTableExpandedRows to Record<string, boolean>
                            if (Array.isArray(e.data)) {
                                // If it's an array, create a new record with all values set to true
                                const expanded: Record<string, boolean> = {};
                                e.data.forEach((item: any) => {
                                    expanded[item] = true;
                                });
                                setExpandedToDeleteTypes(expanded);
                            } else {
                                // If it's already a record object, use it directly
                                setExpandedToDeleteTypes(e.data as Record<string, boolean>);
                            }
                        }}
                        rowExpansionTemplate={renderDependencyDetails}
                    >
                        <Column expander={hasDependencies} style={{width: '3rem'}}/>
                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>
                        <Column field="contentType" header="Content Type" sortable/>
                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                style={{minWidth: '20rem'}}/>
                        <Column header="Content" body={(rowData) => (
                            <Button
                                label="View Content"
                                icon="pi pi-eye"
                                className="p-button-text"
                                onClick={() => openEditorDialog(rowData.onlyInTarget?.rawData, false, null, null, "View Content")}
                            />
                        )} style={{width: '10rem'}}/>
                    </DataTable>
                </TabPanel>

                {/* Identical Tab */}
                <TabPanel headerTemplate={identicalTabHeader}>
                    <DataTable
                        value={identicalTypes}
                        dataKey="contentType"
                        paginator
                        rows={5}
                        rowsPerPageOptions={[5, 10, 25,50]}
                        emptyMessage="No identical content types"
                        expandedRows={expandedIdenticalTypes}
                        onRowToggle={(e) => {
                            // Convert DataTableExpandedRows to Record<string, boolean>
                            if (Array.isArray(e.data)) {
                                // If it's an array, create a new record with all values set to true
                                const expanded: Record<string, boolean> = {};
                                e.data.forEach((item: any) => {
                                    expanded[item] = true;
                                });
                                setExpandedIdenticalTypes(expanded);
                            } else {
                                // If it's already a record object, use it directly
                                setExpandedIdenticalTypes(e.data as Record<string, boolean>);
                            }
                        }}
                        rowExpansionTemplate={renderDependencyDetails}
                    >
                        <Column expander={hasDependencies} style={{width: '3rem'}}/>
                        <Column field="contentType" header="Content Type" sortable/>
                        <Column header="Representative Attributes" body={renderRepresentativeAttributes}
                                style={{minWidth: '20rem'}}/>
                        <Column header="Content" body={(rowData) => (
                            <Button
                                label="View Content"
                                icon="pi pi-eye"
                                className="p-button-text"
                                onClick={() => openEditorDialog(rowData.identical?.rawData, false, null, null, "View Content")}
                            />
                        )} style={{width: '10rem'}}/>
                    </DataTable>
                </TabPanel>
            </TabView>

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

            {/* Modals removed to simplify workflow */}
        </div>
    );
};

export default MergeSingleTypesStep;
