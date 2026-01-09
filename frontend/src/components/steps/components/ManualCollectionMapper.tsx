import React, {useEffect, useMemo, useState} from 'react';
import {Dialog} from 'primereact/dialog';
import {Dropdown} from 'primereact/dropdown';
import {Button} from 'primereact/button';
import {InputText} from 'primereact/inputtext';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import axios from 'axios';
import {Badge} from 'primereact/badge';
import {TabView, TabPanel} from 'primereact/tabview';

import {
    ContentTypeComparisonResultKind,
    ContentTypeComparisonResultWithRelationships, ManualMappingsResponseDTO,
    MergeRequestData,
    StrapiContent
} from '../../../types';
import {getRepresentativeAttributes} from '../../../utils/attributeUtils';
import EditorDialog from '../../common/EditorDialog';

interface ManualCollectionMapperProps {
    visible: boolean;
    onHide: () => void;
    mergeRequestId: number;
    collectionTypesData: Record<string, ContentTypeComparisonResultWithRelationships[]>;
    allMergeData: MergeRequestData;
    onSaved?: (data?:MergeRequestData) => void | Promise<void>;
    fixedTable?: string | null; // if provided, the collection is fixed and dropdown is hidden
}

interface MappingPair {
    tableName: string;
    contentTypeUid: string;
    sourceDocumentId: string;
    targetDocumentId: string;
    sourceId:number;
    targetId:number;
    locale?: string | null;
    sourcePreview?: string;
    targetPreview?: string;
    sourceJson?: any;
    targetJson?: any;

}

const truncate = (s?: string, max = 80) => s ? (s.length > max ? s.slice(0, max) + '…' : s) : '';

const ManualCollectionMapper: React.FC<ManualCollectionMapperProps> = ({
    visible,
    onHide,
    mergeRequestId,
    collectionTypesData,
    allMergeData,
    onSaved,
    fixedTable
}) => {
    const [selectedTable, setSelectedTable] = useState<string | null>(null);
    const [sourceFilter, setSourceFilter] = useState('');
    const [targetFilter, setTargetFilter] = useState('');
    const [globalFilter, setGlobalFilter] = useState<string>('');
    const [savedGlobalFilter, setSavedGlobalFilter] = useState<string>('');

    const [selectedSource, setSelectedSource] = useState<ContentTypeComparisonResultWithRelationships | null>(null);
    const [selectedTarget, setSelectedTarget] = useState<ContentTypeComparisonResultWithRelationships | null>(null);
    const [queue, setQueue] = useState<MappingPair[]>([]);
    const [saving, setSaving] = useState(false);

    const [editorVisible, setEditorVisible] = useState(false);
    const [editorIsDiff, setEditorIsDiff] = useState(false);
    const [editorOriginal, setEditorOriginal] = useState<any>(null);
    const [editorModified, setEditorModified] = useState<any>(null);
    const [editorContent, setEditorContent] = useState<any>(null);

    const [activeTab, setActiveTab] = useState<number>(0);
    type SavedMappingDTO = { id: number; contentType: string; sourceDocumentId?: string | null; targetDocumentId?: string | null; locale?: string | null; sourceJson?: any; targetJson?: any };
    const [savedMappings, setSavedMappings] = useState<SavedMappingDTO[]>([]);
    const [loadingSaved, setLoadingSaved] = useState<boolean>(false);

    const selectedUid = useMemo(() => {
        if (!selectedTable) return null as string | null;
        if (selectedTable === 'files') return 'plugin::upload.file';
        const list = collectionTypesData[selectedTable];
        return list && list.length > 0 ? list[0].contentType : null;
    }, [selectedTable, collectionTypesData]);

    const refreshSaved = async () => {
        if (!visible || !selectedUid) return;
        try {
            setLoadingSaved(true);
            const res = await axios.get(`/api/merge-requests/${mergeRequestId}/mappings`, { params: { contentType: selectedUid } });
            setSavedMappings(res.data?.data || []);
        } catch (e) {
            console.error('Failed to load saved mappings', e);
        } finally {
            setLoadingSaved(false);
        }
    };

    useEffect(() => {
        if (visible) {
            // Reset state on open
            setSelectedSource(null);
            setSelectedTarget(null);
            setSourceFilter('');
            setTargetFilter('');
            setQueue([]);
            // Preselect table: fixedTable if provided, otherwise first available
            const initialTable = fixedTable ?? Object.keys(collectionTypesData)[0] ?? null;
            setSelectedTable(initialTable);
        }
    }, [visible, fixedTable, collectionTypesData]);

    useEffect(() => {
        refreshSaved();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selectedUid, visible]);

    const blockedSavedSrcDocs = useMemo(() => new Set((savedMappings || []).map(m => m.sourceDocumentId || '')), [savedMappings]);
    const blockedSavedTgtDocs = useMemo(() => new Set((savedMappings || []).map(m => m.targetDocumentId || '')), [savedMappings]);

    const sourceCandidates = useMemo(() => {
        if (!selectedTable) return [] as ContentTypeComparisonResultWithRelationships[];
        const blockedSrcDocs = new Set(queue.filter(q => q.tableName === selectedTable).map(q => q.sourceDocumentId));
        const items = (collectionTypesData[selectedTable] || [])
            .filter(x => x.compareState === ContentTypeComparisonResultKind.ONLY_IN_SOURCE)
            .filter(x => {
                const doc = x.sourceContent?.metadata?.documentId || '';
                return !blockedSrcDocs.has(doc) && !blockedSavedSrcDocs.has(doc);
            });
        if (!sourceFilter) return items;
        const f = sourceFilter.toLowerCase();
        return items.filter(x => {
            const attrs = getRepresentativeAttributes(x.sourceContent as StrapiContent);
            return attrs.some(a => ('' + a.value).toLowerCase().includes(f));
        });
    }, [collectionTypesData, selectedTable, sourceFilter, queue]);

    const targetCandidates = useMemo(() => {
        if (!selectedTable) return [] as ContentTypeComparisonResultWithRelationships[];
        const blockedTgtDocs = new Set(queue.filter(q => q.tableName === selectedTable).map(q => q.targetDocumentId));
        const items = (collectionTypesData[selectedTable] || [])
            .filter(x => x.compareState === ContentTypeComparisonResultKind.ONLY_IN_TARGET)
            .filter(x => {
                const doc = x.targetContent?.metadata?.documentId || '';
                return !blockedTgtDocs.has(doc) && !blockedSavedTgtDocs.has(doc);
            });
        if (!targetFilter) return items;
        const f = targetFilter.toLowerCase();
        return items.filter(x => {
            const attrs = getRepresentativeAttributes(x.targetContent as StrapiContent);
            return attrs.some(a => ('' + a.value).toLowerCase().includes(f));
        });
    }, [collectionTypesData, selectedTable, targetFilter, queue]);

    const addPair = () => {
        if (!selectedTable || !selectedSource || !selectedTarget) return;
        const srcDoc = selectedSource.sourceContent?.metadata?.documentId;
        const sourceId = selectedSource.sourceContent?.metadata?.id;
        const tgtDoc = selectedTarget.targetContent?.metadata?.documentId;
        const targetId = selectedTarget.targetContent?.metadata?.id;
        const locale = selectedSource.sourceContent?.metadata?.locale || selectedTarget.targetContent?.metadata?.locale;
        if (!srcDoc || !tgtDoc || !targetId || !sourceId ) return;
        const contentTypeUid = selectedSource.contentType || selectedTarget.contentType;
        const srcAttrs = getRepresentativeAttributes(selectedSource.sourceContent as StrapiContent)
            .map(a => `${a.key}: ${a.value}`).join(', ');
        const tgtAttrs = getRepresentativeAttributes(selectedTarget.targetContent as StrapiContent)
            .map(a => `${a.key}: ${a.value}`).join(', ');
        const entry: MappingPair = {
            tableName: selectedTable,
            contentTypeUid,
            sourceDocumentId: srcDoc,
            sourceId: sourceId,
            targetId: targetId,
            targetDocumentId: tgtDoc,
            locale: locale || undefined,
            sourcePreview: srcAttrs,
            targetPreview: tgtAttrs,
            sourceJson: selectedSource.sourceContent?.cleanData,
            targetJson: selectedTarget.targetContent?.cleanData
        };
        // Avoid duplicates
        setQueue(q => {
            if (q.some(p => p.tableName === entry.tableName && p.sourceDocumentId === entry.sourceDocumentId && p.targetDocumentId === entry.targetDocumentId)) return q;
            return [...q, entry];
        });
        // Reset selections for next pairing
        setSelectedSource(null);
        setSelectedTarget(null);
    };

    const removePair = (idx: number) => setQueue(q => q.filter((_, i) => i !== idx));

    const saveAll = async () => {
        if (queue.length === 0) return;
        try {
            setSaving(true);


            const items: {
                contentType: string;
                sourceDocumentId: string;
                targetDocumentId: string;
                sourceId:number,
                targetId:number
                locale: string | null | undefined
            }[] = queue.map(p => ({
                contentType: p.contentTypeUid === 'files' ?   'plugin::upload.file' : p.contentTypeUid,
                sourceDocumentId: p.sourceDocumentId,
                targetDocumentId: p.targetDocumentId,
                sourceId: p.sourceId,
                targetId: p.targetId,
                locale: p.locale
            }));
            const result = await axios.post<ManualMappingsResponseDTO>(`/api/merge-requests/${mergeRequestId}/mappings`, {items});

            if (onSaved) await onSaved(result.data.data);
            onHide();
        } catch (e) {
            console.error('Failed to save mappings', e);
        } finally {
            setSaving(false);
        }
    };

    const tableOptions = Object.keys(collectionTypesData).map(t => ({label: t, value: t}));

    const repAttrsBody = (row: ContentTypeComparisonResultWithRelationships) => {
        const content = row.sourceContent || row.targetContent;
        if (!content) return null;
        const attrs = getRepresentativeAttributes(content);
        return (
            <div className="text-sm">
                {attrs.slice(0, 3).map((a, i) => (
                    <div key={i}><span className="font-bold">{a.key}: </span><span>{truncate(String(a.value), 80)}</span></div>
                ))}
            </div>
        );
    };

    const jsonBtnBody = (row: ContentTypeComparisonResultWithRelationships, dir: 'source' | 'target') => (
        <Button label="View" className="p-button-text p-button-sm" icon="pi pi-eye" onClick={() => {
            setEditorIsDiff(false);
            const data = dir === 'source' ? row.sourceContent?.cleanData : row.targetContent?.cleanData;
            setEditorContent(data);
            setEditorOriginal(null);
            setEditorModified(null);
            setEditorVisible(true);
        }}/>
    );

    return (
        <Dialog header="Associazioni manuali (Collections)" visible={visible} style={{width: '90vw', maxWidth: 1200}}
                modal onHide={onHide} className="manual-mapper-dialog">
            <div className="col-12 mb-3 flex gap-2 align-items-center">
                <span className="font-bold">Collection:</span>
                {fixedTable ? (
                    <span className="text-900">{fixedTable}</span>
                ) : (
                    <Dropdown value={selectedTable} options={tableOptions} onChange={(e) => setSelectedTable(e.value)}
                              placeholder="Select a collection" className="w-20rem"/>
                )}
                <span className="text-500">Abbina record ONLY_IN_SOURCE con record ONLY_IN_TARGET.</span>
                <div className="ml-auto">
                    <span className="p-input-icon-left">
                        <i className="pi pi-search" />
                        <InputText 
                            value={activeTab === 0 ? globalFilter : savedGlobalFilter} 
                            onChange={(e) => activeTab === 0 ? setGlobalFilter(e.target.value) : setSavedGlobalFilter(e.target.value)} 
                            placeholder="Ricerca globale..." 
                        />
                    </span>
                </div>
            </div>

            <TabView activeIndex={activeTab} onTabChange={(e) => setActiveTab(e.index)}>
                <TabPanel header="Nuove associazioni">
                    <div className="grid">
                        <div className="col-12 md:col-6">
                            <div className="flex align-items-center justify-content-between mb-2">
                                <h5 className="m-0">Sorgente</h5>
                                <span className="text-500">{sourceCandidates.length} elementi</span>
                            </div>
                            <span className="p-input-icon-left w-full mb-2">
                                <i className="pi pi-search"/>
                                <InputText value={sourceFilter} onChange={e => setSourceFilter(e.target.value)} placeholder="Cerca..." className="w-full"/>
                            </span>
                            <DataTable value={sourceCandidates} selectionMode="single" selection={selectedSource}
                                       onSelectionChange={(e) => setSelectedSource(e.value as ContentTypeComparisonResultWithRelationships | null)} dataKey="id" paginator rows={5}
                                       globalFilter={globalFilter}
                                       globalFilterFields={[
                                           'sourceContent.metadata.documentId',
                                           'sourceContent.metadata.uniqueId',
                                           'sourceContent.rawData.name',
                                           'sourceContent.rawData.title',
                                           'sourceContent.rawData.filename'
                                       ]}
                                       className="text-sm">
                                <Column field="id" header="Doc ID" body={(r: any) => r.sourceContent?.metadata?.documentId}/>
                                <Column header="Preview" body={repAttrsBody}/>
                                <Column header="JSON" body={(r) => jsonBtnBody(r, 'source')} style={{width: '6rem'}}/>
                            </DataTable>
                        </div>

                        <div className="col-12 md:col-6">
                            <div className="flex align-items-center justify-content-between mb-2">
                                <h5 className="m-0">Destinazione</h5>
                                <span className="text-500">{targetCandidates.length} elementi</span>
                            </div>
                            <span className="p-input-icon-left w-full mb-2">
                                <i className="pi pi-search"/>
                                <InputText value={targetFilter} onChange={e => setTargetFilter(e.target.value)} placeholder="Cerca..." className="w-full"/>
                            </span>
                            <DataTable value={targetCandidates} selectionMode="single" selection={selectedTarget}
                                       onSelectionChange={(e) => setSelectedTarget(e.value as ContentTypeComparisonResultWithRelationships | null)} dataKey="id" paginator rows={5}
                                       globalFilter={globalFilter}
                                       globalFilterFields={[
                                           'targetContent.metadata.documentId',
                                           'targetContent.metadata.uniqueId',
                                           'targetContent.rawData.name',
                                           'targetContent.rawData.title',
                                           'targetContent.rawData.filename'
                                       ]}
                                       className="text-sm">
                                <Column field="id" header="Doc ID" body={(r: any) => r.targetContent?.metadata?.documentId}/>
                                <Column header="Preview" body={repAttrsBody}/>
                                <Column header="JSON" body={(r) => jsonBtnBody(r, 'target')} style={{width: '6rem'}}/>
                            </DataTable>
                        </div>

                        <div className="col-12 flex gap-2 justify-content-end">
                            <Button label="Aggiungi coppia" icon="pi pi-link" onClick={addPair}
                                    disabled={!selectedSource || !selectedTarget}/>
                        </div>

                        <div className="col-12">
                            <h5>Coppie da salvare</h5>
                            <DataTable value={queue} emptyMessage="Nessuna coppia selezionata" className="text-sm">
                                <Column header="#" body={(_, opt) => (opt.rowIndex + 1)} style={{width: '3rem'}}/>
                                <Column field="tableName" header="Collection"/>
                                <Column field="sourceDocumentId" header="Source Doc" body={(r: MappingPair) => (
                                    <span className="flex align-items-center gap-2"><Badge value="S" severity="info"/>{r.sourceDocumentId}</span>
                                )}/>
                                <Column field="targetDocumentId" header="Target Doc" body={(r: MappingPair) => (
                                    <span className="flex align-items-center gap-2"><Badge value="T" severity="warning"/>{r.targetDocumentId}</span>
                                )}/>
                                <Column header="Preview" body={(r: MappingPair) => (
                                    <div>
                                        <div className="text-500">{truncate(r.sourcePreview, 100)}</div>
                                        <div className="text-500">{truncate(r.targetPreview, 100)}</div>
                                    </div>
                                )}/>
                                <Column header="JSON" body={(r: MappingPair) => (
                                    <Button label="Compare" className="p-button-text p-button-sm" icon="pi pi-eye" onClick={() => {
                                        setEditorIsDiff(true);
                                        setEditorOriginal(r.sourceJson ?? null);
                                        setEditorModified(r.targetJson ?? null);
                                        setEditorVisible(true);
                                    }}/>
                                )} style={{width: '8rem'}}/>
                                <Column header="Azioni" body={(_, opt) => (
                                    <Button className="p-button-text p-button-sm" icon="pi pi-trash" onClick={() => removePair(opt.rowIndex)}/>
                                )} style={{width: '6rem'}}/>
                            </DataTable>
                        </div>

                        <div className="col-12 flex justify-content-end gap-2">
                            <Button label="Annulla" severity="secondary" onClick={onHide}/>
                            <Button label="Salva associazioni" icon="pi pi-save" onClick={saveAll} loading={saving}
                                    disabled={queue.length === 0}/>
                        </div>
                    </div>
                </TabPanel>
                <TabPanel header="Associazioni salvate">
                    <DataTable value={savedMappings} loading={loadingSaved} emptyMessage="Nessuna associazione salvata" 
                               paginator rows={5} globalFilter={savedGlobalFilter}
                               globalFilterFields={['sourceDocumentId', 'targetDocumentId']}
                               className="text-sm">
                        <Column field="sourceDocumentId" header="Source Doc" body={(r: SavedMappingDTO) => (
                            <span className="flex align-items-center gap-2"><Badge value="S" severity="info"/>{r.sourceDocumentId}</span>
                        )}/>
                        <Column field="targetDocumentId" header="Target Doc" body={(r: SavedMappingDTO) => (
                            <span className="flex align-items-center gap-2"><Badge value="T" severity="warning"/>{r.targetDocumentId}</span>
                        )}/>
                        <Column header="JSON" body={(r: SavedMappingDTO) => (
                            <Button label="Compare" className="p-button-text p-button-sm" icon="pi pi-eye" onClick={() => {
                                setEditorIsDiff(true);
                                setEditorOriginal(r.sourceJson ?? null);
                                setEditorModified(r.targetJson ?? null);
                                setEditorVisible(true);
                            }}/>
                        )} style={{width: '8rem'}}/>
                        <Column header="Azioni" body={(r: SavedMappingDTO) => (
                            <Button className="p-button-text p-button-sm" icon="pi pi-trash" onClick={async () => {
                                if (!window.confirm('Eliminare questa relazione?')) return;
                                try {
                                    const res = await axios.delete<ManualMappingsResponseDTO>(`/api/merge-requests/${mergeRequestId}/mappings/${r.id}`);
                                    await refreshSaved();
                                    if (onSaved) await onSaved(res.data.data);
                                } catch (e) {
                                    console.error('Failed to delete mapping', e);
                                }
                            }}/>
                        )} style={{width: '6rem'}}/>
                    </DataTable>
                </TabPanel>
            </TabView>

            <EditorDialog visible={editorVisible} onHide={() => setEditorVisible(false)} header={"View Content"}
                          isDiff={editorIsDiff} originalContent={editorOriginal} modifiedContent={editorModified}
                          content={editorContent}/>
        </Dialog>
    );
};

export default ManualCollectionMapper;
