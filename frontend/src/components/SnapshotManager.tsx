import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Button } from 'primereact/button';
import { Card } from 'primereact/card';
import { Tag } from 'primereact/tag';
import { Timeline } from 'primereact/timeline';
import { Dialog } from 'primereact/dialog';
import { SnapshotDTO, SnapshotActivityDTO } from '../types';

interface SnapshotManagerProps {
    mergeRequestId: number;
    onRestoreComplete?: () => void;
}

const SnapshotManager: React.FC<SnapshotManagerProps> = ({ mergeRequestId, onRestoreComplete }) => {
    const [snapshots, setSnapshots] = useState<SnapshotDTO[]>([]);
    const [history, setHistory] = useState<SnapshotActivityDTO[]>([]);
    const [loading, setLoading] = useState<boolean>(false);
    const [actionLoading, setActionLoading] = useState<boolean>(false);
    const [showHistory, setShowHistory] = useState<boolean>(false);

    const fetchData = async () => {
        setLoading(true);
        try {
            const [snapsRes, histRes] = await Promise.all([
                axios.get(`/api/merge-requests/${mergeRequestId}/snapshots`),
                axios.get(`/api/merge-requests/${mergeRequestId}/snapshots/history`)
            ]);
            setSnapshots(snapsRes.data);
            setHistory(histRes.data);
        } catch (error) {
            console.error('Error fetching snapshots data', error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
    }, [mergeRequestId]);

    const takeSnapshot = async () => {
        setActionLoading(true);
        try {
            await axios.post(`/api/merge-requests/${mergeRequestId}/snapshots/take`);
            await fetchData();
        } catch (error) {
            console.error('Error taking snapshot', error);
        } finally {
            setActionLoading(false);
        }
    };

    const restoreSnapshot = async (schemaName?: string) => {
        if (!window.confirm('Sei sicuro di voler ripristinare questo snapshot? Tutti i dati correnti nello schema public verranno sovrascritti.')) {
            return;
        }

        setActionLoading(true);
        try {
            await axios.post(`/api/merge-requests/${mergeRequestId}/snapshots/restore`, {
                snapshotSchemaName: schemaName
            });
            await fetchData();
            if (onRestoreComplete) onRestoreComplete();
        } catch (error) {
            console.error('Error restoring snapshot', error);
        } finally {
            setActionLoading(false);
        }
    };

    const getStatusTag = (status: string) => {
        switch (status) {
            case 'SUCCESS': return <Tag severity="success" value="SUCCESS" />;
            case 'FAILED': return <Tag severity="danger" value="FAILED" />;
            case 'IN_PROGRESS': return <Tag severity="info" value="IN PROGRESS" />;
            default: return <Tag value={status} />;
        }
    };

    const historyContent = (item: SnapshotActivityDTO) => {
        return (
            <div className="flex flex-column gap-2 mb-4 p-3 border-round surface-card shadow-1">
                <div className="flex justify-content-between align-items-center">
                    <span className="font-bold text-lg">{item.activityType}</span>
                    <small className="text-500">{new Date(item.createdAt).toLocaleString()}</small>
                </div>
                <div className="flex align-items-center gap-2">
                    {getStatusTag(item.status)}
                    {item.snapshotSchemaName && <code className="text-xs">{item.snapshotSchemaName}</code>}
                </div>
                {item.message && (
                    <div className="mt-2 p-2 bg-red-50 text-red-600 border-round text-sm">
                        {item.message}
                    </div>
                )}
            </div>
        );
    };

    return (
        <div className="snapshot-manager mt-4">
            <Card title="Database Snapshots" className="shadow-2">
                <div className="flex justify-content-between mb-3">
                    <div className="flex gap-2">
                        <Button 
                            label="Crea Snapshot" 
                            icon="pi pi-camera" 
                            onClick={takeSnapshot} 
                            loading={actionLoading}
                            className="p-button-outlined"
                        />
                        <Button 
                            label="Vedi Storico" 
                            icon="pi pi-history" 
                            onClick={() => setShowHistory(true)} 
                            className="p-button-outlined p-button-secondary"
                        />
                    </div>
                    <Button 
                        icon="pi pi-refresh" 
                        onClick={fetchData} 
                        loading={loading}
                        className="p-button-text p-button-rounded"
                    />
                </div>

                <DataTable value={snapshots} emptyMessage="Nessuno snapshot trovato per questa Merge Request." loading={loading} responsiveLayout="scroll">
                    <Column field="createdAt" header="Data Creazione" body={(rowData) => new Date(rowData.createdAt).toLocaleString()} sortable />
                    <Column field="snapshotSchemaName" header="Nome Schema" body={(rowData) => <code>{rowData.snapshotSchemaName}</code>} />
                    <Column header="Azioni" body={(rowData) => (
                        <Button 
                            label="Ripristina" 
                            icon="pi pi-undo" 
                            severity="warning"
                            size="small"
                            onClick={() => restoreSnapshot(rowData.snapshotSchemaName)}
                            loading={actionLoading}
                        />
                    )} />
                </DataTable>
            </Card>

            <Dialog 
                header="Storico Attività Snapshot" 
                visible={showHistory} 
                onHide={() => setShowHistory(false)}
                style={{ width: '50vw' }}
                breakpoints={{ '960px': '75vw', '641px': '100vw' }}
            >
                <div className="mt-3">
                    <Timeline value={history} content={historyContent} />
                </div>
            </Dialog>
        </div>
    );
};

export default SnapshotManager;
