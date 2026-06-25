import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Toast } from 'primereact/toast';
import { MergeRequestSelectionDTO, MergeRequestData, MergeRequestSelection, StrapiContent, SyncPlanDTO } from '../../types';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import EditorDialog from '../common/EditorDialog';
import SyncPlanGraph from './components/SyncPlanGraph';

// Interface for sync progress updates from the WebSocket
interface SyncProgressUpdate {
    mergeRequestId: number;
    totalItems: number;
    processedItems: number;
    currentItem: string;
    currentItemType: string;
    currentOperation: string;
    status: string;
    message?: string;
}

interface CompleteMergeStepProps {
    status: string;
    completing: boolean;
    completeMerge: (opts?: { onlyFailed?: boolean; rollbackOnFailure?: boolean }) => void;
    selections?: MergeRequestSelectionDTO[];
    allMergeData?: MergeRequestData;
}

type Operation = 'create' | 'update' | 'delete';
type EffStatus = 'success' | 'failed' | 'processing' | 'pending';

interface ReviewItem {
    contentType: string;
    documentId: string;
    operation: Operation;
    statusInfo?: MergeRequestSelection;
}

const shortType = (ct: string) => (ct === 'files' ? 'files' : ct.substring(ct.lastIndexOf('.') + 1));

// Recursive search for a readable label inside a (possibly relation-/component-only) entry,
// mirroring the backend's contentLabelOf: prefer fields whose name hints at a title, then any
// short text. Returns null when nothing usable is found (caller falls back to the documentId).
const LABEL_HINTS = ['title', 'name', 'label', 'heading', 'subject', 'slug', 'code', 'key', 'question', 'testo', 'descrizione', 'description'];
const LABEL_TECH = new Set(['id', 'documentId', 'document_id', '__sync_id', '__component', '__links', '__order',
    'createdAt', 'updatedAt', 'publishedAt', 'created_at', 'updated_at', 'published_at', 'locale',
    'hash', 'ext', 'mime', 'provider', 'url', 'previewUrl']);
const isShortText = (v: any): v is string => typeof v === 'string' && v.trim().length >= 1 && v.trim().length <= 80;

const deepLabel = (obj: any, hintedOnly = true, depth = 0): string | null => {
    if (obj == null || depth > 5) return null;
    if (Array.isArray(obj)) {
        for (const v of obj) { const r = deepLabel(v, hintedOnly, depth + 1); if (r) return r; }
        return null;
    }
    if (typeof obj === 'object') {
        // own scalar string fields first
        for (const [k, v] of Object.entries(obj)) {
            if (LABEL_TECH.has(k)) continue;
            if (isShortText(v)) {
                const hinted = LABEL_HINTS.some((h) => k.toLowerCase().includes(h));
                if (!hintedOnly || hinted) return (v as string).trim();
            }
        }
        // then descend into nested objects/arrays
        for (const [k, v] of Object.entries(obj)) {
            if (LABEL_TECH.has(k)) continue;
            if (v && typeof v === 'object') { const r = deepLabel(v, hintedOnly, depth + 1); if (r) return r; }
        }
        // top-level call: retry without the hint requirement before giving up
        if (hintedOnly && depth === 0) return deepLabel(obj, false, 0);
    }
    return null;
};

const CompleteMergeStep: React.FC<CompleteMergeStepProps> = ({
                                                                 status,
                                                                 completing,
                                                                 completeMerge,
                                                                 selections = [],
                                                                 allMergeData
                                                             }) => {
    // ---- counts -------------------------------------------------------------
    const totalToCreate = selections.reduce((s, dto) => s + ((dto.selections || []).filter(x => x.direction === 'TO_CREATE').length), 0);
    const totalToUpdate = selections.reduce((s, dto) => s + ((dto.selections || []).filter(x => x.direction === 'TO_UPDATE').length), 0);
    const totalToDelete = selections.reduce((s, dto) => s + ((dto.selections || []).filter(x => x.direction === 'TO_DELETE').length), 0);
    const totalItems = totalToCreate + totalToUpdate + totalToDelete;

    // ---- editor / logs dialog ----------------------------------------------
    const [editorDialogVisible, setEditorDialogVisible] = useState(false);
    const [editorContent, setEditorContent] = useState<any>(null);
    const [isDiffEditor, setIsDiffEditor] = useState(false);
    const [originalContent, setOriginalContent] = useState<any>(null);
    const [modifiedContent, setModifiedContent] = useState<any>(null);
    const [editorDialogHeader, setEditorDialogHeader] = useState('View Content');
    const [editorErrorMessage, setEditorErrorMessage] = useState<string | undefined>(undefined);
    const [httpLogs, setHttpLogs] = useState<{ fileName: string, content: string, timestamp: string }[] | undefined>(undefined);

    // ---- sync progress ------------------------------------------------------
    const [syncProgress, setSyncProgress] = useState<SyncProgressUpdate | null>(null);
    const [syncInProgress, setSyncInProgress] = useState(false);
    const [syncCompleted, setSyncCompleted] = useState(false);
    const [syncFailed, setSyncFailed] = useState(false);
    const [syncItemsStatus, setSyncItemsStatus] = useState<Record<string, { status: string, message?: string }>>({});
    const [rollbackOnFailure, setRollbackOnFailure] = useState(false);
    const [verifying, setVerifying] = useState(false);
    const [verifyReport, setVerifyReport] = useState<{ total: number; consistent: number; schemaGap: number; inconsistent: number; items: Array<{ contentType: string; documentId: string; direction: string; actual: string; severity: string; reason?: string | null }> } | null>(null);
    const eventSourceRef = useRef<EventSource | null>(null);
    const toast = useRef<Toast>(null);

    // ---- sync plan ----------------------------------------------------------
    const [syncPlan, setSyncPlan] = useState<SyncPlanDTO | null>(null);
    const [planLoading, setPlanLoading] = useState(false);
    const [planError, setPlanError] = useState<string | null>(null);
    const [showPlan, setShowPlan] = useState(false);

    // ---- view filters -------------------------------------------------------
    const [query, setQuery] = useState('');
    const [opFilter, setOpFilter] = useState<Set<Operation>>(new Set(['create', 'update', 'delete']));
    const [statusFilter, setStatusFilter] = useState<'all' | EffStatus>('all');
    const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

    const loadPlan = () => {
        const parts = window.location.pathname.split('/');
        const mergeRequestId = parseInt(parts[parts.length - 1], 10);
        if (isNaN(mergeRequestId)) return;
        setPlanLoading(true);
        setPlanError(null);
        fetch(`/api/merge-requests/${mergeRequestId}/sync-plan`)
            .then(res => { if (!res.ok) throw new Error(`Failed to load sync plan (HTTP ${res.status})`); return res.json(); })
            .then((data: SyncPlanDTO) => setSyncPlan(data))
            .catch(err => setPlanError(err.message || 'Failed to load sync plan'))
            .finally(() => setPlanLoading(false));
    };

    useEffect(() => { loadPlan(); /* eslint-disable-next-line */ }, [selections?.length]);

    // Align initial node statuses with existing selection state
    useEffect(() => {
        if (!syncPlan || syncInProgress) return;
        const planKeys = new Set<string>();
        (syncPlan.batches || []).forEach(b => b.forEach(it => planKeys.add(`${it.tableName}:${it.documentId}`)));
        const initial: Record<string, { status: string; message?: string }> = {};
        (selections || []).forEach(dto => (dto.selections || []).forEach(sel => {
            const key = `${dto.tableName}:${sel.documentId}`;
            if (!planKeys.has(key)) return;
            if (sel.syncSuccess === true) initial[key] = { status: 'SUCCESS' };
            else if (sel.syncSuccess === false) initial[key] = { status: 'ERROR', message: sel.syncFailureResponse || undefined };
        }));
        setSyncItemsStatus(prev => ({ ...initial, ...prev }));
    }, [syncPlan, selections, syncInProgress]);

    useEffect(() => () => { if (eventSourceRef.current) { eventSourceRef.current.close(); eventSourceRef.current = null; } }, []);

    const applyUpdate = (update: SyncProgressUpdate, eventSource: EventSource) => {
        setSyncProgress(update);
        const reserved = ['Starting synchronization', 'Processing content types', 'Content types processed', 'Synchronization completed', 'Synchronization failed'];
        if (update.currentItem && !reserved.includes(update.currentItem)) {
            const parts = update.currentItem.split(':');
            const key = parts.length >= 2 ? `${parts[0]}:${parts.slice(1).join(':')}` : update.currentItem;
            setSyncItemsStatus(prev => ({ ...prev, [key]: { status: update.status, message: update.message } }));
        }
        if (update.status === 'SUCCESS' && update.currentOperation === 'COMPLETED') {
            setSyncInProgress(false); setSyncCompleted(true); eventSource.close();
        } else if (update.status === 'ERROR') {
            setSyncInProgress(false); setSyncFailed(true); eventSource.close();
        }
    };

    const startSyncProgressSSE = (mergeRequestId: number) => {
        if (eventSourceRef.current) { eventSourceRef.current.close(); eventSourceRef.current = null; }
        setSyncProgress(null);
        setSyncInProgress(true);
        setSyncCompleted(false);
        setSyncFailed(false);
        setSyncItemsStatus({});

        // Relative URL on purpose: in dev the Vite proxy forwards /api to the backend, in prod the
        // backend serves the frontend. A hardcoded host:port here bypassed the proxy and silently
        // broke all live progress (wrong port → EventSource never connected).
        const eventSource = new EventSource(`/api/sync-progress/${mergeRequestId}`);

        eventSource.onmessage = (event) => {
            if (!event.data || event.data === 'connected' || event.data === 'heartbeat') return;
            try { applyUpdate(JSON.parse(event.data) as SyncProgressUpdate, eventSource); }
            catch (e) { console.error('Error parsing SSE message:', e); }
        };
        eventSource.addEventListener('progress', (event: MessageEvent) => {
            try { applyUpdate(JSON.parse(event.data) as SyncProgressUpdate, eventSource); }
            catch (e) { console.error('Error parsing SSE message:', e); }
        });
        eventSource.onerror = () => { setSyncInProgress(false); setSyncFailed(true); eventSource.close(); };
        eventSourceRef.current = eventSource;
    };

    const verifyDestination = () => {
        const parts = window.location.pathname.split('/');
        const mergeRequestId = parseInt(parts[parts.length - 1], 10);
        if (isNaN(mergeRequestId)) return;
        setVerifying(true);
        setVerifyReport(null);
        fetch(`/api/merge-requests/${mergeRequestId}/verify`, { method: 'POST' })
            .then(res => res.json())
            .then((r) => {
                setVerifyReport(r);
                const sev = r.inconsistent > 0 ? 'warn' : 'success';
                toast.current?.show({
                    severity: sev,
                    summary: r.inconsistent > 0 ? `${r.inconsistent} real mismatch(es)` : 'Destination consistent',
                    detail: `${r.consistent} ok · ${r.schemaGap} schema-gap · ${r.inconsistent} mismatch`,
                    life: 6000,
                });
            })
            .catch(() => toast.current?.show({ severity: 'error', summary: 'Verification failed', life: 5000 }))
            .finally(() => setVerifying(false));
    };

    const handleCompleteMerge = (opts?: { onlyFailed?: boolean }) => {
        const parts = window.location.pathname.split('/');
        const mergeRequestId = parseInt(parts[parts.length - 1], 10);
        if (!isNaN(mergeRequestId)) { startSyncProgressSSE(mergeRequestId); completeMerge({ ...opts, rollbackOnFailure }); }
        else toast.current?.show({ severity: 'error', summary: 'Error', detail: 'Could not determine merge request ID.', life: 5000 });
    };

    // ---- entry lookups (unchanged logic) -----------------------------------
    const findEntry = (contentType: string, documentId: string): any => {
        if (!allMergeData) return null;
        if (contentType === 'files') {
            const s = allMergeData.files.find(f => f.compareState === 'ONLY_IN_SOURCE' && f.sourceImage?.metadata.documentId === documentId)?.sourceImage;
            if (s) return s;
            const d = allMergeData.files.find(f => f.compareState === 'DIFFERENT' && f.sourceImage?.metadata.documentId === documentId);
            if (d?.sourceImage) return d.sourceImage;
            const t = allMergeData.files.find(f => f.compareState === 'ONLY_IN_TARGET' && f.targetImage?.metadata.documentId === documentId)?.targetImage;
            if (t) return t;
            return null;
        }
        if (allMergeData.singleTypes[contentType]) {
            const st = allMergeData.singleTypes[contentType];
            if (st.sourceContent && st.sourceContent.metadata.documentId === documentId) return st.sourceContent.cleanData;
            if (st.targetContent && st.targetContent.metadata.documentId === documentId) return st.targetContent.cleanData;
            return null;
        }
        if (allMergeData.collectionTypes[contentType]) {
            const entries = allMergeData.collectionTypes[contentType];
            const src = entries.find(e => e.compareState === 'ONLY_IN_SOURCE' && e.sourceContent?.metadata.documentId === documentId)?.sourceContent;
            if (src) return src.cleanData;
            const diff = entries.find(e => e.compareState === 'DIFFERENT' && e.sourceContent?.metadata.documentId === documentId)?.sourceContent;
            if (diff) return diff.cleanData;
            const tgt = entries.find(e => e.compareState === 'ONLY_IN_TARGET' && e.targetContent?.metadata.documentId === documentId)?.targetContent;
            if (tgt) return tgt.cleanData;
            const ident = entries.find(e => e.compareState === 'IDENTICAL' && ((e.sourceContent && e.sourceContent.metadata.documentId === documentId) || (e.targetContent && e.targetContent.metadata.documentId === documentId)));
            if (ident) return ident.sourceContent?.cleanData ?? ident.targetContent?.cleanData;
            return null;
        }
        return null;
    };

    const isUpdateEntry = (contentType: string, documentId: string): { isUpdate: boolean, source: any, target: any } => {
        if (!allMergeData) return { isUpdate: false, source: null, target: null };
        if (contentType === 'files') {
            const d = allMergeData.files.find(f => f.compareState === 'DIFFERENT' && f.sourceImage?.metadata.documentId === documentId);
            if (d && d.sourceImage && d.targetImage) return { isUpdate: true, source: d.sourceImage, target: d.targetImage };
        }
        if (allMergeData.singleTypes[contentType]) {
            const st = allMergeData.singleTypes[contentType];
            if (st.compareState === 'DIFFERENT' && st.sourceContent && st.targetContent && st.sourceContent.metadata.documentId === documentId)
                return { isUpdate: true, source: st.sourceContent.cleanData, target: st.targetContent.cleanData };
        }
        if (allMergeData.collectionTypes[contentType]) {
            const d = allMergeData.collectionTypes[contentType].find(e => e.compareState === 'DIFFERENT' && e.sourceContent?.metadata.documentId === documentId);
            if (d && d.sourceContent && d.targetContent) return { isUpdate: true, source: d.sourceContent.cleanData, target: d.targetContent.cleanData };
        }
        return { isUpdate: false, source: null, target: null };
    };

    // Single-line human label for an item.
    const itemLabel = (contentType: string, documentId: string): string => {
        const entry = findEntry(contentType, documentId);
        if (!entry) return documentId;
        if (contentType === 'files') {
            const meta: any = entry.metadata;
            return (meta && 'name' in meta && meta.name) ? meta.name : documentId;
        }
        const attrs = getRepresentativeAttributes(entry as StrapiContent);
        if (attrs.length > 0) {
            const v = attrs[0].value;
            if (v != null && v !== '') return String(v);
        }
        // Fallback: the representative attributes only look at top-level primitives; many entries
        // (relation-/component-only) have their text nested. Search recursively for a readable label.
        const deep = deepLabel(entry);
        return deep || documentId;
    };

    const fileThumbUrl = (contentType: string, documentId: string): string | null => {
        if (contentType !== 'files') return null;
        const entry: any = findEntry(contentType, documentId);
        const meta = entry?.metadata;
        return meta && 'url' in meta && /^image\//.test(meta.mime || '') ? meta.url : null;
    };

    const openEditorDialog = (content: any, isDiff: boolean, source: any, target: any, header: string, tableName?: string, documentId?: string) => {
        const parts = window.location.pathname.split('/');
        const mergeRequestId = parseInt(parts[parts.length - 1], 10);
        if (!isNaN(mergeRequestId) && documentId) {
            fetch(`/api/merge-requests/${mergeRequestId}/logs?identifier=${documentId}`)
                .then(res => res.json()).then(d => setHttpLogs(d)).catch(err => console.error('Error fetching logs', err));
        } else setHttpLogs(undefined);

        if (isDiff) {
            setIsDiffEditor(true); setOriginalContent(source); setModifiedContent(target); setEditorErrorMessage(undefined);
        } else {
            setIsDiffEditor(false); setEditorContent(content);
            if (tableName && documentId) {
                const live = syncItemsStatus[`${tableName}:${documentId}`];
                let err: string | undefined;
                if (live && live.status === 'ERROR') err = live.message || 'Unknown error';
                else {
                    const dto = selections.find(s => s.tableName === tableName);
                    const sel = dto?.selections?.find(s => s.documentId === documentId && s.syncSuccess === false);
                    if (sel && sel.syncFailureResponse) err = sel.syncFailureResponse;
                }
                setEditorErrorMessage(err);
            } else setEditorErrorMessage(undefined);
        }
        setEditorDialogHeader(header);
        setEditorDialogVisible(true);
    };

    const inspectItem = (tableName: string, documentId: string) => {
        const entry = findEntry(tableName, documentId);
        if (!entry) return;
        const diff = isUpdateEntry(tableName, documentId);
        if (diff.isUpdate) openEditorDialog(null, true, diff.source, diff.target, 'View differences', tableName, documentId);
        else openEditorDialog(entry, false, null, null, 'View content', tableName, documentId);
    };

    // ---- derived: items + status -------------------------------------------
    const allItems: ReviewItem[] = useMemo(() => {
        const items: ReviewItem[] = [];
        selections.forEach(dto => (dto.selections || []).forEach(sel => {
            const operation: Operation = sel.direction === 'TO_CREATE' ? 'create' : sel.direction === 'TO_UPDATE' ? 'update' : 'delete';
            items.push({ contentType: dto.tableName, documentId: sel.documentId, operation, statusInfo: sel });
        }));
        return items;
    }, [selections]);

    const effStatus = (it: ReviewItem): EffStatus => {
        const live = syncItemsStatus[`${it.contentType}:${it.documentId}`];
        if (live) {
            if (live.status === 'SUCCESS') return 'success';
            if (live.status === 'ERROR') return 'failed';
            if (live.status === 'IN_PROGRESS') return 'processing';
        }
        if (it.statusInfo && it.statusInfo.syncSuccess === true) return 'success';
        if (it.statusInfo && it.statusInfo.syncSuccess === false) return 'failed';
        return 'pending';
    };

    const statusMessage = (it: ReviewItem): string | undefined => {
        const live = syncItemsStatus[`${it.contentType}:${it.documentId}`];
        if (live && live.status === 'ERROR') return live.message || 'Unknown error';
        return it.statusInfo?.syncFailureResponse || undefined;
    };

    const statusCounts = useMemo(() => {
        const c = { success: 0, failed: 0, processing: 0, pending: 0 } as Record<EffStatus, number>;
        allItems.forEach(it => { c[effStatus(it)]++; });
        return c;
    }, [allItems, syncItemsStatus]);

    const anyRunData = statusCounts.success + statusCounts.failed + statusCounts.processing > 0;

    // ---- filtering + grouping ----------------------------------------------
    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        return allItems.filter(it => {
            if (!opFilter.has(it.operation)) return false;
            if (statusFilter !== 'all' && effStatus(it) !== statusFilter) return false;
            if (q) {
                const hay = `${shortType(it.contentType)} ${itemLabel(it.contentType, it.documentId)} ${it.documentId}`.toLowerCase();
                if (!hay.includes(q)) return false;
            }
            return true;
        });
    }, [allItems, opFilter, statusFilter, query, syncItemsStatus]);

    const groups = useMemo(() => {
        const m = new Map<string, ReviewItem[]>();
        filtered.forEach(it => { const a = m.get(it.contentType) || []; a.push(it); m.set(it.contentType, a); });
        return Array.from(m.entries()).sort((a, b) => a[0].localeCompare(b[0]));
    }, [filtered]);

    const toggleOp = (op: Operation) => setOpFilter(prev => {
        const n = new Set(prev);
        if (n.has(op)) n.delete(op); else n.add(op);
        if (n.size === 0) return new Set(['create', 'update', 'delete']);
        return n;
    });
    const toggleGroup = (ct: string) => setCollapsed(prev => { const n = new Set(prev); n.has(ct) ? n.delete(ct) : n.add(ct); return n; });

    // ---- small render helpers ----------------------------------------------
    const opDot = (op: Operation) => <span className={`ss-state-dot ${op === 'create' ? 'add' : op === 'update' ? 'upd' : 'del'}`} />;
    const opLabel = (op: Operation) => op === 'create' ? 'Create' : op === 'update' ? 'Update' : 'Delete';

    const statusChip = (it: ReviewItem) => {
        const es = effStatus(it);
        if (es === 'success') return <span className="ss-badge success">OK</span>;
        if (es === 'processing') return <span className="ss-badge info"><i className="pi pi-spin pi-spinner" style={{ fontSize: 9, marginRight: 4 }} />Running</span>;
        if (es === 'failed') return <span className="ss-badge danger" title={statusMessage(it) || 'Failed'}>Failed</span>;
        return <span className="ss-dim" style={{ fontSize: 11 }}>pending</span>;
    };

    const Pill: React.FC<{ op: Operation; n: number }> = ({ op, n }) => {
        const cls = op === 'create' ? 'add' : op === 'update' ? 'upd' : 'del';
        return (
            <button className={`ss-pill ${cls}${opFilter.has(op) ? ' active' : ''}`} onClick={() => toggleOp(op)}>
                {opLabel(op)} {n}
            </button>
        );
    };

    const pct = syncProgress && syncProgress.totalItems > 0
        ? Math.round((syncProgress.processedItems / syncProgress.totalItems) * 100) : 0;

    const runnable = ['MERGED_COLLECTIONS', 'FAILED', 'IN_PROGRESS', 'REVIEW'].includes(status);
    const completed = status === 'COMPLETED';

    if (totalItems === 0) {
        return (
            <div className="ss-review">
                <div className="ss-empty">
                    <i className="pi pi-inbox" aria-hidden="true" />
                    No items selected for synchronization.<br />
                    <span className="ss-dim" style={{ fontSize: 12 }}>Go back to the changes step and pick what to sync.</span>
                </div>
                <Toast ref={toast} position="top-right" />
            </div>
        );
    }

    return (
        <div className="ss-review">
            {/* Toolbar: search + operation filters + plan toggle */}
            <div className="ss-review-toolbar">
                <div className="ss-search" style={{ width: 200 }}>
                    <i className="pi pi-search" aria-hidden="true" />
                    <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search items…" />
                </div>
                <Pill op="create" n={totalToCreate} />
                <Pill op="update" n={totalToUpdate} />
                <Pill op="delete" n={totalToDelete} />

                {anyRunData && (
                    <>
                        <span style={{ width: 1, height: 18, background: 'var(--ss-border)', margin: '0 2px' }} />
                        <button className={`ss-pill${statusFilter === 'all' ? ' active' : ''}`} onClick={() => setStatusFilter('all')}>All</button>
                        {statusCounts.success > 0 && <button className={`ss-pill${statusFilter === 'success' ? ' active' : ''}`} onClick={() => setStatusFilter('success')}><i className="pi pi-check" style={{ fontSize: 10, color: 'var(--ss-green)' }} /> {statusCounts.success}</button>}
                        {statusCounts.failed > 0 && <button className={`ss-pill${statusFilter === 'failed' ? ' active' : ''}`} onClick={() => setStatusFilter('failed')}><i className="pi pi-times" style={{ fontSize: 10, color: 'var(--ss-red)' }} /> {statusCounts.failed}</button>}
                    </>
                )}

                <span style={{ marginLeft: 'auto' }} />
                <button className={`ss-btn subtle${showPlan ? ' active' : ''}`} onClick={() => setShowPlan(v => !v)}>
                    <i className={`pi ${showPlan ? 'pi-chevron-up' : 'pi-sitemap'}`} aria-hidden="true" /> Execution order
                </button>
            </div>

            {/* Live progress strip — visible as soon as the run starts, even before the first event */}
            {(syncInProgress || syncCompleted || syncFailed) && (
                <div style={{ padding: '10px 16px', borderBottom: '1px solid var(--ss-border)', background: 'var(--ss-surface-2)' }}>
                    <div className="ss-card-row" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                        <span style={{ fontSize: 12, color: 'var(--ss-text-2)' }}>
                            <i className={`pi ${syncInProgress ? 'pi-spin pi-spinner' : syncCompleted ? 'pi-check-circle' : 'pi-times-circle'}`}
                               style={{ marginRight: 6, color: syncCompleted ? 'var(--ss-green)' : syncFailed ? 'var(--ss-red)' : 'var(--ss-info)' }} />
                            {syncProgress
                                ? <>{syncProgress.processedItems} / {syncProgress.totalItems} processed{syncProgress.currentItem && syncInProgress && <span className="ss-dim"> · {syncProgress.currentItem}</span>}</>
                                : (syncInProgress ? 'Starting synchronization…' : syncFailed ? 'Synchronization failed' : 'Done')}
                        </span>
                        {syncProgress && <span style={{ fontSize: 12, color: 'var(--ss-text-2)' }}>{pct}%</span>}
                    </div>
                    <div style={{ height: 6, borderRadius: 999, background: 'var(--ss-surface-3)', overflow: 'hidden' }}>
                        <div style={{ width: syncProgress ? `${pct}%` : '15%', height: '100%', background: syncFailed ? 'var(--ss-red)' : 'var(--ss-accent)', transition: 'width .25s', opacity: syncProgress ? 1 : 0.5 }} />
                    </div>
                    {syncProgress?.message && syncFailed && (
                        <div style={{ marginTop: 8, fontSize: 12, color: 'var(--ss-red)' }}>{syncProgress.message}</div>
                    )}
                </div>
            )}

            {/* Execution plan (collapsible) */}
            {showPlan && (
                <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--ss-border)', background: 'var(--ss-surface-2)' }}>
                    <div className="ss-card-row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
                        <span style={{ fontSize: 12, color: 'var(--ss-text-2)' }}>Items are processed in dependency order. Dashed arrows = circular dependencies handled in a second pass.</span>
                        <button className="ss-btn subtle" onClick={loadPlan} disabled={planLoading}>
                            <i className="pi pi-refresh" aria-hidden="true" /> {planLoading ? 'Loading…' : 'Recompute'}
                        </button>
                    </div>
                    {planError && <div style={{ fontSize: 12, color: 'var(--ss-red)' }}>{planError}</div>}
                    {!planLoading && !planError && syncPlan && (syncPlan.batches.length === 0
                        ? <div className="ss-dim" style={{ fontSize: 12 }}>No dependencies to resolve — items are processed directly.</div>
                        : <SyncPlanGraph syncPlan={syncPlan} syncItemsStatus={syncItemsStatus} onInspect={inspectItem} labelFor={itemLabel} />)}

                    {syncPlan && syncPlan.missingDependencies.length > 0 && (
                        <div style={{ marginTop: 10 }}>
                            <span className="ss-chip warn"><i className="pi pi-exclamation-triangle" /> {syncPlan.missingDependencies.length} missing dependencies</span>
                            <ul style={{ margin: '6px 0 0', paddingLeft: 18, fontSize: 12, color: 'var(--ss-text-2)' }}>
                                {syncPlan.missingDependencies.map((m, i) => (
                                    <li key={i}>{shortType(m.fromTable)}:{m.fromDocumentId} requires <code>{shortType(m.linkTargetTable)}.{m.linkField}</code> — {m.reason}</li>
                                ))}
                            </ul>
                        </div>
                    )}
                    {syncPlan && syncPlan.circularEdges.length > 0 && (
                        <div style={{ marginTop: 10 }}>
                            <span className="ss-chip info"><i className="pi pi-replay" /> {syncPlan.circularEdges.length} circular dependencies</span>
                        </div>
                    )}
                </div>
            )}

            {/* Item list grouped by content type */}
            {groups.length === 0 ? (
                <div className="ss-empty"><i className="pi pi-filter-slash" aria-hidden="true" />No items match the current filters.</div>
            ) : groups.map(([ct, items]) => {
                const isCollapsed = collapsed.has(ct);
                const nC = items.filter(i => i.operation === 'create').length;
                const nU = items.filter(i => i.operation === 'update').length;
                const nD = items.filter(i => i.operation === 'delete').length;
                return (
                    <div key={ct}>
                        <div className="ss-group-head">
                            <button onClick={() => toggleGroup(ct)}>
                                <i className={`pi ${isCollapsed ? 'pi-chevron-right' : 'pi-chevron-down'}`} style={{ fontSize: 10 }} />
                                {shortType(ct)}
                                <span className="ss-dim" style={{ textTransform: 'none', letterSpacing: 0 }}>{items.length}</span>
                            </button>
                            <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 8 }}>
                                {nC > 0 && <span className="ss-count add">+{nC}</span>}
                                {nU > 0 && <span className="ss-count upd">~{nU}</span>}
                                {nD > 0 && <span className="ss-count del">−{nD}</span>}
                            </span>
                        </div>
                        {!isCollapsed && items.map(it => {
                            const thumb = fileThumbUrl(it.contentType, it.documentId);
                            return (
                                <div className="ss-erow" key={`${it.contentType}:${it.documentId}:${it.operation}`}>
                                    <div className="ss-erow-head" onClick={() => inspectItem(it.contentType, it.documentId)}>
                                        {opDot(it.operation)}
                                        {thumb && <span className="ss-thumb"><img src={thumb} alt="" /></span>}
                                        <span className={`ss-erow-name${it.operation === 'delete' ? ' del' : ''}`} style={{ flex: '0 1 auto', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                            {itemLabel(it.contentType, it.documentId)}
                                        </span>
                                        <span className="ss-erow-summary">{opLabel(it.operation)}</span>
                                        <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 10 }}>
                                            {statusChip(it)}
                                            <button className="ss-link" onClick={(e) => { e.stopPropagation(); inspectItem(it.contentType, it.documentId); }} title="Inspect">
                                                <i className="pi pi-eye" />
                                            </button>
                                        </span>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                );
            })}

            {/* Verification report */}
            {verifyReport && (
                <div style={{ padding: '10px 16px', borderTop: '1px solid var(--ss-border)', background: 'var(--ss-surface-2)' }}>
                    <div className="ss-card-row" style={{ gap: 10, marginBottom: verifyReport.inconsistent > 0 ? 8 : 0, flexWrap: 'wrap' }}>
                        <span style={{ fontSize: 12, color: 'var(--ss-text-2)' }}><i className="pi pi-verified" style={{ marginRight: 6, color: verifyReport.inconsistent > 0 ? 'var(--ss-red)' : 'var(--ss-green)' }} />Post-sync verification</span>
                        <span className="ss-badge success">{verifyReport.consistent} consistent</span>
                        {verifyReport.schemaGap > 0 && <span className="ss-badge warn" title="Differ only in source-only fields the target schema can't store (auto-dropped by design).">{verifyReport.schemaGap} schema-gap</span>}
                        {verifyReport.inconsistent > 0
                            ? <span className="ss-badge danger">{verifyReport.inconsistent} real mismatch</span>
                            : <span className="ss-dim" style={{ fontSize: 11.5 }}>no real mismatches</span>}
                    </div>
                    {verifyReport.items.filter(i => i.severity !== 'ok').map((it, idx) => (
                        <div key={idx} style={{ fontSize: 11.5, color: it.severity === 'mismatch' ? 'var(--ss-red)' : 'var(--ss-text-3)', padding: '2px 0' }}>
                            <span className={`ss-state-dot ${it.severity === 'mismatch' ? 'del' : 'upd'}`} style={{ display: 'inline-block', marginRight: 6 }} />
                            <code>{it.contentType}</code> · {it.documentId} — {it.reason || it.actual}
                        </div>
                    ))}
                </div>
            )}

            {/* Action bar */}
            <div className="ss-actionbar">
                <span style={{ fontSize: 12, color: 'var(--ss-text-2)' }}>
                    <strong style={{ color: 'var(--ss-text)' }}>{totalItems}</strong> items
                    <span className="ss-count add" style={{ marginLeft: 10 }}>+{totalToCreate}</span>
                    <span className="ss-count upd" style={{ marginLeft: 8 }}>~{totalToUpdate}</span>
                    <span className="ss-count del" style={{ marginLeft: 8 }}>−{totalToDelete}</span>
                    {anyRunData && (
                        <>
                            <span style={{ margin: '0 8px', color: 'var(--ss-border)' }}>|</span>
                            {statusCounts.success > 0 && <span style={{ color: 'var(--ss-green)' }}>{statusCounts.success} ok</span>}
                            {statusCounts.failed > 0 && (
                                <button className="ss-link" style={{ marginLeft: 8, color: 'var(--ss-red)' }} onClick={() => setStatusFilter('failed')}>
                                    {statusCounts.failed} failed →
                                </button>
                            )}
                        </>
                    )}
                </span>
                <span style={{ marginLeft: 'auto' }} />
                {anyRunData && !syncInProgress && (
                    <button className="ss-btn subtle" onClick={verifyDestination} disabled={verifying} title="Recompute the comparison and confirm the target is actually consistent for synced items.">
                        <i className={`pi ${verifying ? 'pi-spin pi-spinner' : 'pi-verified'}`} aria-hidden="true" /> {verifying ? 'Verifying…' : 'Verify destination'}
                    </button>
                )}
                {completed && !syncInProgress && statusCounts.failed === 0 && (
                    <span className="ss-chip success"><i className="pi pi-check-circle" /> Merge completed</span>
                )}
                {runnable && !syncInProgress && (
                    <label className="ss-link" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer', color: 'var(--ss-text-2)' }} title="If the run does not fully succeed, restore the pre-run snapshot.">
                        <input type="checkbox" checked={rollbackOnFailure} onChange={(e) => setRollbackOnFailure(e.target.checked)} />
                        Rollback on failure
                    </label>
                )}
                {runnable && !syncInProgress && statusCounts.failed > 0 && (
                    <button className="ss-btn" disabled={completing} onClick={() => handleCompleteMerge({ onlyFailed: true })}>
                        <i className="pi pi-replay" aria-hidden="true" /> Retry failed ({statusCounts.failed})
                    </button>
                )}
                {runnable && !syncInProgress && (
                    <button className="ss-btn primary" disabled={completing} onClick={() => handleCompleteMerge()}>
                        <i className={`pi ${completing ? 'pi-spin pi-spinner' : statusCounts.failed > 0 ? 'pi-refresh' : 'pi-check'}`} aria-hidden="true" />
                        {completing ? 'Starting…' : statusCounts.failed > 0 ? 'Re-run all' : 'Complete merge'}
                    </button>
                )}
                {syncInProgress && (
                    <button className="ss-btn primary" disabled>
                        <i className="pi pi-spin pi-spinner" aria-hidden="true" /> Running… {syncProgress ? `${syncProgress.processedItems}/${syncProgress.totalItems}` : ''}
                    </button>
                )}
            </div>

            <Toast ref={toast} position="top-right" />
            <EditorDialog
                visible={editorDialogVisible}
                onHide={() => { setEditorDialogVisible(false); setHttpLogs(undefined); }}
                header={editorDialogHeader}
                content={editorContent}
                isDiff={isDiffEditor}
                originalContent={originalContent}
                modifiedContent={modifiedContent}
                errorMessage={editorErrorMessage}
                httpLogs={httpLogs}
            />
        </div>
    );
};

export default CompleteMergeStep;
