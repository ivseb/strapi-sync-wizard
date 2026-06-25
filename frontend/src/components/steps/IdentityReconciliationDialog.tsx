import React, {useCallback, useEffect, useRef, useState} from 'react';
import {mergeRequestsApi} from '../../api/mergeRequests';
import {apiErrorMessage} from '../../api/http';
import {Dialog} from 'primereact/dialog';
import {DataTable} from 'primereact/datatable';
import {Column} from 'primereact/column';
import {Button} from 'primereact/button';
import {Tag} from 'primereact/tag';
import {Toast} from 'primereact/toast';
import {ProgressSpinner} from 'primereact/progressspinner';
import {IdentityReconciliationAction, IdentityReconciliationReport, ReconciliationActionKind} from '../../types';

interface Props {
    mergeRequestId: number;
    visible: boolean;
    onHide: () => void;
}

const KIND_SEVERITY: Record<ReconciliationActionKind, 'success' | 'info' | 'warning' | 'danger'> = {
    ALREADY_LINKED: 'success',
    LINK_TARGET_TO_SOURCE: 'info',
    LINK_SOURCE_TO_TARGET: 'info',
    ASSIGN_NEW_BOTH: 'warning',
    CONFLICT_PREFER_SOURCE: 'danger',
};

const KIND_LABEL: Record<ReconciliationActionKind, string> = {
    ALREADY_LINKED: 'Already linked',
    LINK_TARGET_TO_SOURCE: 'Link target → source id',
    LINK_SOURCE_TO_TARGET: 'Link source → target id',
    ASSIGN_NEW_BOTH: 'New shared id (both)',
    CONFLICT_PREFER_SOURCE: 'Conflict (source wins)',
};

/**
 * Identity reconciliation (Phase 1): links a shared sync_id across the two instances for matched
 * content pairs. Opens in dry-run (apply=false) so the user reviews the proposed actions before
 * committing with "Apply".
 */
const IdentityReconciliationDialog: React.FC<Props> = ({mergeRequestId, visible, onHide}) => {
    const toast = useRef<Toast>(null);
    const [loading, setLoading] = useState(false);
    const [applying, setApplying] = useState(false);
    const [report, setReport] = useState<IdentityReconciliationReport | null>(null);

    const run = useCallback(async (apply: boolean) => {
        if (apply) setApplying(true); else setLoading(true);
        try {
            const data = await mergeRequestsApi.reconcileIdentity(mergeRequestId, apply);
            setReport(data);
            if (apply) {
                toast.current?.show({
                    severity: 'success',
                    summary: 'Identity reconciled',
                    detail: `${data.linked + data.assignedNew} pairs linked`,
                    life: 4000,
                });
            }
        } catch (e) {
            toast.current?.show({severity: 'error', summary: 'Reconciliation failed', detail: apiErrorMessage(e), life: 5000});
        } finally {
            if (apply) setApplying(false); else setLoading(false);
        }
    }, [mergeRequestId]);

    useEffect(() => {
        if (visible) {
            setReport(null);
            run(false); // dry-run on open
        }
    }, [visible, run]);

    const kindTemplate = (row: IdentityReconciliationAction) => (
        <Tag value={KIND_LABEL[row.kind]} severity={KIND_SEVERITY[row.kind]}/>
    );

    const header = (
        <div className="flex align-items-center gap-2">
            <i className="pi pi-id-card"/>
            <span>Identity reconciliation</span>
        </div>
    );

    const footer = (
        <div className="flex justify-content-between align-items-center w-full">
            <Button label="Re-run dry-run" icon="pi pi-refresh" className="p-button-text"
                    onClick={() => run(false)} disabled={loading || applying}/>
            <div className="flex gap-2">
                <Button label="Close" className="p-button-text" onClick={onHide} disabled={applying}/>
                <Button label="Apply" icon="pi pi-check" severity="success"
                        loading={applying}
                        disabled={loading || !report || (report.linked + report.assignedNew + report.conflicts) === 0}
                        onClick={() => run(true)}/>
            </div>
        </div>
    );

    const pending = report ? report.linked + report.assignedNew + report.conflicts : 0;

    return (
        <Dialog header={header} visible={visible} style={{width: '70vw'}} onHide={onHide} footer={footer} maximizable>
            <Toast ref={toast}/>
            <p className="text-sm text-color-secondary mt-0">
                Pairs matched between the two instances get a shared <code>sync_id</code>. This is a
                bootstrap/maintenance step; future comparisons then match by exact identity instead of heuristics.
                On a conflict (both sides already have a different id) the <strong>source</strong> id wins.
            </p>

            {loading && (
                <div className="flex justify-content-center my-4"><ProgressSpinner style={{width: 40, height: 40}}/></div>
            )}

            {report && !loading && (
                <>
                    <div className="flex flex-wrap gap-2 mb-3">
                        <Tag value={`Total pairs: ${report.totalPairs}`}/>
                        <Tag value={`Already linked: ${report.alreadyLinked}`} severity="success"/>
                        <Tag value={`To link: ${report.linked}`} severity="info"/>
                        <Tag value={`New ids: ${report.assignedNew}`} severity="warning"/>
                        <Tag value={`Conflicts: ${report.conflicts}`} severity="danger"/>
                        <Tag value={report.applied ? 'APPLIED' : 'DRY-RUN'} severity={report.applied ? 'success' : 'info'}/>
                    </div>

                    {report.applied ? (
                        <p className="text-sm">Sidecars updated. {pending === 0 ? 'Everything was already linked.' : ''}</p>
                    ) : (
                        <p className="text-sm">{pending} pair(s) would be changed if you press Apply.</p>
                    )}

                    <DataTable value={report.actions} paginator rows={10} responsiveLayout="scroll"
                               emptyMessage="No matched pairs found." size="small">
                        <Column header="Action" body={kindTemplate} style={{width: '14rem'}}/>
                        <Column field="contentType" header="Content type"/>
                        <Column field="sourceDocumentId" header="Source documentId"/>
                        <Column field="targetDocumentId" header="Target documentId"/>
                        <Column field="locale" header="Locale" body={(r: IdentityReconciliationAction) => r.locale || '-'}
                                style={{width: '7rem'}}/>
                    </DataTable>
                </>
            )}
        </Dialog>
    );
};

export default IdentityReconciliationDialog;
