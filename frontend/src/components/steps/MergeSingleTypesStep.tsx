import React, {useEffect, useRef, useState} from 'react';
import {Button} from 'primereact/button';
import {Message} from 'primereact/message';
import {TabPanel, TabView} from 'primereact/tabview';
import {ProgressSpinner} from 'primereact/progressspinner';

// Import types
import {
    ContentTypeComparisonResultKind,
    ContentTypeComparisonResultWithRelationships,
    MergeRequestData,
    MergeRequestSelectionDTO,
    StrapiContent,
    StrapiContentTypeKind
} from '../../types';
import {getRepresentativeAttributes} from '../../utils/attributeUtils';
import EditorDialog from '../common/EditorDialog';
import {groupByToArray, GroupEntry} from '../../utils/arrayGroupingUtilities';
import {DataTable} from "primereact/datatable";
import {Column} from "primereact/column";
import {Badge} from "primereact/badge";
import RelationshipCard from "../common/RelationshipCard";
import {buildRelationshipsFromLinks, isComparisonItemResolved, RelationshipStatus} from "../../utils/entryUtils";
import ManualCollectionMapper from './components/ManualCollectionMapper';


interface MergeSingleTypesStepProps {
    status: string;
    kind: StrapiContentTypeKind,
    mergeSingleTypes: () => void;
    mergeRequestId: number;
    contentData?: Record<string, ContentTypeComparisonResultWithRelationships[]>;
    selections: MergeRequestSelectionDTO[];
    loading?: boolean;
    allMergeData: MergeRequestData;
    updateAllSelections: (kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string, documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind) => Promise<boolean>;
    onSaved?: (data?: MergeRequestData) => void | Promise<void>;
}

// Use the imported types instead of redefining them
// ContentTypeComparisonResultWithRelationships, EntryRelationship, SelectedContentTypeDependency are imported from '../../types'

interface ContentTypeWithRelationStatus {
    content: ContentTypeComparisonResultWithRelationships
    relationships: RelationshipStatus[]

}

const MergeSingleTypesStep: React.FC<MergeSingleTypesStepProps> = ({
                                                                       status,
                                                                       kind,
                                                                       mergeSingleTypes,
                                                                       mergeRequestId,
                                                                       contentData,
                                                                       selections,
                                                                       loading: parentLoading,
                                                                       allMergeData,
                                                                       updateAllSelections,
                                                                       onSaved
                                                                   }) => {

    const title = kind === StrapiContentTypeKind.SingleType ? "Single Types" : "Collection Types"
    const [loading, setLoading] = useState<boolean>(true);
    const [error, setError] = useState<string | null>(null);

    const [contentTypes, setContentTypes] = useState<ContentTypeWithRelationStatus[]>([]);
    const [activeTabIndex, setActiveTabIndex] = useState<number>(0);
    // Removed modal-related state variables

    // State for expanded rows

    const [expandedToUpdateTypes, setExpandedToUpdateTypes] = useState<Record<string, boolean>>({});


    // Loading states for each table

    const [updateTableLoading, setUpdateTableLoading] = useState<boolean>(false);


    // State for editor modal
    const [editorDialogVisible, setEditorDialogVisible] = useState<boolean>(false);
    const [editorContent, setEditorContent] = useState<any>(null);
    const [isDiffEditor, setIsDiffEditor] = useState<boolean>(false);
    const [originalContent, setOriginalContent] = useState<any>(null);
    const [modifiedContent, setModifiedContent] = useState<any>(null);
    const [editorDialogHeader, setEditorDialogHeader] = useState<string>("View Content");

    // Manual mapping dialog visibility (for collections)
    const [showManualMapper, setShowManualMapper] = useState<boolean>(false);

    // Use refs to track the latest values for the cleanup function
    const errorRef = useRef(error);

    // Mapping helper: documentId -> comparison id per tableName
    const docIdToIdMapRef = useRef<Record<string, Record<string, string>>>({});

    useEffect(() => {
        if (!contentData) return;
        const map: Record<string, Record<string, string>> = {};
        Object.entries(contentData).forEach(([tableName, v]) => {
            if (!v) return;
            const cmpId = (v as any).id as string;
            const sc = (v as any).sourceContent?.metadata?.documentId as string | undefined;
            const tc = (v as any).targetContent?.metadata?.documentId as string | undefined;
            map[tableName] = map[tableName] || {};
            if (sc) map[tableName][sc] = cmpId;
            if (tc) map[tableName][tc] = cmpId;
        });
        docIdToIdMapRef.current = map;
    }, [contentData]);


    useEffect(() => {
        errorRef.current = error;
    }, [error]);

    const isDisabled =
        (status !== 'MERGED_FILES' &&
            status !== 'MERGED_SINGLES' &&
            status !== 'MERGED_COLLECTIONS' &&
            status !== 'COMPLETED');

    // Use singleTypesData from props if available, otherwise show loading state
    useEffect(() => {
        // If singleTypesData is provided from parent, use it
        if (contentData) {

            // Convert the map to an array
            const singleTypesArray: ContentTypeWithRelationStatus[] = Object.values(contentData).flatMap((contentList: ContentTypeComparisonResultWithRelationships[]) => {
                return contentList.map(x => {
                    const rels = buildRelationshipsFromLinks(allMergeData, x)
                    return {
                        content: x,
                        relationships: rels
                    }
                })
            });
            setContentTypes(singleTypesArray);

            // Initialize selected entries for each content type with empty arrays


            setLoading(false);
            setError(null);
        } else {
            // If parent is still loading, show loading state
            setLoading(true);
        }
    }, [mergeRequestId, contentData, selections]);

    // Check if a content type has dependencies
    const hasDependencies = (contentType: ContentTypeWithRelationStatus): boolean => {
        const linksCount = (contentType.content.sourceContent?.links?.length || 0) + (contentType.content.targetContent?.links?.length || 0);
        return linksCount > 0;
    };


    const renderRelationStatus = (rowData?: ContentTypeWithRelationStatus | null) => {
        if (!rowData) return <span className="text-500">No relations</span>;
        const rels = rowData.relationships || [];
        if (rels.length === 0) return <span className="text-500">No relations</span>;

        const total = rels.length;
        const identicalCount = rels.filter(r => r.identical).length;
        const resolvedCount = rels.filter(r => !r.identical && isComparisonItemResolved(allMergeData, r.data)).length;
        const unresolvedCount = Math.max(0, total - identicalCount - resolvedCount);

        return (
            <div className="flex flex-wrap align-items-center gap-2">
                <div className="flex align-items-center gap-1 white-space-nowrap" title="Total relations">
                    <i className="pi pi-link text-500"></i>
                    <span className="text-600">Total</span>
                    <Badge value={total} severity="info"/>
                </div>
                <div className="flex align-items-center gap-1 white-space-nowrap" title="Already synchronized">
                    <i className="pi pi-check-circle text-green-500"></i>
                    <span className="text-600">Synced</span>
                    <Badge value={identicalCount} severity="success"/>
                </div>
                <div className="flex align-items-center gap-1 white-space-nowrap" title="Resolved (selected)">
                    <i className="pi pi-check text-green-500"></i>
                    <span className="text-600">Resolved</span>
                    <Badge value={resolvedCount} severity="success"/>
                </div>
                <div className="flex align-items-center gap-1 white-space-nowrap" title="Pending resolution">
                    <i className="pi pi-exclamation-circle text-yellow-500"></i>
                    <span className="text-600">Pending</span>
                    <Badge value={unresolvedCount} severity="warning"/>
                </div>
            </div>
        )
    }
    const renderRepresentativeAttributes = (rowData?: StrapiContent | null) => {
        // For single types, the entry might be in different places depending on the compareState

        if (!rowData) return <span className="text-muted">No representative attributes</span>;
        const attributes = getRepresentativeAttributes(rowData);

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


    const selectedTableName = React.useMemo(() => {
        if (!contentData) return null as string | null;
        const keys = Object.keys(contentData);
        return keys.length > 0 ? keys[0] : null;
    }, [contentData]);

    // Helpers to derive relationships from StrapiContent.links instead of precomputed relationships


    // Function to render relationship details
    const renderDependencyDetails = (relationshipsToRender: RelationshipStatus[]) => {
        if (relationshipsToRender.length == 0) return <div>No related records</div>;


        return (
            <div className="p-3">
                <h5>Related Records</h5>
                <div className="grid">
                    {relationshipsToRender.map((r, index) => {


                        return (
                            <RelationshipCard
                                allMergeData={allMergeData}
                                link={r.link}
                                identical={r.identical}
                                content={r.data}
                                index={index}
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
                <h3>Merge {title}</h3>
                <Message severity="error" text={error} className="w-full mb-3"/>
            </div>
        );
    }

    // No content types
    if (contentTypes.length === 0) {
        return (
            <div>
                <h3>Merge {title}</h3>
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
                        label={"Merge " + title}
                        icon="pi pi-file"
                        disabled={isDisabled}
                        onClick={mergeSingleTypes}
                    />
                </div>
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

    const groupedElements: GroupEntry<ContentTypeComparisonResultKind, ContentTypeWithRelationStatus>[] = groupByToArray(contentTypes, p => p.content.compareState);


    return (
        <div>
            <h3>Merge {title}</h3>
            <p>
                This step allows you to select which single content types to create, update, or delete on the target
                instance.
                Review the differences and make your selections before proceeding.
            </p>
            <Message severity="info" className="mb-3" content={messageContent}/>

            {kind === StrapiContentTypeKind.CollectionType && (
                <div className="flex justify-content-end mb-3">
                    <Button
                        label="Associa manualmente"
                        icon="pi pi-link"
                        onClick={() => setShowManualMapper(true)}
                    />
                </div>
            )}

            <TabView activeIndex={activeTabIndex} onTabChange={(e) => setActiveTabIndex(e.index)}>
                {groupedElements.map((group: GroupEntry<ContentTypeComparisonResultKind, ContentTypeWithRelationStatus>) => {


                    const disableSelection = group.key === 'IDENTICAL'
                    const isDifference = group.key === 'DIFFERENT'

                    const selection = group.items.filter(x => {
                        const tableSelectionsIds = selections.filter(s => s.tableName === x.content.tableName).flatMap(x => x.selections.map(y => y.documentId))
                        return tableSelectionsIds.includes(x.content.id)
                    })


                    const isSelectAll = selection.length === group.items.length

                    return <TabPanel key={group.key}
                                     headerTemplate={options => updateTabHeader(options, group.key, group.items.length, selection.length)}>
                        <DataTable
                            value={group.items}
                            selectionMode={!disableSelection ? 'multiple' : null}
                            loading={updateTableLoading}
                            selection={selection}
                            selectAll={isSelectAll}
                            onSelectAllChange={() => {
                                if (disableSelection) return;
                                setUpdateTableLoading(true);
                                updateAllSelections(kind, !isSelectAll,undefined,undefined, group.key)
                                    .then(() => {
                                        setUpdateTableLoading(false);
                                    })
                                    .catch(error => {
                                        setUpdateTableLoading(false);
                                        setError(error);
                                    });

                            }}
                            onSelectionChange={(e: any) => {
                                if (disableSelection) return;
                                const newSelectionList = e.value as ContentTypeWithRelationStatus[];
                                const selectionToAddList = newSelectionList.filter(x => !selection.some(y => y.content.id === x.content.id)).map(x => {
                                    return {
                                        data: x.content,
                                        isSelected: true,
                                    }
                                })
                                const selectionToRemoveList = selection.filter(x => !newSelectionList.some(y => y.content.id === x.content.id)).map(x => {
                                    return {
                                        data: x.content,
                                        isSelected: false,
                                    }
                                })
                                const selectionList = [...selectionToAddList, ...selectionToRemoveList]
                                selectionList.forEach(selected => {
                                    setUpdateTableLoading(true);
                                    updateAllSelections(kind, selected.isSelected, selected.data.tableName, [selected.data.id])
                                        .then(() => {
                                            setUpdateTableLoading(false);
                                        })
                                        .catch(error => {
                                            setUpdateTableLoading(false);
                                            setError(error);
                                        });

                                })


                            }}
                            dataKey="content.id"
                            paginator
                            rows={5}
                            rowsPerPageOptions={[5, 10, 25, 50]}
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
                            rowExpansionTemplate={e => renderDependencyDetails(e.relationships)}
                        >
                            <Column expander={hasDependencies} style={{width: '3rem'}}/>
                            {!disableSelection && <Column selectionMode="multiple" headerStyle={{width: '3rem'}}/>}

                            <Column field="content.tableName" header="Content Type" sortable/>
                            <Column header="Representative Attributes Source"
                                    body={(e: ContentTypeWithRelationStatus) => renderRepresentativeAttributes(e.content.sourceContent || e.content.targetContent)}
                                    style={{minWidth: '20rem'}}/>
                            {isDifference &&
                                <Column header="Representative Attributes TARGET"
                                        body={(e: ContentTypeWithRelationStatus) => renderRepresentativeAttributes(e.content.targetContent || e.content.sourceContent)}
                                        style={{minWidth: '20rem'}}/>
                            }
                            <Column header="Relations status"
                                    body={(e: ContentTypeWithRelationStatus) => renderRelationStatus(e)}
                                    style={{minWidth: '14rem'}}/>
                            <Column header="Differences" body={(rowData: ContentTypeWithRelationStatus) => (
                                <Button
                                    label="View Differences"
                                    icon="pi pi-eye"
                                    className="p-button-text"
                                    onClick={() => openEditorDialog(null, true, rowData.content.sourceContent?.cleanData, rowData.content.targetContent?.cleanData, "View Differences")}
                                />
                            )} style={{width: '12rem'}}/>
                        </DataTable>
                    </TabPanel>
                })}


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

            {/* Manual mapper for collections */}
            {kind === StrapiContentTypeKind.CollectionType && contentData && selectedTableName && (
                <ManualCollectionMapper
                    visible={showManualMapper}
                    onHide={() => setShowManualMapper(false)}
                    mergeRequestId={mergeRequestId}
                    collectionTypesData={contentData}
                    allMergeData={allMergeData}
                    fixedTable={selectedTableName}
                    onSaved={onSaved}
                />
            )}

            {/* Modals removed to simplify workflow */}
        </div>
    );
};

export default MergeSingleTypesStep;
