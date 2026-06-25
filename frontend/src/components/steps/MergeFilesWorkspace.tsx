import React, { useMemo, useState } from 'react';
import { Dialog } from 'primereact/dialog';
import { ProgressSpinner } from 'primereact/progressspinner';
import {
  ContentTypeComparisonResultKind,
  ContentTypeComparisonResultWithRelationships,
  ContentTypeFileComparisonResult,
  MergeRequestData,
  MergeRequestSelectionDTO,
  StrapiContentTypeKind,
  StrapiImage,
} from '../../types';
import { mediaApi, FileReferencesResponse } from '../../api/media';
import ManualCollectionMapper from './components/ManualCollectionMapper';
import ExclusionsManager from './components/ExclusionsManager';

interface Props {
  mergeRequestId: number;
  sourceInstanceId: number;
  targetInstanceId: number;
  filesData?: ContentTypeFileComparisonResult[];
  updateAllSelections: (kind: StrapiContentTypeKind, isSelected: boolean, tableName?: string, documentIds?: string[], selectAllKind?: ContentTypeComparisonResultKind) => Promise<boolean>;
  selections: MergeRequestSelectionDTO[];
  allMergeData: MergeRequestData;
  onSaved?: (data?: MergeRequestData) => void | Promise<void>;
}

const rawUrl = (instanceId: number, fileId: number) => `/api/instances/${instanceId}/media/file/raw?fileId=${fileId}`;
const isPdfImg = (img?: StrapiImage | null) => img?.metadata?.mime === 'application/pdf' || (img?.metadata?.ext || '').toLowerCase() === '.pdf';

// Proxied preview on a light tile (so SVG/PDF/any format load via the tool, not the CDN).
const Preview: React.FC<{ instanceId: number; img?: StrapiImage | null; h: number; w?: number; onClick?: () => void }> = ({ instanceId, img, h, w, onClick }) => {
  const isImg = img?.metadata?.mime?.startsWith('image');
  const url = img ? rawUrl(instanceId, img.metadata.id) : '';
  return (
    <div onClick={onClick} style={{ height: h, width: w, borderRadius: 6, background: '#f3f4f6', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', flex: w ? 'none' : undefined, cursor: onClick ? 'zoom-in' : 'default' }}>
      {img && isImg ? (
        <img src={url} alt={img.metadata.name} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }}
          onError={(e) => { const t = e.target as HTMLImageElement; t.style.display = 'none'; (t.nextElementSibling as HTMLElement)?.style.setProperty('display', 'flex'); }} />
      ) : null}
      <span style={{ display: img && isImg ? 'none' : 'flex', flexDirection: 'column', alignItems: 'center', gap: 3, color: '#6b7280' }}>
        <i className={isPdfImg(img) ? 'pi pi-file-pdf' : 'pi pi-file'} style={{ fontSize: Math.min(h / 2.4, 28) }} aria-hidden="true" />
        <span style={{ fontSize: 9 }}>{(img?.metadata?.ext || '').replace('.', '').toUpperCase() || 'FILE'}</span>
      </span>
    </div>
  );
};

const fmtSize = (kb?: number): string => {
  if (kb == null) return '';
  const b = kb * 1000;
  if (b < 1024) return `${Math.round(b)} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / (1024 * 1024)).toFixed(1)} MB`;
};

const stateClass = (s: string): 'add' | 'upd' | 'del' => (s === 'ONLY_IN_SOURCE' ? 'add' : s === 'ONLY_IN_TARGET' ? 'del' : 'upd');
const stateChip = (s: string): { label: string; cls: string } => {
  if (s === 'ONLY_IN_SOURCE') return { label: 'New', cls: 'success' };
  if (s === 'ONLY_IN_TARGET') return { label: 'Del', cls: 'danger' };
  if (s === 'DIFFERENT') return { label: 'Changed', cls: 'warn' };
  return { label: 'Identical', cls: '' };
};

const MergeFilesWorkspace: React.FC<Props> = ({ mergeRequestId, sourceInstanceId, targetInstanceId, filesData, updateAllSelections, selections, allMergeData, onSaved }) => {
  const [zoom, setZoom] = useState<ContentTypeFileComparisonResult | null>(null);
  const [zoomRefs, setZoomRefs] = useState<{ source: FileReferencesResponse | null; target: FileReferencesResponse | null; loading: boolean }>({ source: null, target: null, loading: false });

  const openZoom = async (f: ContentTypeFileComparisonResult) => {
    setZoom(f);
    setZoomRefs({ source: null, target: null, loading: true });
    try {
      const [s, t] = await Promise.all([
        f.sourceImage ? mediaApi.references(sourceInstanceId, f.sourceImage.metadata.id) : Promise.resolve(null),
        f.targetImage ? mediaApi.references(targetInstanceId, f.targetImage.metadata.id) : Promise.resolve(null),
      ]);
      setZoomRefs({ source: s, target: t, loading: false });
    } catch { setZoomRefs({ source: null, target: null, loading: false }); }
  };
  const [search, setSearch] = useState('');
  const [showIdentical, setShowIdentical] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(new Set());
  const [dupOnly, setDupOnly] = useState(false);
  const [activeStates, setActiveStates] = useState<Set<string>>(new Set(['ONLY_IN_SOURCE', 'DIFFERENT', 'ONLY_IN_TARGET']));
  const [showExclusions, setShowExclusions] = useState(false);
  const [showMapper, setShowMapper] = useState(false);

  const selectedSet = useMemo(() => {
    const s = new Set<string>();
    selections.filter((sel) => sel.tableName === 'files').forEach((sel) => (sel.selections || []).forEach((x: any) => s.add(x.documentId)));
    return s;
  }, [selections]);

  const files = filesData || [];
  const counts = useMemo(() => {
    const c: Record<string, number> = { ONLY_IN_SOURCE: 0, DIFFERENT: 0, ONLY_IN_TARGET: 0, IDENTICAL: 0 };
    files.forEach((f) => { c[f.compareState] = (c[f.compareState] || 0) + 1; });
    return c;
  }, [files]);
  const total = counts.ONLY_IN_SOURCE + counts.DIFFERENT + counts.ONLY_IN_TARGET;

  const nameOf = (f: ContentTypeFileComparisonResult) =>
    (f.sourceImage?.metadata?.name || f.targetImage?.metadata?.name || f.id).toLowerCase();
  const folderOf = (f: ContentTypeFileComparisonResult) =>
    f.sourceImage?.metadata?.folder || f.targetImage?.metadata?.folder || '/';

  // Group by folder (readable path). Within a folder, sort by name so duplicate names sit adjacent,
  // and flag how many entries share each (folder, name) so duplicate clusters are obvious.
  const groups = useMemo(() => {
    const q = search.trim().toLowerCase();
    const visible = files.filter((f) => {
      const s = f.compareState;
      if (s === 'IDENTICAL' && !showIdentical) return false;
      if (s !== 'IDENTICAL' && !activeStates.has(s)) return false;
      if (q && !(nameOf(f).includes(q) || folderOf(f).toLowerCase().includes(q))) return false;
      return true;
    });
    const byFolder = new Map<string, ContentTypeFileComparisonResult[]>();
    for (const f of visible) {
      const k = folderOf(f);
      (byFolder.get(k) ?? byFolder.set(k, []).get(k)!).push(f);
    }
    let folders = [...byFolder.entries()].map(([folder, rows]) => {
      const dupCount = new Map<string, number>();
      rows.forEach((r) => dupCount.set(nameOf(r), (dupCount.get(nameOf(r)) || 0) + 1));
      const sorted = [...rows].sort((a, b) => nameOf(a).localeCompare(nameOf(b)) || a.compareState.localeCompare(b.compareState));
      const counts = { ONLY_IN_SOURCE: 0, DIFFERENT: 0, ONLY_IN_TARGET: 0 } as Record<string, number>;
      rows.forEach((r) => { if (r.compareState in counts) counts[r.compareState]++; });
      const hasDup = [...dupCount.values()].some((n) => n > 1);
      return { folder, rows: sorted, dupCount, counts, hasDup };
    });
    if (dupOnly) {
      folders = folders
        .map((g) => ({ ...g, rows: g.rows.filter((r) => (g.dupCount.get(nameOf(r)) || 0) > 1) }))
        .filter((g) => g.rows.length > 0);
    }
    return folders.filter((g) => g.rows.length > 0).sort((a, b) => a.folder.localeCompare(b.folder));
  }, [files, search, showIdentical, activeStates, dupOnly]);

  const dupTotal = useMemo(() => {
    const byKey = new Map<string, number>();
    files.forEach((f) => {
      if (f.compareState === 'IDENTICAL') return;
      const k = `${folderOf(f)}|${nameOf(f)}`;
      byKey.set(k, (byKey.get(k) || 0) + 1);
    });
    return [...byKey.values()].filter((n) => n > 1).reduce((a, n) => a + n, 0);
  }, [files]);

  const toggleState = (s: string) =>
    setActiveStates((prev) => { const n = new Set(prev); n.has(s) ? n.delete(s) : n.add(s); return n; });
  const toggleOpen = (k: string) =>
    setOpen((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });
  const toggleFolder = (k: string) =>
    setCollapsedFolders((prev) => { const n = new Set(prev); n.has(k) ? n.delete(k) : n.add(k); return n; });

  const Pill: React.FC<{ s: string; cls: 'add' | 'upd' | 'del'; label: string }> = ({ s, cls, label }) => (
    <button className={`ss-pill ${cls}${activeStates.has(s) ? ' active' : ''}`} onClick={() => toggleState(s)}>{label} {counts[s] || 0}</button>
  );

  const filesAsCollection: Record<string, ContentTypeComparisonResultWithRelationships[]> = useMemo(() => ({ files: [] }), []);

  return (
    <div className="ss-review" style={{ border: 'none' }}>
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
        {dupTotal > 0 && (
          <button className={`ss-pill warn${dupOnly ? ' active' : ''}`} onClick={() => setDupOnly((v) => !v)} title="Files that share name+folder on a side — need manual review">
            <i className="pi pi-clone" aria-hidden="true" /> duplicates {dupTotal}
          </button>
        )}
        <span style={{ marginLeft: 'auto' }} />
        <button className="ss-btn subtle" onClick={() => setShowMapper(true)}><i className="pi pi-link" aria-hidden="true" /> Mapping</button>
        <button className="ss-btn subtle" onClick={() => setShowExclusions(true)}><i className="pi pi-ban" aria-hidden="true" /> Exclusions</button>
      </div>

      {groups.length === 0 ? (
        <div className="ss-empty"><i className="pi pi-images" aria-hidden="true" />No files match the current filters.</div>
      ) : (
        groups.map((g) => {
          const collapsed = collapsedFolders.has(g.folder);
          const selectable = g.rows.filter((r) => r.compareState !== 'IDENTICAL');
          const allSelected = selectable.length > 0 && selectable.every((r) => selectedSet.has(r.id));
          return (
            <div key={g.folder}>
              <div className="ss-group-head">
                <button onClick={() => toggleFolder(g.folder)}>
                  <i className={`pi pi-chevron-${collapsed ? 'right' : 'down'}`} style={{ fontSize: 12 }} aria-hidden="true" />
                  <i className="pi pi-folder" style={{ fontSize: 13 }} aria-hidden="true" />
                  <span style={{ textTransform: 'none', letterSpacing: 0, color: 'var(--ss-text)' }}>{g.folder}</span>
                </button>
                <span className="ss-dim" style={{ fontSize: 10.5 }}>{g.rows.length} file{g.rows.length !== 1 ? 's' : ''}</span>
                {g.counts.ONLY_IN_SOURCE > 0 && <span className="ss-count add" style={{ fontSize: 10.5 }}>+{g.counts.ONLY_IN_SOURCE}</span>}
                {g.counts.DIFFERENT > 0 && <span className="ss-count upd" style={{ fontSize: 10.5 }}>~{g.counts.DIFFERENT}</span>}
                {g.counts.ONLY_IN_TARGET > 0 && <span className="ss-count del" style={{ fontSize: 10.5 }}>−{g.counts.ONLY_IN_TARGET}</span>}
                {g.hasDup && <span className="ss-badge warn" title="Duplicate names in this folder">dup</span>}
                <span style={{ marginLeft: 'auto' }} />
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, cursor: 'pointer', textTransform: 'none', letterSpacing: 0, color: 'var(--ss-text-2)' }}
                  title="Select every change in this folder">
                  <input type="checkbox" disabled={selectable.length === 0} checked={allSelected}
                    ref={(el) => { if (el) el.indeterminate = !allSelected && selectable.some((r) => selectedSet.has(r.id)); }}
                    onChange={(e) => updateAllSelections(StrapiContentTypeKind.Files, e.target.checked, 'files', selectable.map((r) => r.id))} /> select all
                </label>
              </div>

              {!collapsed && g.rows.map((f) => {
                const img = f.sourceImage || f.targetImage;
                const md = img?.metadata;
                const isSel = selectedSet.has(f.id);
                const chip = stateChip(f.compareState);
                const isOpen = open.has(f.id);
                const both = f.compareState === 'DIFFERENT' && f.sourceImage && f.targetImage;
                const dupN = g.dupCount.get(nameOf(f)) || 1;
                const reprIid = f.sourceImage ? sourceInstanceId : targetInstanceId;
                return (
                  <div key={f.id} className={`ss-erow${isOpen ? ' open' : ''}`}>
                    <div className="ss-erow-head" onClick={() => both && toggleOpen(f.id)}>
                      <input type="checkbox" checked={isSel} disabled={f.compareState === 'IDENTICAL'}
                        onClick={(e) => e.stopPropagation()} onChange={(e) => updateAllSelections(StrapiContentTypeKind.Files, e.target.checked, 'files', [f.id])} style={{ width: 14, height: 14 }} />
                      <i className={`pi pi-chevron-${isOpen ? 'down' : 'right'} ss-dim`} style={{ fontSize: 12, visibility: both ? 'visible' : 'hidden' }} aria-hidden="true" />
                      <span className={`ss-state-dot ${stateClass(f.compareState)}`} />
                      <span onClick={(e) => e.stopPropagation()} style={{ display: 'inline-flex' }}>
                        <Preview instanceId={reprIid} img={img} h={40} w={54} onClick={() => openZoom(f)} />
                      </span>
                      <span className="ss-erow-name" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 260 }}>{md?.name || f.id}</span>
                      {dupN > 1 && <span className="ss-badge warn" style={{ fontSize: 9.5 }} title="Same name appears multiple times in this folder — ambiguous, needs manual mapping">dup ×{dupN}</span>}
                      <span className="ss-erow-summary">{fmtSize(md?.size as number)} · {md?.mime || 'file'}</span>
                      <span style={{ marginLeft: 'auto' }} />
                      <button className="ss-link" onClick={(e) => { e.stopPropagation(); openZoom(f); }} title="Preview big & references">
                        <i className="pi pi-window-maximize" aria-hidden="true" /> view
                      </button>
                      <span className={`ss-badge ${chip.cls}`}>{chip.label}</span>
                    </div>
                    {isOpen && both && (
                      <div className="ss-erow-body">
                        <div className="ss-diff">
                          <div className="h">field</div><div className="h src">source</div><div className="h">target</div>
                          {(['name', 'size', 'mime', 'folder'] as const).map((field) => {
                            const sv = field === 'size' ? fmtSize(f.sourceImage?.metadata?.size as number) : (f.sourceImage?.metadata as any)?.[field];
                            const tv = field === 'size' ? fmtSize(f.targetImage?.metadata?.size as number) : (f.targetImage?.metadata as any)?.[field];
                            const changed = String(sv ?? '') !== String(tv ?? '');
                            return (
                              <React.Fragment key={field}>
                                <div className="c k">{field}</div>
                                <div className={`c ${changed ? 'add' : ''}`}>{sv ?? '—'}</div>
                                <div className={`c ${changed ? 'del' : ''}`}>{tv ?? '—'}</div>
                              </React.Fragment>
                            );
                          })}
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

      <ManualCollectionMapper
        visible={showMapper}
        onHide={() => setShowMapper(false)}
        mergeRequestId={mergeRequestId}
        collectionTypesData={filesAsCollection}
        allMergeData={allMergeData}
        fixedTable={'files'}
        onSaved={onSaved}
      />
      <ExclusionsManager
        visible={showExclusions}
        onHide={() => setShowExclusions(false)}
        mergeRequestId={mergeRequestId}
        onExclusionsChanged={() => onSaved && onSaved()}
      />

      <Dialog header={zoom ? (zoom.sourceImage?.metadata.name || zoom.targetImage?.metadata.name) : ''}
        visible={zoom != null} style={{ width: '92vw', maxWidth: 1100 }} onHide={() => setZoom(null)} dismissableMask>
        {zoom && (() => {
          const sides: Array<{ key: 'source' | 'target'; label: string; iid: number; img?: StrapiImage | null; refs: FileReferencesResponse | null }> = [
            { key: 'source', label: 'Source', iid: sourceInstanceId, img: zoom.sourceImage, refs: zoomRefs.source },
            { key: 'target', label: 'Target', iid: targetInstanceId, img: zoom.targetImage, refs: zoomRefs.target },
          ].filter((s) => s.img) as any;
          return (
            <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
              {sides.map((s) => (
                <div key={s.key} style={{ flex: '1 1 320px', minWidth: 300, border: '1px solid var(--ss-border)', borderRadius: 8, padding: 10 }}>
                  <div className="ss-card-row" style={{ marginBottom: 8 }}>
                    <span className={`ss-badge ${s.key === 'source' ? 'info' : ''}`}>{s.label}</span>
                    <span className="ss-dim" style={{ fontSize: 11 }}>#{s.img!.metadata.id} · {fmtSize(s.img!.metadata.size as number)}</span>
                    <a className="ss-link" href={rawUrl(s.iid, s.img!.metadata.id)} target="_blank" rel="noreferrer" style={{ marginLeft: 'auto', fontSize: 11 }}><i className="pi pi-external-link" aria-hidden="true" /> open</a>
                  </div>
                  {isPdfImg(s.img) ? (
                    <iframe title={`${s.key}-pdf`} src={rawUrl(s.iid, s.img!.metadata.id)} style={{ width: '100%', height: 360, border: 'none', borderRadius: 6, background: '#fff' }} />
                  ) : (
                    <Preview instanceId={s.iid} img={s.img} h={300} />
                  )}
                  <div className="ss-dim" style={{ fontSize: 11, marginTop: 6 }}>{s.img!.metadata.folder || '/'} · {s.img!.metadata.mime}</div>
                  <div style={{ marginTop: 8, borderTop: '1px solid var(--ss-border-soft)', paddingTop: 8 }}>
                    <div className="ss-dim" style={{ fontSize: 11, marginBottom: 4 }}>Used by {zoomRefs.loading ? '…' : `(${s.refs?.total ?? 0})`}</div>
                    {zoomRefs.loading ? (
                      <ProgressSpinner style={{ width: 24, height: 24 }} />
                    ) : s.refs && s.refs.references.length > 0 ? (
                      Object.entries(s.refs.references.reduce((acc, r) => {
                        const k = `${r.relatedType}${r.field ? ` · ${r.field}` : ''}`;
                        (acc[k] = acc[k] || []).push(r); return acc;
                      }, {} as Record<string, typeof s.refs.references>)).map(([k, list]) => (
                        <div key={k} style={{ marginBottom: 5 }}>
                          <div style={{ fontSize: 11.5, fontWeight: 500 }}>
                            {k} <span className={`ss-badge ${list[0].isComponent ? 'warn' : 'info'}`} style={{ fontSize: 9 }}>{list[0].isComponent ? 'component' : 'type'}</span> <span className="ss-dim">×{list.length}</span>
                          </div>
                          {list.slice(0, 6).map((r, i) => (
                            <div key={i} className="ss-dim" style={{ fontSize: 11, paddingLeft: 8 }}>{r.label || `id ${r.relatedId}`}{r.documentId ? ` · ${r.documentId}` : ''}</div>
                          ))}
                        </div>
                      ))
                    ) : (
                      <div className="ss-dim" style={{ fontSize: 11 }}>No references.</div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          );
        })()}
      </Dialog>
    </div>
  );
};

export default MergeFilesWorkspace;
