import React, { useMemo, useRef, useState } from 'react';
import { Dropdown } from 'primereact/dropdown';
import { Toast } from 'primereact/toast';
import { Dialog } from 'primereact/dialog';
import { ProgressSpinner } from 'primereact/progressspinner';
import { confirmDialog, ConfirmDialog } from 'primereact/confirmdialog';
import { useInstances } from '../api/instances';
import { mediaApi, DupFileInfo, DupGroup, MediaDuplicatesReport, FileReferencesResponse } from '../api/media';
import { apiErrorMessage } from '../api/http';

const fmtSize = (b?: number): string => {
  if (b == null) return '';
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / (1024 * 1024)).toFixed(1)} MB`;
};

const rawUrl = (instanceId: number, fileId: number) => `/api/instances/${instanceId}/media/file/raw?fileId=${fileId}`;
const isImage = (f: DupFileInfo) => (f.mime || '').startsWith('image');
const isPdf = (f: DupFileInfo) => f.mime === 'application/pdf' || (f.ext || '').toLowerCase() === '.pdf';

// Preview on a light tile so transparent SVGs and light icons stay visible on the dark UI.
const Preview: React.FC<{ instanceId: number; file: DupFileInfo; h: number; onClick?: () => void }> = ({ instanceId, file, h, onClick }) => {
  const url = rawUrl(instanceId, file.id);
  return (
    <div onClick={onClick}
      style={{ height: h, borderRadius: 6, background: '#f3f4f6', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', cursor: onClick ? 'zoom-in' : 'default', position: 'relative' }}>
      {isImage(file) ? (
        <img src={url} alt={file.name} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }}
          onError={(e) => { const t = e.target as HTMLImageElement; t.style.display = 'none'; (t.nextElementSibling as HTMLElement)?.style.setProperty('display', 'flex'); }} />
      ) : null}
      <span style={{ display: isImage(file) ? 'none' : 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, color: '#6b7280' }}>
        <i className={isPdf(file) ? 'pi pi-file-pdf' : 'pi pi-file'} style={{ fontSize: Math.min(h / 2.4, 30) }} aria-hidden="true" />
        <span style={{ fontSize: 10 }}>{(file.ext || '').replace('.', '').toUpperCase() || 'FILE'}</span>
      </span>
    </div>
  );
};

const MediaCleanup: React.FC = () => {
  const toast = useRef<Toast>(null);
  const { data: instances = [] } = useInstances();
  const realInstances = useMemo(() => (instances as any[]).filter((i) => !i.isVirtual), [instances]);

  const [instanceId, setInstanceId] = useState<number | null>(null);
  const [report, setReport] = useState<MediaDuplicatesReport | null>(null);
  const [scanning, setScanning] = useState(false);
  const [applying, setApplying] = useState(false);
  const [tab, setTab] = useState<'CERTAIN' | 'SUSPECT'>('CERTAIN');
  const [canonicalByGroup, setCanonicalByGroup] = useState<Record<string, number>>({});
  const [included, setIncluded] = useState<Set<string>>(new Set());
  const [zoom, setZoom] = useState<DupGroup | null>(null);
  const [refsState, setRefsState] = useState<{ file: DupFileInfo; loading: boolean; data: FileReferencesResponse | null } | null>(null);
  const [deleteBinaries, setDeleteBinaries] = useState(false);

  const groups = report ? (tab === 'CERTAIN' ? report.certainGroups : report.suspectGroups) : [];

  const openRefs = async (file: DupFileInfo) => {
    if (instanceId == null) return;
    setRefsState({ file, loading: true, data: null });
    try {
      const data = await mediaApi.references(instanceId, file.id);
      setRefsState({ file, loading: false, data });
    } catch (e) {
      setRefsState(null);
      toast.current?.show({ severity: 'error', summary: 'References failed', detail: apiErrorMessage(e), life: 5000 });
    }
  };

  const RefBadge: React.FC<{ f: DupFileInfo }> = ({ f }) =>
    f.refs > 0 ? (
      <button className="ss-badge info" style={{ cursor: 'pointer', border: 'none' }}
        title="Click to see where it's used"
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); openRefs(f); }}>
        {f.refs} ref{f.refs !== 1 ? 's' : ''}
      </button>
    ) : (
      <span className="ss-badge">0 refs</span>
    );

  const scan = async () => {
    if (instanceId == null) return;
    setScanning(true); setReport(null);
    try {
      const r = await mediaApi.scan(instanceId);
      setReport(r);
      const canon: Record<string, number> = {};
      [...r.certainGroups, ...r.suspectGroups].forEach((g) => (canon[g.key] = g.suggestedCanonicalId));
      setCanonicalByGroup(canon);
      setIncluded(new Set(r.certainGroups.map((g) => g.key)));
    } catch (e) {
      toast.current?.show({ severity: 'error', summary: 'Scan failed', detail: apiErrorMessage(e), life: 5000 });
    } finally { setScanning(false); }
  };

  const plan = useMemo(() => {
    if (!report) return { groups: 0, copies: 0, refs: 0 };
    let copies = 0, refs = 0, groupsN = 0;
    [...report.certainGroups, ...report.suspectGroups].forEach((g) => {
      if (!included.has(g.key)) return;
      const canon = canonicalByGroup[g.key];
      const redundant = g.files.filter((f) => f.id !== canon);
      if (redundant.length === 0) return;
      groupsN++; copies += redundant.length; refs += redundant.reduce((a, f) => a + f.refs, 0);
    });
    return { groups: groupsN, copies, refs };
  }, [report, included, canonicalByGroup]);

  const buildPayload = () => {
    const all = report ? [...report.certainGroups, ...report.suspectGroups] : [];
    return all.filter((g) => included.has(g.key))
      .map((g) => ({ canonicalId: canonicalByGroup[g.key], redundantIds: g.files.filter((f) => f.id !== canonicalByGroup[g.key]).map((f) => f.id) }))
      .filter((g) => g.redundantIds.length > 0);
  };

  const dryRun = async () => {
    if (instanceId == null) return;
    try {
      const r = await mediaApi.dedup(instanceId, buildPayload(), false, deleteBinaries);
      toast.current?.show({ severity: 'info', summary: 'Dry-run', detail: `${r.groupsProcessed} groups · ${r.refsRepointed} refs would move · ${r.filesDeleted} files would be removed${deleteBinaries ? ' (incl. binaries)' : ''}`, life: 6000 });
    } catch (e) { toast.current?.show({ severity: 'error', summary: 'Dry-run failed', detail: apiErrorMessage(e), life: 5000 }); }
  };

  const apply = () => {
    if (instanceId == null) return;
    confirmDialog({
      header: 'Apply deduplication',
      message: `This will repoint ${plan.refs} references and remove ${plan.copies} files across ${plan.groups} groups on this instance.`
        + (deleteBinaries
          ? '\n\n⚠ Binary deletion is ON: the physical files will be permanently deleted from the storage provider. Any hardcoded URL to a removed binary will break. Make sure each removed copy is not referenced directly (see docs/next-direct-file-references.md).'
          : '\n\nBinaries are kept (DB rows only). Affected rows are backed up first.'),
      icon: 'pi pi-exclamation-triangle', acceptClassName: 'p-button-danger',
      accept: async () => {
        setApplying(true);
        try {
          const r = await mediaApi.dedup(instanceId, buildPayload(), true, deleteBinaries);
          const bin = r.binariesRequested ? ` · binaries ${r.binariesDeleted} deleted${r.binariesFailed ? `, ${r.binariesFailed} failed` : ''}` : '';
          toast.current?.show({ severity: 'success', summary: 'Deduplicated', detail: `${r.refsRepointed} refs repointed · ${r.filesDeleted} files removed${bin} · backup ${r.backupSchema}`, life: 9000 });
          await scan();
        } catch (e) { toast.current?.show({ severity: 'error', summary: 'Apply failed', detail: apiErrorMessage(e), life: 6000 }); }
        finally { setApplying(false); }
      },
    });
  };

  const toggleGroup = (k: string) => setIncluded((p) => { const n = new Set(p); n.has(k) ? n.delete(k) : n.add(k); return n; });

  return (
    <>
      <Toast ref={toast} />
      <ConfirmDialog />

      <div className="ss-page-head">
        <h1>Media cleanup</h1>
        <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
        <Dropdown value={instanceId} options={realInstances} optionLabel="name" optionValue="id"
          onChange={(e) => { setInstanceId(e.value); setReport(null); }} placeholder="Select instance" style={{ minWidth: 220 }} />
        <button className="ss-btn primary" disabled={instanceId == null || scanning} onClick={scan}>
          <i className="pi pi-search" aria-hidden="true" /> {scanning ? 'Scanning…' : 'Scan duplicates'}
        </button>
      </div>

      <p className="ss-muted" style={{ marginTop: 0 }}>
        Finds files that are byte-identical (<strong>certain</strong>) or share a name+folder but differ (<strong>suspect</strong>) within one
        instance, shows how many content entries reference each copy, and lets you converge everything onto a chosen file.
      </p>

      {scanning ? (
        <div className="flex justify-content-center p-5"><ProgressSpinner /></div>
      ) : !report ? (
        <div className="ss-empty"><i className="pi pi-images" aria-hidden="true" />Pick an instance and scan to find duplicate media.</div>
      ) : (
        <>
          <div className="ss-tabs">
            <button className={`ss-tab${tab === 'CERTAIN' ? ' active' : ''}`} onClick={() => setTab('CERTAIN')}>Certain (identical bytes) <span className="ss-tab-n">{report.certainGroups.length}</span></button>
            <button className={`ss-tab${tab === 'SUSPECT' ? ' active' : ''}`} onClick={() => setTab('SUSPECT')}>Suspect (same name) <span className="ss-tab-n">{report.suspectGroups.length}</span></button>
            <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
            <span className="ss-dim" style={{ fontSize: 12, paddingBottom: 6 }}>{report.totalFiles} files · {report.removableCopies} removable</span>
          </div>

          {groups.length === 0 ? (
            <div className="ss-empty"><i className="pi pi-check-circle" aria-hidden="true" />No {tab.toLowerCase()} duplicates.</div>
          ) : (
            <div className="ss-list">
              {groups.map((g: DupGroup) => {
                const canon = canonicalByGroup[g.key];
                const inc = included.has(g.key);
                return (
                  <div key={g.key} className="ss-card" style={{ opacity: inc ? 1 : 0.55 }}>
                    <div className="ss-card-row">
                      <input type="checkbox" checked={inc} onChange={() => toggleGroup(g.key)} style={{ width: 15, height: 15 }} />
                      <span className="ss-card-title" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 340 }}>{g.name}</span>
                      <span className={`ss-badge ${g.kind === 'CERTAIN' ? 'success' : 'warn'}`}>{g.kind === 'CERTAIN' ? 'identical' : 'suspect'}</span>
                      <span className="ss-dim" style={{ fontSize: 11 }}>{g.files.length} copies · {g.totalRefs} refs</span>
                      <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                      <button className="ss-link" onClick={() => setZoom(g)}><i className="pi pi-window-maximize" aria-hidden="true" /> Compare big</button>
                    </div>

                    <div style={{ marginTop: 10, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                      {g.files.map((f: DupFileInfo) => {
                        const isCanon = f.id === canon;
                        return (
                          <div key={f.id} style={{ width: 168, border: `1px solid ${isCanon ? 'var(--ss-green)' : 'var(--ss-border)'}`, borderRadius: 8, padding: 8, background: isCanon ? 'var(--ss-green-bg)' : 'var(--ss-surface-2)' }}>
                            <Preview instanceId={instanceId!} file={f} h={104} onClick={() => setZoom(g)} />
                            <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 7, cursor: 'pointer' }}>
                              <input type="radio" name={`canon-${g.key}`} checked={isCanon} onChange={() => setCanonicalByGroup((p) => ({ ...p, [g.key]: f.id }))} />
                              <span style={{ fontSize: 11.5 }}>{isCanon ? 'keep' : 'remove'}</span>
                              <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                              <RefBadge f={f} />
                            </label>
                            <div className="ss-dim" style={{ fontSize: 10.5, marginTop: 4, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>#{f.id} · {fmtSize(f.sizeBytes)}</div>
                            <div className="ss-dim" style={{ fontSize: 10.5, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{f.folder}</div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          <div className="ss-actionbar" style={{ marginTop: 14, borderRadius: 8, border: '1px solid var(--ss-border)' }}>
            <span className="ss-muted" style={{ fontSize: 12 }}>
              <strong style={{ color: 'var(--ss-text)', fontWeight: 500 }}>{plan.groups}</strong> groups · {plan.copies} files to remove · {plan.refs} refs to repoint
            </span>
            <label className="ss-chip" style={{ cursor: 'pointer', background: deleteBinaries ? 'var(--ss-red-bg)' : 'var(--ss-surface-3)', color: deleteBinaries ? 'var(--ss-red)' : 'var(--ss-text-2)' }}
              title="Permanently delete the physical files from the storage provider (S3/CDN). Off = remove DB rows only (binaries kept).">
              <input type="checkbox" checked={deleteBinaries} onChange={(e) => setDeleteBinaries(e.target.checked)} style={{ width: 13, height: 13 }} />
              <i className="pi pi-trash" aria-hidden="true" /> also delete binaries
            </label>
            <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
            <button className="ss-btn subtle" disabled={plan.groups === 0 || applying} onClick={dryRun}><i className="pi pi-eye" aria-hidden="true" /> Dry-run</button>
            <button className="ss-btn primary" disabled={plan.groups === 0 || applying} onClick={apply}><i className="pi pi-check" aria-hidden="true" /> {applying ? 'Applying…' : 'Apply'}</button>
          </div>
        </>
      )}

      <Dialog header={zoom?.name} visible={zoom != null} style={{ width: '90vw', maxWidth: 1100 }} onHide={() => setZoom(null)} dismissableMask>
        {zoom && instanceId != null && (
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
            {zoom.files.map((f) => {
              const isCanon = f.id === canonicalByGroup[zoom.key];
              return (
                <div key={f.id} style={{ flex: '1 1 300px', minWidth: 280, border: `1px solid ${isCanon ? 'var(--ss-green)' : 'var(--ss-border)'}`, borderRadius: 8, padding: 10 }}>
                  {isPdf(f) ? (
                    <iframe title={`pdf-${f.id}`} src={rawUrl(instanceId, f.id)} style={{ width: '100%', height: 380, border: 'none', borderRadius: 6, background: '#fff' }} />
                  ) : (
                    <Preview instanceId={instanceId} file={f} h={300} />
                  )}
                  <div className="ss-card-row" style={{ marginTop: 8, gap: 8 }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                      <input type="radio" name={`zcanon-${zoom.key}`} checked={isCanon} onChange={() => setCanonicalByGroup((p) => ({ ...p, [zoom.key]: f.id }))} />
                      <span className={`ss-badge ${isCanon ? 'success' : 'danger'}`}>{isCanon ? 'keep' : 'remove'}</span>
                    </label>
                    <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                    <RefBadge f={f} />
                  </div>
                  <div className="ss-dim" style={{ fontSize: 11, marginTop: 5 }}>#{f.id} · {fmtSize(f.sizeBytes)} · {f.mime}</div>
                  <div className="ss-dim" style={{ fontSize: 11 }}>{f.folder}</div>
                  <a className="ss-link" href={rawUrl(instanceId, f.id)} target="_blank" rel="noreferrer" style={{ fontSize: 11 }}><i className="pi pi-external-link" aria-hidden="true" /> open original</a>
                </div>
              );
            })}
          </div>
        )}
      </Dialog>

      <Dialog header={refsState ? `Used by — ${refsState.file.name} (#${refsState.file.id})` : ''}
        visible={refsState != null} style={{ width: '70vw', maxWidth: 760 }} onHide={() => setRefsState(null)} dismissableMask>
        {refsState?.loading ? (
          <div className="flex justify-content-center p-4"><ProgressSpinner style={{ width: 40, height: 40 }} /></div>
        ) : refsState?.data ? (
          refsState.data.references.length === 0 ? (
            <div className="ss-empty"><i className="pi pi-info-circle" aria-hidden="true" />This file is not referenced by any content.</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div className="ss-dim" style={{ fontSize: 12, marginBottom: 4 }}>{refsState.data.total} reference{refsState.data.total !== 1 ? 's' : ''}</div>
              {Object.entries(
                refsState.data.references.reduce((acc, r) => {
                  const k = `${r.relatedType}${r.field ? ` · ${r.field}` : ''}`;
                  (acc[k] = acc[k] || []).push(r); return acc;
                }, {} as Record<string, typeof refsState.data.references>)
              ).map(([k, list]) => (
                <div key={k} className="ss-card" style={{ padding: '8px 12px' }}>
                  <div className="ss-card-row" style={{ gap: 8 }}>
                    <i className={list[0].isComponent ? 'pi pi-th-large' : 'pi pi-database'} style={{ fontSize: 13, color: 'var(--ss-text-2)' }} aria-hidden="true" />
                    <span style={{ fontSize: 12.5, fontWeight: 500 }}>{k}</span>
                    <span className={`ss-badge ${list[0].isComponent ? 'warn' : 'info'}`}>{list[0].isComponent ? 'component' : 'content-type'}</span>
                    <span className="ss-dim" style={{ fontSize: 11, marginLeft: 'auto' }}>{list.length}×</span>
                  </div>
                  <div style={{ marginTop: 5, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    {list.map((r, i) => (
                      <div key={i} className="ss-dim" style={{ fontSize: 11.5, paddingLeft: 21 }}>
                        {r.label ? <span style={{ color: 'var(--ss-text)' }}>{r.label}</span> : <span className="ss-dim">id {r.relatedId}</span>}
                        {r.documentId && <span className="ss-dim"> · {r.documentId}</span>}
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )
        ) : null}
      </Dialog>
    </>
  );
};

export default MediaCleanup;
