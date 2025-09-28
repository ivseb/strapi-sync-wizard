import React from 'react';
import {Card} from 'primereact/card';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {Badge} from 'primereact/badge';
import {
    ContentTypeComparisonResultKind,
    ContentTypeComparisonResultWithRelationships,
    MergeRequestSelectionDTO
} from "../../../types";

interface Props {

    collectionTypes: Record<string, ContentTypeComparisonResultWithRelationships[]>;
    activeContentType: string | null;
    onActiveContentTypeChange: (contentType: string | null) => void;
    selections: MergeRequestSelectionDTO[];
}


interface SummaryUtils {
    contentType: string;
    onlyInSourceSelected: number;
    onlyInSourceCount: number;
    onlyInTargetSelected: number;
    onlyInTargetCount: number;
    differenceSelected: number;
    differenceCount: number;
    identical: number;
}

const ContentTypesSummaryTable: React.FC<Props> = ({
                                                       collectionTypes,
                                                       activeContentType,
                                                       onActiveContentTypeChange,
                                                       selections,
                                                   }) => {


    const state:SummaryUtils[] = Object.keys(collectionTypes).map(contentType => {
        const items = collectionTypes[contentType];

        if (items.length === 0) {
            return {
                contentType,
                onlyInSourceSelected: 0,
                onlyInSourceCount: 0,
                onlyInTargetSelected: 0,
                onlyInTargetCount: 0,
                differenceSelected: 0,
                differenceCount: 0,
                identical: 0
            }
        }
        const tableSelections = selections.find(x => x.tableName === items[0].tableName)?.selections;
        const onlyInSource = items.filter(i => i.compareState === ContentTypeComparisonResultKind.ONLY_IN_SOURCE).map(i => i.id);
        const onlyInSourceSelectedCount = tableSelections?.filter(x => {
            return onlyInSource.includes(x.documentId)
        }).length || 0
        const onlyInTarget = items.filter(i => i.compareState === ContentTypeComparisonResultKind.ONLY_IN_TARGET).map(i => i.id);
        const onlyInTargetCount = tableSelections?.filter(x => {
           return  onlyInTarget.includes(x.documentId)
        }).length || 0
        const difference = items.filter(i => i.compareState === ContentTypeComparisonResultKind.DIFFERENT).map(i => i.id);
        const differenceSelectionCount = tableSelections?.filter(x => {
            return difference.includes(x.documentId)
        }).length || 0
        const identical = items.filter(i => i.compareState === ContentTypeComparisonResultKind.IDENTICAL).map(i => i.id);


        return {
            contentType,
            onlyInSourceSelected: onlyInSourceSelectedCount,
            onlyInSourceCount: onlyInSource.length,
            onlyInTargetSelected: onlyInTargetCount,
            onlyInTargetCount: onlyInTarget.length,
            differenceSelected: differenceSelectionCount,
            differenceCount: difference.length,
            identical: identical.length
        } as SummaryUtils;
    })

    return (
        <div className="mb-4">
            <Card>
                <h4>Content Types</h4>
                <DataTable
                    value={state}
                    selectionMode="single"
                    selection={activeContentType? state.find(x => x.contentType === activeContentType) : null}
                    onSelectionChange={(e) => onActiveContentTypeChange(e.value?.contentType || null)}
                    dataKey="contentType"
                    className="mb-3"
                    paginator
                    rows={5}
                    rowsPerPageOptions={[5, 10, 25, 50]}
                >
                    <Column field="contentType" header="Content Type" sortable/>
                    <Column
                        header="Only in Source"
                        body={(rowData: SummaryUtils) => {


                            return (
                                <Badge
                                    value={`${rowData.onlyInSourceSelected}/${rowData.onlyInSourceCount}`}
                                    severity={rowData.onlyInSourceCount > 0 ? 'warning' : 'success'}
                                />
                            );
                        }}
                    />
                    <Column
                        header="Only in Target"
                        body={(rowData: SummaryUtils) => {
                            return (
                                <Badge
                                    value={`${rowData.onlyInTargetSelected}/${rowData.onlyInTargetCount}`}
                                    severity={rowData.onlyInTargetCount > 0 ? 'warning' : 'success'}
                                />
                            );
                        }}
                    />
                    <Column
                        header="Different"
                        body={(rowData: SummaryUtils) => {
                            return (
                                <Badge
                                    value={`${rowData.differenceSelected}/${rowData.differenceCount}`}
                                    severity={rowData.differenceCount > 0 ? 'danger' : 'success'}
                                />
                            );
                        }}
                    />
                    <Column header="Identical"
                            body={(rowData: SummaryUtils) => <Badge value={rowData.identical} severity="success"/>}/>
                </DataTable>
            </Card>
        </div>
    );
};

export default ContentTypesSummaryTable;
