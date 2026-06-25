import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { Dialog } from 'primereact/dialog';
import { SnapshotDTO, SnapshotActivityDTO } from '../types';

interface SnapshotManagerProps {
    mergeRequestId: number;
    onRestoreComplete?: () => void;
}

const fmtDate = (s: string) => new Date(s).toLocaleString();

const statusBadge = (status: string) => {
    const cls = status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'danger' : 'info';
    return <span className={`ss-badge ${cls}`}>{status.replace('_', ' ')}</span>;
};

const SnapshotManager: React.FC<SnapshotManagerProps> = ({ mergeRequestId, onRestoreComplete }) => {
    const [snapshots, setSnapshots] = useState<SnapshotDTO[]>([]);
    const [history, setHistory] = useState<SnapshotActivityDTO[]>([]);
    const [loading, setLoading] = useState(false);
    const [actionLoading, setActionLoading] = useState(false);
    const [showHistory, setShowHistory] = useState(false);

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

    useEffect(() => { fetchData(); /* eslint-disable-next-line */ }, [mergeRequestId]);

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
        if (!window.confirm('Sei sicuro di voler ripristinare questo snapshot? Tutti i dati correnti nello schema public verranno sovrascritti.')) return;
        setActionLoading(true);
        try {
            await axios.post(`/api/merge-requests/${mergeRequestId}/snapshots/restore`, { snapshotSchemaName: schemaName });
            await fetchData();
            if (onRestoreComplete) onRestoreComplete();
        } catch (error) {
            console.error('Error restoring snapshot', error);
        } finally {
            setActionLoading(false);
        }
    };

    return (
        <div className="ss-review">
            {/* Header / toolbar */}
            <div className="ss-review-toolbar">
                <i className="pi pi-database" style={{ fontSize: 13, color: 'var(--ss-text-2)' }} aria-hidden="true" />
                <span style={{ fontSize: 13, color: 'var(--ss-text)' }}>Database snapshots</span>
                {snapshots.length > 0 && <span className="ss-dim" style={{ fontSize: 11.5 }}>{snapshots.length}</span>}
                <span style={{ marginLeft: 'auto' }} />
                <button className="ss-btn subtle" onClick={fetchData} disabled={loading} title="Refresh">
                    <i className={`pi ${loading ? 'pi-spin pi-spinner' : 'pi-refresh'}`} aria-hidden="true" />
                </button>
                <button className="ss-btn subtle" onClick={() => setShowHistory(true)}>
                    <i className="pi pi-history" aria-hidden="true" /> History
                </button>
                <button className="ss-btn" onClick={takeSnapshot} disabled={actionLoading}>
                    <i className={`pi ${actionLoading ? 'pi-spin pi-spinner' : 'pi-camera'}`} aria-hidden="true" /> Take snapshot
                </button>
            </div>

            {/* Snapshot list */}
            {snapshots.length === 0 ? (
                <div className="ss-empty" style={{ padding: '28px 16px' }}>
                    <i className="pi pi-camera" aria-hidden="true" />
                    No snapshots for this merge request.<br />
                    <span className="ss-dim" style={{ fontSize: 12 }}>Take one before running the merge to be able to roll back.</span>
                </div>
            ) : snapshots.map((s) => (
                <div className="ss-erow" key={s.snapshotSchemaName}>
                    <div className="ss-erow-head" style={{ cursor: 'default' }}>
                        <i className="pi pi-camera" style={{ fontSize: 12, color: 'var(--ss-text-3)' }} aria-hidden="true" />
                        <span className="ss-erow-name">{fmtDate(s.createdAt)}</span>
                        <code className="ss-erow-summary" style={{ flex: '1 1 auto' }}>{s.snapshotSchemaName}</code>
                        <span style={{ marginLeft: 'auto' }} />
                        <button className="ss-btn" style={{ color: 'var(--ss-amber)', borderColor: 'var(--ss-amber-bg)' }}
                            onClick={() => restoreSnapshot(s.snapshotSchemaName)} disabled={actionLoading}>
                            <i className="pi pi-undo" aria-hidden="true" /> Restore
                        </button>
                    </div>
                </div>
            ))}

            {/* History dialog */}
            <Dialog header="Snapshot activity history" visible={showHistory} onHide={() => setShowHistory(false)}
                style={{ width: '50vw' }} breakpoints={{ '960px': '75vw', '641px': '100vw' }} dismissableMask>
                {history.length === 0 ? (
                    <div className="ss-dim" style={{ fontSize: 12, padding: 8 }}>No activity yet.</div>
                ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
                        {history.map((item) => (
                            <div key={item.id} style={{ border: '1px solid var(--ss-border)', borderRadius: 'var(--ss-radius-sm)', background: 'var(--ss-surface-2)', padding: '10px 12px' }}>
                                <div className="ss-card-row" style={{ justifyContent: 'space-between' }}>
                                    <span style={{ fontSize: 13, color: 'var(--ss-text)' }}>{item.activityType}</span>
                                    <span className="ss-dim" style={{ fontSize: 11 }}>{fmtDate(item.createdAt)}</span>
                                </div>
                                <div className="ss-card-row" style={{ gap: 8, marginTop: 6 }}>
                                    {statusBadge(item.status)}
                                    {item.snapshotSchemaName && <code style={{ fontSize: 11, color: 'var(--ss-text-3)' }}>{item.snapshotSchemaName}</code>}
                                </div>
                                {item.message && (
                                    <div style={{ marginTop: 8, fontSize: 12, color: 'var(--ss-red)', background: 'var(--ss-red-bg)', borderRadius: 6, padding: '6px 8px' }}>
                                        {item.message}
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                )}
            </Dialog>
        </div>
    );
};

export default SnapshotManager;
