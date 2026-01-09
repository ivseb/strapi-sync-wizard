import React, {useEffect, useState} from 'react';
import {Dialog} from 'primereact/dialog';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {Button} from 'primereact/button';
import {Tag} from 'primereact/tag';
import axios from 'axios';
import {Message} from 'primereact/message';

interface MergeRequestExclusion {
    id: number;
    contentType: string;
    documentId?: string | null;
    fieldPath?: string | null;
    createdAt: string;
}

interface ExclusionsManagerProps {
    visible: boolean;
    onHide: () => void;
    mergeRequestId: number;
    onExclusionsChanged?: () => void;
}

const ExclusionsManager: React.FC<ExclusionsManagerProps> = ({
    visible,
    onHide,
    mergeRequestId,
    onExclusionsChanged
}) => {
    const [exclusions, setExclusions] = useState<MergeRequestExclusion[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const fetchExclusions = async () => {
        setLoading(true);
        try {
            const res = await axios.get(`/api/merge-requests/${mergeRequestId}/exclusions`);
            if (res.data.success) {
                setExclusions(res.data.data);
            }
        } catch (e: any) {
            setError(e.message || 'Errore nel caricamento delle esclusioni');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (visible) {
            fetchExclusions();
        }
    }, [visible, mergeRequestId]);

    const deleteExclusion = async (id: number) => {
        try {
            await axios.delete(`/api/merge-requests/${mergeRequestId}/exclusions/${id}`);
            await fetchExclusions();
            if (onExclusionsChanged) onExclusionsChanged();
        } catch (e: any) {
            setError(e.message || "Errore durante l'eliminazione dell'esclusione");
        }
    };

    const typeTemplate = (rowData: MergeRequestExclusion) => {
        if (rowData.fieldPath) return <Tag value="Campo" severity="warning" />;
        if (rowData.documentId) return <Tag value="Entità" severity="info" />;
        return <Tag value="Content Type" severity="success" />;
    };

    return (
        <Dialog 
            header="Gestione Vincoli di Sincronizzazione (Esclusioni)" 
            visible={visible} 
            style={{width: '70vw'}} 
            onHide={onHide}
            modal
        >
            <div className="flex flex-column gap-3">
                <p>Queste entità o campi non verranno mai sovrascritti o sincronizzati tra gli ambienti.</p>
                
                {error && <Message severity="error" text={error} className="mb-3" />}

                <DataTable 
                    value={exclusions} 
                    loading={loading} 
                    emptyMessage="Nessun vincolo configurato."
                    responsiveLayout="scroll"
                >
                    <Column header="Tipo" body={typeTemplate} style={{width: '10rem'}} />
                    <Column field="contentType" header="Content Type" />
                    <Column field="documentId" header="Document ID / Entità" body={r => r.documentId || '-'} />
                    <Column field="fieldPath" header="Campo / Path" body={r => r.fieldPath || '-'} />
                    <Column 
                        header="Azioni" 
                        body={(rowData: MergeRequestExclusion) => (
                            <Button 
                                icon="pi pi-trash" 
                                className="p-button-text p-button-danger" 
                                onClick={() => deleteExclusion(rowData.id)}
                                tooltip="Rimuovi vincolo"
                            />
                        )}
                        style={{width: '5rem'}}
                    />
                </DataTable>
            </div>
        </Dialog>
    );
};

export default ExclusionsManager;
