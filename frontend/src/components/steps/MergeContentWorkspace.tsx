import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Dialog } from 'primereact/dialog';
import { Toast } from 'primereact/toast';
import { http, apiErrorMessage } from '../../api/http';
import {
  ContentTypeComparisonResultKind,
  ContentTypeComparisonResultWithRelationships,
  MergeRequestData,
  MergeRequestSelectionDTO,
  ResolvedRef,
  StrapiContentTypeKind,
} from '../../types';
import { getRepresentativeAttributes } from '../../utils/attributeUtils';
import FieldDiffSplit, { changeSummary, computeLeaves } from '../common/FieldDiffSplit';
import ExclusionsManager from './components/ExclusionsManager';
import ManualCollectionMapper from './components/ManualCollectionMapper';

interface Props {
  mergeRequestId: number;
  data: MergeRequestData;
  sourceInstanceId: number;
  targetInstanceId: number;
  updateAllSelections: (
    kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string,
    documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind
  ) => Promise<boolean>;
  onSaved?: () => void | Promise<void>;
}

interface ChangeRow {
  tableName: string;
  kind: StrapiContentTypeKind;
  documentId: string;
  label: string;
  state: ContentTypeComparisonResultKind;
  uid: string;
  sourceId?: number | null;
  targetId?: number | null;
  syncId?: string | null;
  source?: any;
  target?: any;
  sourceRefs?: Record<string, ResolvedRef[]>;
  targetRefs?: Record<string, ResolvedRef[]>;
  sourceDoc?: string;
  targetDoc?: string;
  draftBadge?: 'modified' | 'draft-only' | null;
}

const labelOf = (r: ContentTypeComparisonResultWithRelationships): string => {
  const c = r.sourceContent || r.targetContent;
  if (!c) return '(empty)';
  const attrs = getRepresentativeAttributes(c as any, 2);
  return attrs.length ? attrs.map((a) => a.value).join(' · ') : (c.metadata?.documentId || '(entry)');
};

const rowSummary = (r: ChangeRow): string => {
  if (r.state === 'ONLY_IN_SOURCE') {
    const n = computeLeaves(r.source, undefined).length;
    return `new entity · ${n} field${n === 1 ? '' : 's'}`;
  }
  if (r.state === 'ONLY_IN_TARGET') return 'only in target';
  if (r.state === 'DIFFERENT') return changeSummary(r.source, r.target);
  return 'identical';
};

const stateClass = (s: string): 'add' | 'upd' | 'del' => (s === 'ONLY_IN_SOURCE' ? 'add' : s === 'ONLY_IN_TARGET' ? 'del' : 'upd');
const stateChip = (s: string): { label: string; cls: string } => {
  if (s === 'ONLY_IN_SOURCE') return { label: 'New', cls: 'success' };
  if (s === 'ONLY_IN_TARGET') return { label: 'Del', cls: 'danger' };
  if (s === 'DIFFERENT') return { label: 'Changed', cls: 'warn' };
  return { label: 'Identical', cls: '' };
};

const MergeContentWorkspace: React.FC<Props> = ({ mergeRequestId, data, sourceInstanceId, targetInstanceId, updateAllSelections, onSaved }) => {
  const [search, setSearch] = useState('');
  const [showIdentical, setShowIdentical] = useState(false);
  const [showExcluded, setShowExcluded] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
  const [activeStates, setActiveStates] = useState<Set<string>>(new Set(['ONLY_IN_SOURCE', 'DIFFERENT', 'ONLY_IN_TARGET']));
  const [showExclusions, setShowExclusions] = useState(false);
  const [mapperTable, setMapperTable] = useState<string | null>(null);
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const toast = useRef<Toast>(null);
  const setRowBusy = (k: string, on: boolean) => setBusy((p) => { const n = new Set(p); on ? n.add(k) : n.delete(k); return n; });

  // Index every compared entity by documentId (both sides) so a relation chip can open a side-by-side
  // detail of the referenced entity. Drill-down keeps a breadcrumb stack.
  type RefNode = { source?: any; target?: any; sourceRefs?: any; targetRefs?: any; type: string; state: string; sourceDoc?: string; targetDoc?: string };
  const entityIndex = useMemo(() => {
    const m = new Map<string, RefNode>();
    const add = (r: ContentTypeComparisonResultWithRelationships) => {
      const node: RefNode = {
        source: r.sourceContent?.cleanData, target: r.targetContent?.cleanData,
        sourceRefs: r.sourceRefs, targetRefs: r.targetRefs,
        type: r.contentType.split('.').pop() || r.contentType, state: r.compareState,
        sourceDoc: r.sourceContent?.metadata?.documentId, targetDoc: r.targetContent?.metadata?.documentId,
      };
      const sd = r.sourceContent?.metadata?.documentId; const td = r.targetContent?.metadata?.documentId;
      if (sd) m.set(sd, node);
      if (td) m.set(td, node);
    };
    Object.values(data.singleTypes || {}).forEach(add);
    Object.values(data.collectionTypes || {}).forEach((arr) => (arr || []).forEach(add));
    return m;
  }, [data.singleTypes, data.collectionTypes]);

  const [refStack, setRefStack] = useState<{ label: string; node: RefNode }[]>([]);
  const openRef = (documentId: string, label: string) => {
    const node = entityIndex.get(documentId);
    if (node) setRefStack((p) => [...p, { label, node }]);
  };

  // --- Quick exclusions ---
  const [exclusions, setExclusions] = useState<{ id: number; contentType: string; documentId?: string | null; fieldPath?: string | null }[]>([]);
  const excludedSet = useMemo(() => {
    const s = new Set<string>();
    exclusions.forEach((e) => { if (!e.fieldPath && e.documentId) s.add(`${e.contentType}:${e.documentId}`); });
    return s;
  }, [exclusions]);
  const isExcluded = (r: ChangeRow) => r.state === 'EXCLUDED' || excludedSet.has(`${r.uid}:${r.documentId}`);
  const loadExclusions = async () => {
    try { const r = await http.get(`/api/merge-requests/${mergeRequestId}/exclusions`); setExclusions(r.data?.data || r.data || []); } catch { /* ignore */ }
  };
  useEffect(() => { loadExclusions(); /* eslint-disable-next-line */ }, [mergeRequestId]);
  const toggleExclude = async (r: ChangeRow) => {
    const k = `${r.tableName}:${r.documentId}`;
    const isExcluded = r.state === 'EXCLUDED' || exclusions.some((e) => e.contentType === r.uid && e.documentId === r.documentId && !e.fieldPath);
    setRowBusy(k, true);
    try {
      if (isExcluded) {
        const ex = exclusions.find((e) => e.contentType === r.uid && e.documentId === r.documentId && !e.fieldPath);
        if (ex?.id != null) await http.delete(`/api/merge-requests/${mergeRequestId}/exclusions/${ex.id}`);
        toast.current?.show({ severity: 'success', summary: 'Included', detail: `${r.label} will be merged`, life: 2500 });
      } else {
        await http.post(`/api/merge-requests/${mergeRequestId}/exclusions`, { contentType: r.uid, documentId: r.documentId });
        toast.current?.show({ severity: 'info', summary: 'Excluded', detail: `${r.label} hidden — see the “excluded” filter`, life: 3000 });
      }
      await loadExclusions(); // updates excludedSet -> row hides/shows immediately (no full recompare needed)
    } catch (e) { toast.current?.show({ severity: 'error', summary: 'Failed', detail: apiErrorMessage(e), life: 4000 }); }
    finally { setRowBusy(k, false); }
  };

  // --- Quick mapping: pick one unmatched entity, then "map here" on its counterpart ---
  const [mapPick, setMapPick] = useState<{ table: string; uid: string; side: 'source' | 'target'; doc: string; id?: number | null; label: string } | null>(null);
  const [mapping, setMapping] = useState(false);
  const pickForMap = (r: ChangeRow) => {
    const side = r.state === 'ONLY_IN_SOURCE' ? 'source' : 'target';
    const id = side === 'source' ? r.sourceId : r.targetId;
    setMapPick((p) => (p && p.side === side && p.doc === r.documentId ? null : { table: r.tableName, uid: r.uid, side, doc: r.documentId, id, label: r.label }));
  };
  const mapWith = async (r: ChangeRow) => {
    if (!mapPick) return;
    const sourceDoc = mapPick.side === 'source' ? mapPick.doc : r.documentId;
    const sourceId = mapPick.side === 'source' ? mapPick.id : r.sourceId;
    const targetDoc = mapPick.side === 'source' ? r.documentId : mapPick.doc;
    const targetId = mapPick.side === 'source' ? r.targetId : mapPick.id;
    if (sourceId == null || targetId == null) {
      toast.current?.show({ severity: 'error', summary: 'Mapping failed', detail: 'Missing numeric id on one side', life: 4000 });
      return;
    }
    setMapping(true);
    try {
      await http.post(`/api/merge-requests/${mergeRequestId}/mappings`, {
        items: [{ contentType: mapPick.uid, sourceDocumentId: sourceDoc, sourceId, targetDocumentId: targetDoc, targetId, locale: null }],
      });
      toast.current?.show({ severity: 'success', summary: 'Mapped', detail: `${mapPick.label} ↔ ${r.label}`, life: 3000 });
      setMapPick(null);
      onSaved && (await onSaved());
    } catch (e) { toast.current?.show({ severity: 'error', summary: 'Mapping failed', detail: apiErrorMessage(e), life: 4000 }); }
    finally { setMapping(false); }
  };

  // Reverse index: which entities reference a given entity (by referenced documentId, both sides).
  type Referrer = { label: string; type: string; openDoc: string; key: string; field: string };
  const reverseIndex = useMemo(() => {
    const m = new Map<string, Referrer[]>();
    const rows: ContentTypeComparisonResultWithRelationships[] = [
      ...Object.values(data.singleTypes || {}),
      ...Object.values(data.collectionTypes || {}).flat(),
    ];
    rows.forEach((r) => {
      const label = labelOf(r);
      const type = r.contentType.split('.').pop() || r.contentType;
      const openDoc = r.sourceContent?.metadata?.documentId || r.targetContent?.metadata?.documentId || r.id;
      const key = r.sourceContent?.metadata?.syncId || r.targetContent?.metadata?.syncId || openDoc;
      [r.sourceRefs, r.targetRefs].forEach((refs) => {
        Object.entries(refs || {}).forEach(([field, list]) => {
          (list as ResolvedRef[]).forEach((ref) => {
            const arr = m.get(ref.documentId) || [];
            arr.push({ label, type, openDoc, key, field });
            m.set(ref.documentId, arr);
          });
        });
      });
    });
    return m;
  }, [data.singleTypes, data.collectionTypes]);

  const referrersFor = (sourceDoc?: string, targetDoc?: string): Referrer[] => {
    const all = [...(sourceDoc ? reverseIndex.get(sourceDoc) || [] : []), ...(targetDoc ? reverseIndex.get(targetDoc) || [] : [])];
    const seen = new Set<string>(); const out: Referrer[] = [];
    all.forEach((x) => { const k = `${x.key}|${x.field}`; if (!seen.has(k)) { seen.add(k); out.push(x); } });
    return out;
  };

  const ReferencedBy: React.FC<{ referrers: Referrer[] }> = ({ referrers }) => {
    if (referrers.length === 0) return <div className="ss-dim" style={{ fontSize: 11.5 }}>Not referenced by any entity.</div>;
    const byField = referrers.reduce((acc, r) => { (acc[r.field] = acc[r.field] || []).push(r); return acc; }, {} as Record<string, Referrer[]>);
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <div className="ss-dim" style={{ fontSize: 11 }}>Referenced by {referrers.length} {referrers.length === 1 ? 'entity' : 'entities'}</div>
        {Object.entries(byField).map(([field, list]) => (
          <div key={field} style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 }}>
            <span className="ss-dim" style={{ fontSize: 11 }}>via <code>{field}</code>:</span>
            {list.map((x, i) => (
              <button key={i} className="ss-link" style={{ fontSize: 12 }} onClick={() => openRef(x.openDoc, x.label)}>
                {x.label} <span className="ss-badge" style={{ fontSize: 9 }}>{x.type}</span>
              </button>
            ))}
          </div>
        ))}
      </div>
    );
  };

  const selectedSet = useMemo(() => {
    const s = new Set<string>();
    (data.selections || ([] as MergeRequestSelectionDTO[])).forEach((sel) => {
      (sel.selections || []).forEach((x: any) => s.add(`${sel.tableName}:${x.documentId}`));
    });
    return s;
  }, [data.selections]);

  const groups = useMemo(() => {
    const map = new Map<string, { uid: string; tableName: string; kind: StrapiContentTypeKind; displayName: string; rows: ChangeRow[] }>();
    const push = (r: ContentTypeComparisonResultWithRelationships, kind: StrapiContentTypeKind) => {
      const c = r.sourceContent || r.targetContent;
      const documentId = c?.metadata?.documentId || r.id;
      const row: ChangeRow = {
        tableName: r.tableName, kind, documentId, uid: r.contentType,
        sourceId: r.sourceContent?.metadata?.id, targetId: r.targetContent?.metadata?.id,
        label: labelOf(r), state: r.compareState,
        syncId: r.sourceContent?.metadata?.syncId || r.targetContent?.metadata?.syncId || null,
        // For a "modified" entry the published bodies match — the real change is in the draft channel.
        // Show the source DRAFT body so the side-by-side reflects what will actually change.
        source: (r.sourceContent as any)?.draft?.cleanData ?? r.sourceContent?.cleanData,
        target: r.targetContent?.cleanData,
        sourceRefs: r.sourceRefs, targetRefs: r.targetRefs,
        sourceDoc: r.sourceContent?.metadata?.documentId, targetDoc: r.targetContent?.metadata?.documentId,
        draftBadge: (() => {
          const sc: any = r.sourceContent;
          if (sc?.metadata?.isDraftOnly) return 'draft-only';
          if (sc?.draft) return 'modified';
          return null;
        })(),
      };
      const g = map.get(r.contentType) || { uid: r.contentType, tableName: r.tableName, kind, displayName: r.contentType.split('.').pop() || r.contentType, rows: [] };
      g.rows.push(row);
      map.set(r.contentType, g);
    };
    Object.values(data.singleTypes || {}).forEach((r) => push(r, StrapiContentTypeKind.SingleType));
    Object.values(data.collectionTypes || {}).forEach((arr) => (arr || []).forEach((r) => push(r, StrapiContentTypeKind.CollectionType)));
    return Array.from(map.values());
  }, [data.singleTypes, data.collectionTypes]);

  const counts = useMemo(() => {
    const c: Record<string, number> = { ONLY_IN_SOURCE: 0, DIFFERENT: 0, ONLY_IN_TARGET: 0, IDENTICAL: 0, EXCLUDED: 0 };
    groups.forEach((g) => g.rows.forEach((r) => { if (isExcluded(r)) c.EXCLUDED++; else c[r.state] = (c[r.state] || 0) + 1; }));
    return c;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groups, excludedSet]);

  const visibleGroups = useMemo(() => {
    const q = search.trim().toLowerCase();
    return groups
      .map((g) => ({
        ...g,
        rows: g.rows.filter((r) => {
          if (q && !(`${r.label} ${r.documentId}`.toLowerCase().includes(q))) return false;
          if (isExcluded(r)) return showExcluded;                // excluded hidden unless its filter is on
          if (r.state === 'IDENTICAL') return showIdentical;
          return activeStates.has(r.state);
        }),
      }))
      .filter((g) => g.rows.length > 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [groups, search, showIdentical, showExcluded, activeStates, excludedSet]);

  const total = counts.ONLY_IN_SOURCE + counts.DIFFERENT + counts.ONLY_IN_TARGET;

  const toggleState = (s: string) =>
    setActiveStates((prev) => { const n = new Set(prev); n.has(s) ? n.delete(s) : n.add(s); return n; });
  const toggleOpen = (k: string) =>
    setOpen((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  const toggleGroupCollapse = (uid: string) =>
    setCollapsedGroups((prev) => { const n = new Set(prev); n.has(uid) ? n.delete(uid) : n.add(uid); return n; });

  const toggleRow = async (r: ChangeRow, selected: boolean) => { await updateAllSelections(r.kind, selected, r.tableName, [r.documentId]); };
  const toggleGroupSel = async (g: { kind: StrapiContentTypeKind; tableName: string; rows: ChangeRow[] }, selected: boolean) => {
    const ids = g.rows.filter((r) => r.state !== 'IDENTICAL').map((r) => r.documentId);
    if (ids.length) await updateAllSelections(g.kind, selected, g.tableName, ids);
  };

  const Pill: React.FC<{ s: string; cls: 'add' | 'upd' | 'del'; label: string }> = ({ s, cls, label }) => (
    <button className={`ss-pill ${cls}${activeStates.has(s) ? ' active' : ''}`} onClick={() => toggleState(s)}>{label} {counts[s] || 0}</button>
  );

  return (
    <div className="ss-review">
      <Toast ref={toast} />
      <div className="ss-review-toolbar">
        <div className="ss-search" style={{ width: 200 }}>
          <i className="pi pi-search" aria-hidden="true" />
          <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Filter…" />
        </div>
        <span className={`ss-pill${activeStates.size === 3 ? ' active' : ''}`} style={{ cursor: 'default' }}>All {total}</span>
        <Pill s="ONLY_IN_SOURCE" cls="add" label="+" />
        <Pill s="DIFFERENT" cls="upd" label="~" />
        <Pill s="ONLY_IN_TARGET" cls="del" label="−" />
        <label className="ss-dim" style={{ fontSize: 11, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <input type="checkbox" checked={showIdentical} onChange={(e) => setShowIdentical(e.target.checked)} /> identical ({counts.IDENTICAL || 0})
        </label>
        {(counts.EXCLUDED || 0) > 0 && (
          <button className={`ss-pill${showExcluded ? ' active' : ''}`} onClick={() => setShowExcluded((v) => !v)} title="Show entities excluded from the merge">
            <i className="pi pi-eye-slash" aria-hidden="true" /> excluded {counts.EXCLUDED}
          </button>
        )}
        <span style={{ marginLeft: 'auto' }} />
        <button className="ss-btn subtle" onClick={() => setShowExclusions(true)}><i className="pi pi-ban" aria-hidden="true" /> Exclusions</button>
      </div>

      {mapPick && (
        <div className="ss-actionbar" style={{ borderRadius: 0, borderLeft: 'none', borderRight: 'none', background: 'var(--ss-accent-soft-bg)' }}>
          {mapping ? <i className="pi pi-spin pi-spinner" aria-hidden="true" /> : <i className="pi pi-link" aria-hidden="true" />}
          <span style={{ fontSize: 12 }}>
            Mapping <span className={`ss-count ${mapPick.side === 'source' ? 'add' : 'del'}`}>{mapPick.label}</span>
            {' '}({mapPick.side}) — now click <strong style={{ fontWeight: 500 }}>“map here”</strong> on the matching{' '}
            <strong style={{ fontWeight: 500 }}>{mapPick.side === 'source' ? 'Del (target)' : 'New (source)'}</strong> entity in <code>{mapPick.table.split('.').pop()}</code>.
          </span>
          <span style={{ marginLeft: 'auto' }} />
          <button className="ss-btn subtle" onClick={() => setMapPick(null)}>Cancel</button>
        </div>
      )}

      {visibleGroups.length === 0 ? (
        <div className="ss-empty"><i className="pi pi-inbox" aria-hidden="true" />No changes match the current filters.</div>
      ) : (
        visibleGroups.map((g) => {
          const selectable = g.rows.filter((r) => r.state !== 'IDENTICAL');
          const allSelected = selectable.length > 0 && selectable.every((r) => selectedSet.has(`${r.tableName}:${r.documentId}`));
          const groupCollapsed = collapsedGroups.has(g.uid);
          return (
            <div key={g.uid}>
              <div className="ss-group-head">
                <button onClick={() => toggleGroupCollapse(g.uid)}>
                  <i className={`pi pi-chevron-${groupCollapsed ? 'right' : 'down'}`} aria-hidden="true" />
                  {g.displayName} · {g.rows.length}
                </button>
                <span style={{ marginLeft: 'auto' }} />
                {g.kind === StrapiContentTypeKind.CollectionType && (
                  <button className="ss-link" onClick={() => setMapperTable(g.tableName)}><i className="pi pi-link" aria-hidden="true" /> mapping</button>
                )}
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, cursor: 'pointer', textTransform: 'none', letterSpacing: 0 }}>
                  <input type="checkbox" checked={allSelected} disabled={selectable.length === 0} onChange={(e) => toggleGroupSel(g, e.target.checked)} /> all
                </label>
              </div>

              {!groupCollapsed && g.rows.map((r) => {
                const key = `${r.tableName}:${r.documentId}`;
                const isSel = selectedSet.has(key);
                const isOpen = open.has(key);
                const chip = stateChip(r.state);
                const hasDiff = r.source || r.target;
                const referrers = referrersFor(r.sourceDoc, r.targetDoc);
                const rowBusy = busy.has(key);
                const excluded = isExcluded(r);
                const oneSided = (r.state === 'ONLY_IN_SOURCE' || r.state === 'ONLY_IN_TARGET') && !excluded;
                const isPicked = !!mapPick && mapPick.doc === r.documentId && oneSided;
                const isCounterpart = !!mapPick && mapPick.table === r.tableName && ((mapPick.side === 'source' && r.state === 'ONLY_IN_TARGET') || (mapPick.side === 'target' && r.state === 'ONLY_IN_SOURCE'));
                const headBg = isPicked ? 'var(--ss-accent-soft-bg)' : isCounterpart ? 'var(--ss-green-bg)' : undefined;
                return (
                  <div key={key} className={`ss-erow${isOpen ? ' open' : ''}`} style={{ opacity: excluded ? 0.6 : 1 }}>
                    <div className="ss-erow-head" onClick={() => hasDiff && toggleOpen(key)} style={{ background: headBg }}>
                      <input type="checkbox" checked={isSel} disabled={r.state === 'IDENTICAL' || excluded}
                        onClick={(e) => e.stopPropagation()} onChange={(e) => toggleRow(r, e.target.checked)} style={{ width: 14, height: 14 }} />
                      <i className={`pi pi-chevron-${isOpen ? 'down' : 'right'} ss-dim`} style={{ fontSize: 12, visibility: hasDiff ? 'visible' : 'hidden' }} aria-hidden="true" />
                      <span className={`ss-state-dot ${stateClass(r.state)}`} />
                      <span className={`ss-erow-name${r.state === 'ONLY_IN_TARGET' ? ' del' : ''}`} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 280, textDecoration: excluded ? 'line-through' : undefined }}>{r.label}</span>
                      <span className="ss-erow-summary">{rowSummary(r)}</span>
                      {r.draftBadge && (
                        <span className="ss-badge" style={{ fontSize: 9, background: 'rgba(245,166,35,0.18)', color: '#f5a623', borderColor: 'rgba(245,166,35,0.4)' }}
                          title={r.draftBadge === 'draft-only'
                            ? 'Draft-only: this entry has no published version (it will be created/kept as a draft on the target)'
                            : 'Modified: the working draft differs from the published version (both are reproduced on the target)'}>
                          <i className="pi pi-pencil" style={{ fontSize: 8 }} aria-hidden="true" /> {r.draftBadge}
                        </span>
                      )}
                      {r.syncId && <i className="pi pi-link ss-dim" title="identity linked" style={{ fontSize: 11 }} aria-hidden="true" />}
                      {referrers.length > 0 && <span className="ss-badge" title={`Referenced by ${referrers.length}`} style={{ fontSize: 10 }}><i className="pi pi-arrow-up-right" style={{ fontSize: 9 }} aria-hidden="true" /> {referrers.length}</span>}
                      <span style={{ marginLeft: 'auto' }} />
                      {oneSided && (
                        isPicked ? (
                          <button className="ss-link" style={{ fontSize: 12, color: 'var(--ss-accent)' }} onClick={(e) => { e.stopPropagation(); setMapPick(null); }}><i className="pi pi-times" aria-hidden="true" /> picked</button>
                        ) : isCounterpart ? (
                          <button className="ss-btn primary" disabled={mapping} style={{ padding: '2px 8px', fontSize: 11 }} onClick={(e) => { e.stopPropagation(); mapWith(r); }}><i className="pi pi-arrow-right-arrow-left" aria-hidden="true" /> map here</button>
                        ) : (!mapPick || mapPick.table === r.tableName) ? (
                          <button className="ss-link" title="Pick this entity, then choose its counterpart" onClick={(e) => { e.stopPropagation(); pickForMap(r); }} style={{ fontSize: 12 }}><i className="pi pi-link" aria-hidden="true" /> map</button>
                        ) : null
                      )}
                      <button className="ss-link" title={excluded ? 'Include in merge' : 'Exclude from merge'} disabled={rowBusy} onClick={(e) => { e.stopPropagation(); toggleExclude(r); }} style={{ fontSize: 12, color: excluded ? 'var(--ss-amber)' : undefined }}>
                        {rowBusy ? <i className="pi pi-spin pi-spinner" aria-hidden="true" /> : <i className={`pi ${excluded ? 'pi-eye-slash' : 'pi-eye'}`} aria-hidden="true" />}
                        {excluded ? ' include' : ''}
                      </button>
                      <span className={`ss-badge ${chip.cls}`}>{chip.label}</span>
                    </div>
                    {isOpen && hasDiff && (
                      <div className="ss-erow-body">
                        <FieldDiffSplit source={r.source} target={r.target} sourceRefs={r.sourceRefs} targetRefs={r.targetRefs} srcInstanceId={sourceInstanceId} tgtInstanceId={targetInstanceId} onOpenRef={openRef} />
                        <div style={{ marginTop: 8, borderTop: '1px solid var(--ss-border-soft)', paddingTop: 8 }}>
                          <ReferencedBy referrers={referrers} />
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          );
        })
      )}

      <ExclusionsManager
        visible={showExclusions}
        onHide={() => setShowExclusions(false)}
        mergeRequestId={mergeRequestId}
        onExclusionsChanged={() => onSaved && onSaved()}
      />

      {mapperTable && (
        <ManualCollectionMapper
          visible={!!mapperTable}
          onHide={() => setMapperTable(null)}
          mergeRequestId={mergeRequestId}
          collectionTypesData={{ [groups.find((g) => g.tableName === mapperTable)?.uid || mapperTable]: (data.collectionTypes?.[groups.find((g) => g.tableName === mapperTable)?.uid || ''] || []) }}
          allMergeData={data}
          fixedTable={mapperTable}
          onSaved={onSaved}
        />
      )}

      <Dialog visible={refStack.length > 0} style={{ width: '88vw', maxWidth: 1100 }} onHide={() => setRefStack([])} dismissableMask
        header={
          <div className="ss-card-row" style={{ gap: 6, flexWrap: 'wrap' }}>
            <i className="pi pi-sitemap" aria-hidden="true" />
            {refStack.map((s, i) => (
              <React.Fragment key={i}>
                {i > 0 && <i className="pi pi-angle-right ss-dim" style={{ fontSize: 11 }} aria-hidden="true" />}
                <button className="ss-link" style={{ fontWeight: i === refStack.length - 1 ? 500 : 400 }}
                  onClick={() => setRefStack((p) => p.slice(0, i + 1))}>{s.label}</button>
              </React.Fragment>
            ))}
            {refStack.length > 0 && (
              <>
                <span className="ss-badge">{refStack[refStack.length - 1].node.type}</span>
                <span className={`ss-badge ${refStack[refStack.length - 1].node.state === 'IDENTICAL' ? 'success' : refStack[refStack.length - 1].node.state === 'DIFFERENT' ? 'warn' : ''}`}>
                  {refStack[refStack.length - 1].node.state.toLowerCase().replace(/_/g, ' ')}
                </span>
              </>
            )}
          </div>
        }>
        {refStack.length > 0 && (() => {
          const n = refStack[refStack.length - 1].node;
          return (
            <>
              <FieldDiffSplit source={n.source} target={n.target} sourceRefs={n.sourceRefs} targetRefs={n.targetRefs}
                srcInstanceId={sourceInstanceId} tgtInstanceId={targetInstanceId} onOpenRef={openRef} />
              <div style={{ marginTop: 10, borderTop: '1px solid var(--ss-border-soft)', paddingTop: 8 }}>
                <ReferencedBy referrers={referrersFor(n.sourceDoc, n.targetDoc)} />
              </div>
            </>
          );
        })()}
      </Dialog>
    </div>
  );
};

export default MergeContentWorkspace;
