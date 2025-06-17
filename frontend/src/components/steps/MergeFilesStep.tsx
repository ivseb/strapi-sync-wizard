import React, {useEffect, useState, useRef} from 'react';
import {Message} from 'primereact/message';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {TabPanel, TabView} from 'primereact/tabview';
import {ProgressSpinner} from 'primereact/progressspinner';
import {Tag} from 'primereact/tag';
import {Badge} from 'primereact/badge';
import {Button} from 'primereact/button';
import axios from 'axios';
import {ContentTypeFileComparisonResult, DifferentFile, MergeRequestSelectionDTO, StrapiImage, StrapiImageMetadata, MergeRequestDetail} from '../../types';

// Content type for files
const STRAPI_FILE_CONTENT_TYPE_NAME = "plugin::upload.file";

interface MergeFilesStepProps {
    mergeRequestId: number;
    filesData?: ContentTypeFileComparisonResult;
    loading?: boolean;
    updateSingleSelection: (contentType: string, documentId: string, direction: string, isSelected: boolean) => Promise<boolean>;
    selections?: MergeRequestSelectionDTO[];
}

interface SelectedContentTypeEntries {
    entriesToCreate: StrapiImage[];
    entriesToUpdate: DifferentFile[];
    entriesToDelete: StrapiImage[];
}

const MergeFilesStep: React.FC<MergeFilesStepProps> = ({

                                                           mergeRequestId,
                                                           filesData,
                                                           loading: parentLoading,
                                                           updateSingleSelection,
                                                           selections
                                                       }) => {
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);
    const [comparisonResult, setComparisonResult] = useState<ContentTypeFileComparisonResult | null>(null);
    const [selectedEntries, setSelectedEntries] = useState<SelectedContentTypeEntries>({
        entriesToCreate: [],
        entriesToUpdate: [],
        entriesToDelete: []
    });
    const [activeTabIndex, setActiveTabIndex] = useState<number>(0);

    // Use refs to track the latest values for the cleanup function
    const selectedEntriesRef = useRef(selectedEntries);
    const comparisonResultRef = useRef(comparisonResult);
    const errorRef = useRef(error);

    // Update refs when values change
    useEffect(() => {
        selectedEntriesRef.current = selectedEntries;
    }, [selectedEntries]);

    useEffect(() => {
        comparisonResultRef.current = comparisonResult;
    }, [comparisonResult]);

    useEffect(() => {
        errorRef.current = error;
    }, [error]);

    // Use filesData from props if available, otherwise show loading state
    useEffect(() => {
        // If filesData is provided from parent, use it
        if (filesData) {
            setComparisonResult(filesData);
            setLoading(false);
            setError(null);
        } else {
            // If parent is still loading, show loading state
            setLoading(true);
        }
    }, [mergeRequestId, filesData]);

    // Initialize selected entries from selections prop
    useEffect(() => {
        if (comparisonResult && selections) {
            // Find the selection for the file content type
            const fileSelection = selections.find((s: MergeRequestSelectionDTO) => s.contentType === STRAPI_FILE_CONTENT_TYPE_NAME);

            if (fileSelection) {
                // Filter the comparison results to match the selected files
                const entriesToCreate = comparisonResult.onlyInSource.filter(
                    file => fileSelection.entriesToCreate.includes(file.metadata.documentId)
                );

                const entriesToUpdate = comparisonResult.different.filter(
                    file => fileSelection.entriesToUpdate.includes(file.source.metadata.documentId)
                );

                const entriesToDelete = comparisonResult.onlyInTarget.filter(
                    file => fileSelection.entriesToDelete.includes(file.metadata.documentId)
                );

                // Update the selected entries state
                setSelectedEntries({
                    entriesToCreate,
                    entriesToUpdate,
                    entriesToDelete
                });
            }
        }
    }, [comparisonResult, selections]);

    // Function to handle file selection changes
    const handleFileSelection = async (file: StrapiImage, direction: string, isSelected: boolean) => {
        try {
            await updateSingleSelection(
                STRAPI_FILE_CONTENT_TYPE_NAME,
                file.metadata.documentId,
                direction,
                isSelected
            );
            return true;
        } catch (err: any) {
            console.error('Error updating file selection:', err);
            setError(err.response?.data?.message || 'Failed to update file selection');
            return false;
        }
    };

    // Function to handle file update selection changes
    const handleFileUpdateSelection = async (file: DifferentFile, isSelected: boolean) => {
        try {
            await updateSingleSelection(
                STRAPI_FILE_CONTENT_TYPE_NAME,
                file.source.metadata.documentId,
                'TO_UPDATE',
                isSelected
            );
            return true;
        } catch (err: any) {
            console.error('Error updating file selection:', err);
            setError(err.response?.data?.message || 'Failed to update file selection');
            return false;
        }
    };




    // File name template
    const fileNameTemplate = (rowData: StrapiImage) => {
        return rowData.metadata.name || 'Unknown';
    };

    // Document ID template
    const documentIdTemplate = (rowData: StrapiImage) => {
        return rowData.metadata.documentId || 'Unknown';
    };

    // File size template
    const fileSizeTemplate = (rowData: StrapiImage) => {
        const size = rowData.metadata.size || 0;
        if (size < 1024) return `${size} B`;
        if (size < 1024 * 1024) return `${(size / 1024).toFixed(2)} KB`;
        return `${(size / (1024 * 1024)).toFixed(2)} MB`;
    };

    // File type template
    const fileTypeTemplate = (rowData: StrapiImage) => {
        return rowData.metadata.mime || 'Unknown';
    };

    // Image URL template
    const fileUrl = (rowData: StrapiImage) => {
        return <img 
            src={rowData.metadata.url} 
            alt={rowData.metadata.name} 
            style={{ maxWidth: '100px', maxHeight: '100px', objectFit: 'contain' }}
        />;
    };

    // Caption template
    const captionTemplate = (rowData: StrapiImage) => {
        return rowData.metadata.caption || '';
    };

    // Alternative text template
    const altTextTemplate = (rowData: StrapiImage) => {
        return rowData.metadata.alternativeText || '';
    };

    // Folder path template
    const folderPathTemplate = (rowData: StrapiImage) => {
        return rowData.metadata.folderPath || '';
    };


    // Difference template for comparing source and target
    const differenceTemplate = (rowData: DifferentFile, field: string) => {
        const sourceValue = rowData.source.metadata[field as keyof StrapiImageMetadata];
        const targetValue = rowData.target.metadata[field as keyof StrapiImageMetadata];

        if (sourceValue === targetValue) {
            return    <div className="mb-2">
                <Tag severity="success" value="=" className="mr-2"/>
                <span>{sourceValue}</span>
            </div>
        }

        return (
            <div className="flex flex-column">
                <div className="mb-2">
                    <Tag severity="info" value="Source" className="mr-2"/>
                    <span>{sourceValue}</span>
                </div>
                <div>
                    <Tag severity="warning" value="Target" className="mr-2"/>
                    <span>{targetValue}</span>
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
                <h3>Merge Files</h3>
                <Message severity="error" text={error} className="w-full mb-3"/>
            </div>
        );
    }

    // No comparison result
    if (!comparisonResult) {
        return (
            <div>
                <h3>Merge Files</h3>
                <Message severity="warn" text="No file comparison data available." className="w-full mb-3"/>
            </div>
        );
    }

    const createTabHeader = (options:any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{ cursor: 'pointer' }} onClick={options.onClick}>
                {selectedEntries.entriesToCreate.length > 0 && (
                    <Badge value={selectedEntries.entriesToCreate.length} severity="info" className="ml-2" />
                )}
                <span className="font-bold white-space-nowrap">To Create ({comparisonResult.onlyInSource.length})</span>
            </div>
        );
    };

    const updateTabHeader = (options:any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{ cursor: 'pointer' }} onClick={options.onClick}>
                {selectedEntries.entriesToUpdate.length > 0 && (
                    <Badge value={selectedEntries.entriesToUpdate.length} severity="warning" className="ml-2" />
                )}
                <span className="font-bold white-space-nowrap">To Update ({comparisonResult.different.length})</span>
            </div>
        );
    };

    const deleteTabHeader = (options:any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{ cursor: 'pointer' }} onClick={options.onClick}>
                {selectedEntries.entriesToDelete.length > 0 && (
                    <Badge value={selectedEntries.entriesToDelete.length} severity="danger" className="ml-2" />
                )}
                <span className="font-bold white-space-nowrap">To Delete ({comparisonResult.onlyInTarget.length})</span>
            </div>
        );
    };

    const identicalTabHeader = (options:any) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{ cursor: 'pointer' }} onClick={options.onClick}>
                <span className="font-bold white-space-nowrap">Identical ({comparisonResult.identical.length})</span>
            </div>
        );
    };

    return (
        <div>
            <h3>Merge Files</h3>
            <p>
                This step allows you to select which files to create, update, or delete on the target instance.
                Review the differences and make your selections before proceeding.
            </p>

            <TabView activeIndex={activeTabIndex} onTabChange={(e) => setActiveTabIndex(e.index)}>
                {/* Files only in source (to create) */}
                <TabPanel headerTemplate={createTabHeader}>
                    <DataTable dataKey={"metadata.documentId"}
                               selectionMode="multiple"
                               selection={selectedEntries.entriesToCreate}
                               onSelectionChange={e => {
                                   // Get the current selection
                                   const currentSelection = selectedEntries.entriesToCreate;
                                   const newSelection = e.value as StrapiImage[];

                                   // Find items that were added or removed
                                   if (currentSelection.length < newSelection.length) {
                                       // Item was added - find which one
                                       const addedItem = newSelection.find(item => 
                                           !currentSelection.some(current => current.metadata.documentId === item.metadata.documentId)
                                       );
                                       if (addedItem) {
                                           // Call API to update selection
                                           handleFileSelection(addedItem, 'TO_CREATE', true);
                                       }
                                   } else if (currentSelection.length > newSelection.length) {
                                       // Item was removed - find which one
                                       const removedItem = currentSelection.find(item => 
                                           !newSelection.some(current => current.metadata.documentId === item.metadata.documentId)
                                       );
                                       if (removedItem) {
                                           // Call API to update selection
                                           handleFileSelection(removedItem, 'TO_CREATE', false);
                                       }
                                   }

                                   // Update local state
                                   setSelectedEntries(prevState => {
                                       return {...prevState, entriesToCreate: newSelection}
                                   });
                               }}
                               value={comparisonResult.onlyInSource} paginator rows={5}
                               emptyMessage="No files to create">
                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}></Column>
                        <Column header="ID " body={(rowData) => rowData.metadata.documentId}/>
                        <Column field="metadata.name" header="File Name" body={fileNameTemplate} sortable/>
                        <Column field="metadata.folderPath" header="Folder Path" body={folderPathTemplate} sortable/>
                        <Column field="metadata.size" header="Size" body={fileSizeTemplate} sortable/>
                        <Column field="metadata.mime" header="Type" body={fileTypeTemplate} sortable/>
                        <Column field="metadata.url" header="Image" body={fileUrl} sortable/>
                        <Column field="metadata.caption" header="Caption" body={captionTemplate} sortable/>
                        <Column field="metadata.alternativeText" header="Alt Text" body={altTextTemplate} sortable/>
                    </DataTable>
                </TabPanel>

                {/* Different files (to update) */}
                <TabPanel headerTemplate={updateTabHeader}>
                    <DataTable dataKey={"source.metadata.documentId"}
                               selectionMode="multiple"
                               selection={selectedEntries.entriesToUpdate}
                               onSelectionChange={e => {
                                   // Get the current selection
                                   const currentSelection = selectedEntries.entriesToUpdate;
                                   const newSelection = e.value as DifferentFile[];

                                   // Find items that were added or removed
                                   if (currentSelection.length < newSelection.length) {
                                       // Item was added - find which one
                                       const addedItem = newSelection.find(item => 
                                           !currentSelection.some(current => current.source.metadata.documentId === item.source.metadata.documentId)
                                       );
                                       if (addedItem) {
                                           // Call API to update selection
                                           handleFileUpdateSelection(addedItem, true);
                                       }
                                   } else if (currentSelection.length > newSelection.length) {
                                       // Item was removed - find which one
                                       const removedItem = currentSelection.find(item => 
                                           !newSelection.some(current => current.source.metadata.documentId === item.source.metadata.documentId)
                                       );
                                       if (removedItem) {
                                           // Call API to update selection
                                           handleFileUpdateSelection(removedItem, false);
                                       }
                                   }

                                   // Update local state
                                   setSelectedEntries(prevState => {
                                       return {...prevState, entriesToUpdate: newSelection}
                                   });
                               }}
                               value={comparisonResult.different} paginator rows={5}
                               emptyMessage="No files to update">
                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}></Column>
                        <Column header="SourceId " body={(rowData) => rowData.source.metadata.documentId}/>
                        <Column header="target " body={(rowData) => rowData.target.metadata.documentId}/>
                        <Column header="File Name" body={(rowData) => differenceTemplate(rowData, 'name')}/>
                        <Column header="Folder Path" body={(rowData) => differenceTemplate(rowData, 'folderPath')}/>
                        <Column header="Size" body={(rowData) => differenceTemplate(rowData, 'size')}/>
                        <Column header="Type" body={(rowData) => differenceTemplate(rowData, 'mime')}/>
                        <Column header="Image" body={(rowData) => (
                            <div className="flex flex-row">
                                <div className="mr-4 flex flex-column align-items-center">
                                    <Tag severity="info" value="Source" className="mb-2"/>
                                    <img 
                                        src={rowData.source.metadata.url} 
                                        alt={rowData.source.metadata.name} 
                                        style={{ maxWidth: '100px', maxHeight: '100px', objectFit: 'contain' }}
                                    />
                                </div>
                                <div className="flex flex-column align-items-center">
                                    <Tag severity="warning" value="Target" className="mb-2"/>
                                    <img 
                                        src={rowData.target.metadata.url} 
                                        alt={rowData.target.metadata.name} 
                                        style={{ maxWidth: '100px', maxHeight: '100px', objectFit: 'contain' }}
                                    />
                                </div>
                            </div>
                        )}/>
                        <Column header="Caption" body={(rowData) => differenceTemplate(rowData, 'caption')}/>
                        <Column header="Alt Text" body={(rowData) => differenceTemplate(rowData, 'alternativeText')}/>
                    </DataTable>
                </TabPanel>

                {/* Files only in target (to delete) */}
                <TabPanel headerTemplate={deleteTabHeader}>
                    <DataTable dataKey={"metadata.documentId"}
                               selectionMode="multiple"
                               selection={selectedEntries.entriesToDelete}
                               onSelectionChange={e => {
                                   // Get the current selection
                                   const currentSelection = selectedEntries.entriesToDelete;
                                   const newSelection = e.value as StrapiImage[];

                                   // Find items that were added or removed
                                   if (currentSelection.length < newSelection.length) {
                                       // Item was added - find which one
                                       const addedItem = newSelection.find(item => 
                                           !currentSelection.some(current => current.metadata.documentId === item.metadata.documentId)
                                       );
                                       if (addedItem) {
                                           // Call API to update selection
                                           handleFileSelection(addedItem, 'TO_DELETE', true);
                                       }
                                   } else if (currentSelection.length > newSelection.length) {
                                       // Item was removed - find which one
                                       const removedItem = currentSelection.find(item => 
                                           !newSelection.some(current => current.metadata.documentId === item.metadata.documentId)
                                       );
                                       if (removedItem) {
                                           // Call API to update selection
                                           handleFileSelection(removedItem, 'TO_DELETE', false);
                                       }
                                   }

                                   // Update local state
                                   setSelectedEntries(prevState => {
                                       return {...prevState, entriesToDelete: newSelection}
                                   });
                               }}
                               value={comparisonResult.onlyInTarget} paginator rows={5}
                               emptyMessage="No files to delete">
                        <Column selectionMode="multiple" headerStyle={{width: '3rem'}}></Column>
                        <Column field="metadata.name" header="File Name" body={fileNameTemplate} sortable/>
                        <Column field="metadata.folderPath" header="Folder Path" body={folderPathTemplate} sortable/>

                        <Column field="metadata.size" header="Size" body={fileSizeTemplate} sortable/>
                        <Column field="metadata.mime" header="Type" body={fileTypeTemplate} sortable/>
                        <Column field="metadata.url" header="Image" body={fileUrl} sortable/>
                        <Column field="metadata.caption" header="Caption" body={captionTemplate} sortable/>
                        <Column field="metadata.alternativeText" header="Alt Text" body={altTextTemplate} sortable/>
                    </DataTable>
                </TabPanel>

                {/* Identical files (no action needed) */}
                <TabPanel headerTemplate={identicalTabHeader}>
                    <DataTable value={comparisonResult.identical} paginator rows={5}
                               emptyMessage="No identical files">
                        <Column field="metadata.name" header="File Name" body={fileNameTemplate} sortable/>
                        <Column field="metadata.folderPath" header="Folder Path" body={folderPathTemplate} sortable/>
                        <Column field="metadata.size" header="Size" body={fileSizeTemplate} sortable/>
                        <Column field="metadata.mime" header="Type" body={fileTypeTemplate} sortable/>
                        <Column field="metadata.url" header="Image" body={fileUrl} sortable/>
                        <Column field="metadata.caption" header="Caption" body={captionTemplate} sortable/>
                        <Column field="metadata.alternativeText" header="Alt Text" body={altTextTemplate} sortable/>
                    </DataTable>
                </TabPanel>
            </TabView>
        </div>
    );
};

export default MergeFilesStep;
