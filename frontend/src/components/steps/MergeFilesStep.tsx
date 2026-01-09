import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Message} from 'primereact/message';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {TabPanel, TabView} from 'primereact/tabview';
import {ProgressSpinner} from 'primereact/progressspinner';
import {Tag} from 'primereact/tag';
import {Badge} from 'primereact/badge';
import {InputText} from 'primereact/inputtext';
import {
    ContentTypeComparisonResultKind,
    ContentTypeComparisonResultWithRelationships,
    ContentTypeFileComparisonResult,
    MergeRequestData,
    MergeRequestSelectionDTO,
    StrapiContent,
    StrapiContentTypeKind,
    StrapiImage,
    StrapiImageMetadata
} from '../../types';
import {groupByToArray, GroupEntry} from "../../utils/arrayGroupingUtilities";
import {Button} from "primereact/button";
import ManualCollectionMapper from './components/ManualCollectionMapper';
import axios from 'axios';
import ExclusionsManager from './components/ExclusionsManager';


interface MergeFilesStepProps {
    mergeRequestId: number;
    filesData?: ContentTypeFileComparisonResult[];
    loading?: boolean;
    updateAllSelections: (kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string, documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind) => Promise<boolean>;
    selections: MergeRequestSelectionDTO[];
    allMergeData: MergeRequestData;
    onSaved?: (data?: MergeRequestData) => void | Promise<void>;
}

const MergeFilesStep: React.FC<MergeFilesStepProps> = ({

                                                           mergeRequestId,
                                                           filesData,
                                                           loading: parentLoading,

                                                           updateAllSelections,
                                                           selections,
                                                           allMergeData,
                                                           onSaved
                                                       }) => {
    const [loading, setLoading] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);

    const [showManualMapper, setShowManualMapper] = useState<boolean>(false);
    const [showExclusionsManager, setShowExclusionsManager] = useState<boolean>(false);
    const [editorDialogVisible, setEditorDialogVisible] = useState<boolean>(false);
    const [isDiffEditor, setIsDiffEditor] = useState<boolean>(false);
    const [originalContent, setOriginalContent] = useState<any>(null);
    const [modifiedContent, setModifiedContent] = useState<any>(null);
    const [editorTargetInfo, setEditorTargetInfo] = useState<{contentType: string, documentId: string} | null>(null);

    const [activeTabIndex, setActiveTabIndex] = useState<number>(0);
    const [globalFilter, setGlobalFilter] = useState<string>('');

    // Loading states for each table
    const [updateTableLoading, setUpdateTableLoading] = useState<boolean>(false);


    const errorRef = useRef(error);


    useEffect(() => {
        errorRef.current = error;
    }, [error]);


    const filesAsCollection: Record<string, ContentTypeComparisonResultWithRelationships[]> = useMemo(() => {
        const list: ContentTypeComparisonResultWithRelationships[] = (filesData || []).map((f) => {
            const toContent = (img?: StrapiImage | null): StrapiContent | undefined => {
                if (!img) return undefined as any;
                return {
                    metadata: {
                        id: img.metadata.id,
                        documentId: img.metadata.documentId,
                        locale: img.metadata.locale
                    },
                    rawData: img.rawData,
                    cleanData: img.rawData,
                    links: []
                } as StrapiContent;
            };
            return {
                id: f.id,
                tableName: 'files',
                contentType: 'files',
                sourceContent: toContent(f.sourceImage) as any,
                targetContent: toContent(f.targetImage) as any,
                compareState: f.compareState,
                kind: StrapiContentTypeKind.Files,
            } as ContentTypeComparisonResultWithRelationships;
        });
        return {files: list};
    }, [filesData]);

    // File size template
    const fileSizeTemplate = (sizeIn: number) => {
        const size = sizeIn * 1000
        if (size < 1024) return `${size} B`;
        if (size < 1024 * 1024) return `${(size / 1024).toFixed(2)} KB`;
        return `${(size / (1024 * 1024)).toFixed(2)} MB`;
    };


    // Difference template for comparing source and target
    const differenceTemplate = (rowData: ContentTypeFileComparisonResult, field: string) => {
        let sourceValue = rowData.sourceImage?.metadata[field as keyof StrapiImageMetadata];
        let targetValue = rowData.targetImage?.metadata[field as keyof StrapiImageMetadata];
        if (field == "size") {
            sourceValue = sourceValue ? fileSizeTemplate(sourceValue as number) : sourceValue
            targetValue = targetValue ? fileSizeTemplate(targetValue as number) : targetValue
        }

        if (sourceValue === undefined || targetValue === undefined)
            return sourceValue || targetValue;

        if (sourceValue === targetValue) {
            return <div className="mb-2">
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
    if (!filesData) {
        return (
            <div>
                <h3>Merge Files</h3>
                <Message severity="warn" text="No file comparison data available." className="w-full mb-3"/>
            </div>
        );
    }

    const updateTabHeader = (options: any, kind: ContentTypeComparisonResultKind, size: number, selectionSize: number) => {
        return (
            <div className="flex align-items-center gap-2 p-3" style={{cursor: 'pointer'}} onClick={options.onClick}>
                {selectionSize > 0 && <Badge
                    value={selectionSize}
                    severity="warning" className="ml-2"/>}
                <span className="font-bold white-space-nowrap">{kind} ({size}) </span>
            </div>
        );
    };

    const fileTagTemplate = (strapiImage?: StrapiImage | null) => {
        if (!strapiImage) {
            return
        }
        const isImage = strapiImage.metadata.mime?.startsWith('image')
        return <>
            {isImage ? <img
                src={strapiImage.metadata.url}
                alt={strapiImage.metadata.name}
                style={{maxWidth: '100px', maxHeight: '100px', objectFit: 'contain'}}
            /> : <Button label="View" icon="pi pi-eye" className="p-button-text" onClick={() => {
                window.open(strapiImage.metadata.url, '_blank')
            }}></Button>}
        </>
    }

    const imageTemplate = (rowData: ContentTypeFileComparisonResult) => {
        if (!rowData.sourceImage && !rowData.targetImage) {
            return
        }

        const isAssociated = rowData.sourceImage && rowData.targetImage;

        return <div className="flex flex-column gap-2">
            <div className="flex flex-row">
                {rowData.sourceImage && (
                    <div className="mr-4 flex flex-column align-items-center">
                        <Tag severity="info" value="Source" className="mb-2"/>
                        {fileTagTemplate(rowData.sourceImage)}
                    </div>
                )}
                {rowData.targetImage && (
                    <div className="flex flex-column align-items-center">
                        <Tag severity="warning" value="Target" className="mb-2"/>
                        {fileTagTemplate(rowData.targetImage)}
                    </div>
                )}
            </div>
            {isAssociated && (
                <div className="flex justify-content-center">
                    <Button 
                        icon="pi pi-link-slash" 
                        label="Scollega" 
                        className="p-button-text p-button-danger p-button-sm"
                        tooltip="Rimuovi questa associazione automatica o manuale"
                        onClick={async () => {
                            if (!window.confirm('Vuoi davvero rimuovere questa associazione? Il ricalcolo avverrà automaticamente.')) return;
                            try {
                                setUpdateTableLoading(true);
                                // Cerchiamo il mapping id tra i mapping salvati
                                const res = await axios.get(`/api/merge-requests/${mergeRequestId}/mappings`, { params: { contentType: 'plugin::upload.file' } });
                                const mappings = res.data?.data || [];
                                const mapping = mappings.find((m: any) => 
                                    m.sourceDocumentId === rowData.sourceImage?.metadata.documentId && 
                                    m.targetDocumentId === rowData.targetImage?.metadata.documentId
                                );
                                
                                if (mapping) {
                                    await axios.delete(`/api/merge-requests/${mergeRequestId}/mappings/${mapping.id}`);
                                    if (onSaved) await onSaved();
                                } else {
                                    alert("Associazione persistente non trovata. Potrebbe essere un'associazione temporanea in memoria.");
                                }
                            } catch (e) {
                                console.error('Errore durante la rimozione del mapping', e);
                                alert("Errore durante la rimozione del mapping");
                            } finally {
                                setUpdateTableLoading(false);
                            }
                        }}
                    />
                    <Button 
                        icon="pi pi-ban" 
                        label="Escludi" 
                        className="p-button-text p-button-warning p-button-sm"
                        tooltip="Escludi questo file dalla sincronizzazione (vincolo ambientale)"
                        onClick={async () => {
                            const docId = rowData.sourceImage?.metadata.documentId || rowData.targetImage?.metadata.documentId;
                            if (!docId) return;
                            if (!window.confirm(`Vuoi escludere il file ${docId} dalla sincronizzazione per sempre?`)) return;
                            try {
                                setUpdateTableLoading(true);
                                await axios.post(`/api/merge-requests/${mergeRequestId}/exclusions`, {
                                    contentType: 'plugin::upload.file',
                                    documentId: docId
                                });
                                if (onSaved) await onSaved();
                            } catch (e) {
                                console.error('Errore durante l\'esclusione del file', e);
                                alert("Errore durante l'esclusione del file");
                            } finally {
                                setUpdateTableLoading(false);
                            }
                        }}
                    />
                </div>
            )}
        </div>
    }


    const groupedElements: GroupEntry<ContentTypeComparisonResultKind, ContentTypeFileComparisonResult>[] = groupByToArray(filesData, p => p.compareState);

    return (
        <div>
            <h3>Merge Files</h3>
            <p>
                This step allows you to select which files to create, update, or delete on the target instance.
                Review the differences and make your selections before proceeding.
            </p>

            <div className="flex justify-content-between align-items-center mb-3">
                <span className="p-input-icon-left w-20rem">
                    <i className="pi pi-search" />
                    <InputText
                        type="search"
                        value={globalFilter}
                        onChange={(e) => setGlobalFilter(e.target.value)}
                        placeholder="Ricerca globale..."
                        className="w-full"
                    />
                </span>
                <Button
                    label="Associa manualmente (files)"
                    icon="pi pi-link"
                    className="mr-2"
                    onClick={() => setShowManualMapper(true)}
                />
                <Button
                    label="Gestisci vincoli"
                    icon="pi pi-ban"
                    severity="warning"
                    onClick={() => setShowExclusionsManager(true)}
                />
            </div>

            <TabView activeIndex={activeTabIndex} onTabChange={(e) => setActiveTabIndex(e.index)}>
                {groupedElements.map((group: GroupEntry<ContentTypeComparisonResultKind, ContentTypeFileComparisonResult>, index) => {

                    const disableSelection = group.key === 'IDENTICAL'
                    const selection = group.items.filter(x => {
                        const tableSelectionsIds = selections.filter(s => s.tableName === 'files').flatMap(x => x.selections.map(y => y.documentId))
                        return tableSelectionsIds.includes(x.id)
                    })
                    const isSelectAll = selection.length === group.items.length

                    return <TabPanel
                        key={group.key}
                        headerTemplate={options => updateTabHeader(options, group.key, group.items.length, selection.length)}>

                        <DataTable dataKey={"id"}
                                   selectionMode="multiple"
                                   selection={selection}
                                   selectAll={isSelectAll}
                                   loading={updateTableLoading}
                                   onSelectAllChange={e => {
                                       setUpdateTableLoading(true);
                                       updateAllSelections(StrapiContentTypeKind.Files, !isSelectAll, "files", undefined, group.key)
                                           .then(() => {
                                               setUpdateTableLoading(false);
                                           })
                                           .catch(error => {
                                               setUpdateTableLoading(false);
                                               setError(error);
                                           });

                                   }}
                                   onSelectionChange={(e) => {

                                       const newSelectionList = e.value as ContentTypeFileComparisonResult[];
                                       const selectionToAddList = newSelectionList.filter(x => !selection.some(y => y.id === x.id)).map(x => {
                                           return {
                                               data: x,
                                               isSelected: true,
                                           }
                                       })
                                       const selectionToRemoveList = selection.filter(x => !newSelectionList.some(y => y.id === x.id)).map(x => {
                                           return {
                                               data: x,
                                               isSelected: false,
                                           }
                                       })
                                       const selectionList = [...selectionToAddList, ...selectionToRemoveList]
                                       selectionList.forEach(selected => {
                                           setUpdateTableLoading(true);
                                           updateAllSelections(StrapiContentTypeKind.Files, selected.isSelected, "files", [selected.data.id])
                                               .then(() => {
                                                   setUpdateTableLoading(false);
                                               })
                                               .catch(error => {
                                                   setUpdateTableLoading(false);
                                                   setError(error);
                                               });
                                       })
                                   }}
                                   value={group.items}
                                   paginator
                                   globalFilter={globalFilter}
                                   globalFilterFields={[
                                       'sourceImage.metadata.name', 'targetImage.metadata.name',
                                       'sourceImage.metadata.documentId', 'targetImage.metadata.documentId',
                                       'sourceImage.metadata.folder', 'targetImage.metadata.folder',
                                       'sourceImage.metadata.alternativeText', 'targetImage.metadata.alternativeText',
                                       'sourceImage.metadata.caption', 'targetImage.metadata.caption'
                                   ]}
                                   rows={5}
                                   rowsPerPageOptions={[5, 10, 25, 50]}
                                   emptyMessage="No files to update">
                            {!disableSelection &&
                                <Column selectionMode="multiple" headerStyle={{width: '3rem'}}></Column>}
                            <Column header="Id"
                                    body={(rowData: ContentTypeFileComparisonResult) => differenceTemplate(rowData, 'documentId')}/>
                            <Column header="File Name"
                                    body={(rowData: ContentTypeFileComparisonResult) => differenceTemplate(rowData, 'name')}/>
                            <Column header="Folder Path"
                                    body={(rowData: ContentTypeFileComparisonResult) => differenceTemplate(rowData, 'folder')}/>
                            <Column header="Size"
                                    body={(rowData: ContentTypeFileComparisonResult) => differenceTemplate(rowData, 'size')}/>
                            <Column header="Type"
                                    body={(rowData: ContentTypeFileComparisonResult) => differenceTemplate(rowData, 'mime')}/>
                            <Column header="Image" body={(rowData) => (
                                imageTemplate(rowData)
                            )}/>
                            {/*<Column header="Caption" body={(rowData) => differenceTemplate(rowData, 'caption')}/>*/}
                            {/*<Column header="Alt Text"*/}
                            {/*        body={(rowData) => differenceTemplate(rowData, 'alternativeText')}/>*/}
                        </DataTable>
                    </TabPanel>
                })}
            </TabView>

            {/* Manual mapper for files */}
            <ManualCollectionMapper
                visible={showManualMapper}
                onHide={() => setShowManualMapper(false)}
                mergeRequestId={mergeRequestId}
                collectionTypesData={filesAsCollection}
                allMergeData={allMergeData}
                fixedTable={'files'}
                onSaved={onSaved}
            />

            <ExclusionsManager 
                visible={showExclusionsManager}
                onHide={() => setShowExclusionsManager(false)}
                mergeRequestId={mergeRequestId}
                onExclusionsChanged={() => onSaved && onSaved()}
            />
        </div>
    );
};

export default MergeFilesStep;
