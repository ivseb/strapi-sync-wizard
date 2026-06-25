import React, { useMemo, useRef, useState } from 'react';
import { Dropdown } from 'primereact/dropdown';
import { Toast } from 'primereact/toast';
import { Dialog } from 'primereact/dialog';
import { ProgressSpinner } from 'primereact/progressspinner';
import { confirmDialog, ConfirmDialog } from 'primereact/confirmdialog';
import { useInstances } from '../api/instances';
import {
  contentApi,
  ContentDuplicatesSummary,
  ContentTableSummary,
  ContentTableDuplicatesReport,
  ContentDupGroup,
  DupEntryInfo,
  ContentReferencesResponse,
  ContentDedupReport,
} from '../api/content';
import { apiErrorMessage } from '../api/http';

const ContentCleanup: React.FC = () => {
  const toast = useRef<Toast>(null);
  const { data: instances = [] } = useInstances();
  const realInstances = useMemo(() => (instances as any[]).filter((i) => !i.isVirtual), [instances]);

  const [instanceId, setInstanceId] = useState<number | null>(null);
  const [summary, setSummary] = useState<ContentDuplicatesSummary | null>(null);
  const [scanning, setScanning] = useState(false);

  const [openTable, setOpenTable] = useState<string | null>(null);
  const [tableReport, setTableReport] = useState<ContentTableDuplicatesReport | null>(null);
  const [loadingTable, setLoadingTable] = useState(false);
  const [tab, setTab] = useState<'CERTAIN' | 'SUSPECT'>('CERTAIN');
  const [canonicalByGroup, setCanonicalByGroup] = useState<Record<string, number>>({});
  const [included, setIncluded] = useState<Set<string>>(new Set());

  const [applying, setApplying] = useState(false);
  const [refsState, setRefsState] = useState<{ entry: DupEntryInfo; loading: boolean; data: ContentReferencesResponse | null } | null>(null);
  const [planReport, setPlanReport] = useState<ContentDedupReport | null>(null);

  const scanAll = async () => {
    if (instanceId == null) return;
    setScanning(true);
    setSummary(null);
    setOpenTable(null);
    setTableReport(null);
    try {
      const r = await contentApi.scanAll(instanceId);
      setSummary(r);
    } catch (e) {
      toast.current?.show({ severity: 'error', summary: 'Scan failed', detail: apiErrorMessage(e), life: 6000 });
    } finally {
      setScanning(false);
    }
  };

  const openTableDetail = async (t: ContentTableSummary) => {
    if (instanceId == null) return;
    setOpenTable(t.table);
    setTableReport(null);
    setPlanReport(null);
    setLoadingTable(true);
    try {
      const r = await contentApi.scanTable(instanceId, t.table);
      setTableReport(r);
      const canon: Record<string, number> = {};
      [...r.certainGroups, ...r.suspectGroups].forEach((g) => (canon[g.key] = g.suggestedCanonicalId));
      setCanonicalByGroup(canon);
      setIncluded(new Set(r.certainGroups.map((g) => g.key)));
      setTab(r.certainGroups.length ? 'CERTAIN' : 'SUSPECT');
    } catch (e) {
      toast.current?.show({ severity: 'error', summary: 'Load failed', detail: apiErrorMessage(e), life: 6000 });
    } finally {
      setLoadingTable(false);
    }
  };

  const groups = tableReport ? (tab === 'CERTAIN' ? tableReport.certainGroups : tableReport.suspectGroups) : [];

  const openRefs = async (entry: DupEntryInfo) => {
    if (instanceId == null || !tableReport) return;
    setRefsState({ entry, loading: true, data: null });
    try {
      const data = await contentApi.references(instanceId, tableReport.table, entry.id);
      setRefsState({ entry, loading: false, data });
    } catch (e) {
      setRefsState(null);
      toast.current?.show({ severity: 'error', summary: 'References failed', detail: apiErrorMessage(e), life: 5000 });
    }
  };

  const RefBadge: React.FC<{ e: DupEntryInfo }> = ({ e }) =>
    e.refs > 0 ? (
      <button className="ss-badge info" style={{ cursor: 'pointer', border: 'none' }} title="Click to see what references it"
        onClick={(ev) => { ev.preventDefault(); ev.stopPropagation(); openRefs(e); }}>
        {e.refs} ref{e.refs !== 1 ? 's' : ''}
      </button>
    ) : (
      <span className="ss-badge">0 refs</span>
    );

  const plan = useMemo(() => {
    if (!tableReport) return { groups: 0, copies: 0, refs: 0 };
    let copies = 0, refs = 0, groupsN = 0;
    [...tableReport.certainGroups, ...tableReport.suspectGroups].forEach((g) => {
      if (!included.has(g.key)) return;
      const canon = canonicalByGroup[g.key];
      const redundant = g.entries.filter((e) => e.id !== canon);
      if (redundant.length === 0) return;
      groupsN++; copies += redundant.length; refs += redundant.reduce((a, e) => a + e.refs, 0);
    });
    return { groups: groupsN, copies, refs };
  }, [tableReport, included, canonicalByGroup]);

  const buildPayload = () => {
    if (!tableReport) return [];
    const all = [...tableReport.certainGroups, ...tableReport.suspectGroups];
    const groupsReq = all
      .filter((g) => included.has(g.key))
      .map((g) => ({ canonicalId: canonicalByGroup[g.key], redundantIds: g.entries.filter((e) => e.id !== canonicalByGroup[g.key]).map((e) => e.id) }))
      .filter((g) => g.redundantIds.length > 0);
    return [{ table: tableReport.table, groups: groupsReq }];
  };

  const dryRun = async () => {
    if (instanceId == null) return;
    try {
      const r = await contentApi.dedup(instanceId, buildPayload(), false);
      setPlanReport(r);
      toast.current?.show({ severity: 'info', summary: 'Dry-run', detail: `${r.groupsProcessed} groups · ${r.refsRepointed} refs would move · ${r.entriesDeleted} entries would be removed`, life: 6000 });
    } catch (e) {
      toast.current?.show({ severity: 'error', summary: 'Dry-run failed', detail: apiErrorMessage(e), life: 6000 });
    }
  };

  const apply = () => {
    if (instanceId == null) return;
    confirmDialog({
      header: 'Apply content deduplication',
      message:
        `This will repoint ${plan.refs} references and permanently delete ${plan.copies} entries across ${plan.groups} groups on this instance.` +
        '\n\n⚠ This modifies real content data. A backup schema is created first, but you should TAKE A SNAPSHOT before applying. Run the Dry-run and review the plan first.',
      icon: 'pi pi-exclamation-triangle',
      acceptClassName: 'p-button-danger',
      acceptLabel: 'Apply (delete entries)',
      accept: async () => {
        setApplying(true);
        try {
          const r = await contentApi.dedup(instanceId, buildPayload(), true);
          setPlanReport(r);
          toast.current?.show({ severity: 'success', summary: 'Deduplicated', detail: `${r.refsRepointed} refs repointed · ${r.entriesDeleted} entries removed · backup ${r.backupSchema}`, life: 10000 });
          if (openTable) {
            const t = summary?.tables.find((x) => x.table === openTable);
            if (t) await openTableDetail(t);
          }
          await scanAll();
        } catch (e) {
          toast.current?.show({ severity: 'error', summary: 'Apply failed', detail: apiErrorMessage(e), life: 7000 });
        } finally {
          setApplying(false);
        }
      },
    });
  };

  const toggleGroup = (k: string) => setIncluded((p) => { const n = new Set(p); n.has(k) ? n.delete(k) : n.add(k); return n; });

  return (
    <>
      <Toast ref={toast} />
      <ConfirmDialog />

      <div className="ss-page-head">
        <h1>Content cleanup</h1>
        <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
        <Dropdown value={instanceId} options={realInstances} optionLabel="name" optionValue="id"
          onChange={(e) => { setInstanceId(e.value); setSummary(null); setOpenTable(null); setTableReport(null); }} placeholder="Select instance" style={{ minWidth: 220 }} />
        <button className="ss-btn primary" disabled={instanceId == null || scanning} onClick={scanAll}>
          <i className="pi pi-search" aria-hidden="true" /> {scanning ? 'Scanning…' : 'Scan duplicates'}
        </button>
      </div>

      <p className="ss-muted" style={{ marginTop: 0 }}>
        Finds collection entries that are logically identical (<strong>certain</strong>: same content fingerprint, different documentId)
        or share a natural key but differ (<strong>suspect</strong>) within one instance, shows how many other entries reference each copy,
        and lets you converge everything onto a chosen entry. Always <strong>dry-run</strong> first, and take a snapshot before applying.
      </p>

      {scanning ? (
        <div className="flex justify-content-center p-5"><ProgressSpinner /></div>
      ) : !summary ? (
        <div className="ss-empty"><i className="pi pi-clone" aria-hidden="true" />Pick an instance and scan to find duplicate content.</div>
      ) : summary.tables.length === 0 ? (
        <div className="ss-empty"><i className="pi pi-check-circle" aria-hidden="true" />No duplicate content across {summary.tablesScanned} collection types.</div>
      ) : (
        <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start', flexWrap: 'wrap' }}>
          {/* Tables list */}
          <div className="ss-list" style={{ flex: '0 0 320px', minWidth: 280 }}>
            <div className="ss-dim" style={{ fontSize: 12, marginBottom: 6 }}>
              {summary.tables.length} collection{summary.tables.length !== 1 ? 's' : ''} with duplicates · {summary.tablesScanned} scanned
            </div>
            {summary.tables.map((t) => (
              <div key={t.table} className={`ss-card${openTable === t.table ? '' : ''}`}
                style={{ cursor: 'pointer', padding: '10px 12px', borderColor: openTable === t.table ? 'var(--ss-accent, #6ea8fe)' : 'var(--ss-border)' }}
                onClick={() => openTableDetail(t)}>
                <div className="ss-card-row" style={{ gap: 8 }}>
                  <i className="pi pi-database" style={{ fontSize: 13, color: 'var(--ss-text-2)' }} aria-hidden="true" />
                  <span className="ss-card-title" style={{ fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }}>{t.displayName || t.table}</span>
                  <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                  <span className="ss-badge warn">{t.removableEntries} dup</span>
                </div>
                <div className="ss-dim" style={{ fontSize: 10.5, marginTop: 4 }}>
                  {t.table} · {t.totalEntries} entries · {t.certainGroups} certain / {t.suspectGroups} suspect
                </div>
              </div>
            ))}
          </div>

          {/* Detail panel */}
          <div style={{ flex: '1 1 520px', minWidth: 420 }}>
            {!openTable ? (
              <div className="ss-empty"><i className="pi pi-arrow-left" aria-hidden="true" />Select a collection to review its duplicate groups.</div>
            ) : loadingTable || !tableReport ? (
              <div className="flex justify-content-center p-5"><ProgressSpinner /></div>
            ) : (
              <>
                <div className="ss-tabs">
                  <button className={`ss-tab${tab === 'CERTAIN' ? ' active' : ''}`} onClick={() => setTab('CERTAIN')}>Certain (identical) <span className="ss-tab-n">{tableReport.certainGroups.length}</span></button>
                  <button className={`ss-tab${tab === 'SUSPECT' ? ' active' : ''}`} onClick={() => setTab('SUSPECT')}>Suspect (same key) <span className="ss-tab-n">{tableReport.suspectGroups.length}</span></button>
                  <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                  <span className="ss-dim" style={{ fontSize: 12, paddingBottom: 6 }}>{tableReport.displayName} · {tableReport.totalEntries} entries · {tableReport.removableEntries} removable</span>
                </div>

                {groups.length === 0 ? (
                  <div className="ss-empty"><i className="pi pi-check-circle" aria-hidden="true" />No {tab.toLowerCase()} duplicates.</div>
                ) : (
                  <div className="ss-list">
                    {groups.map((g: ContentDupGroup) => {
                      const canon = canonicalByGroup[g.key];
                      const inc = included.has(g.key);
                      return (
                        <div key={g.key} className="ss-card" style={{ opacity: inc ? 1 : 0.55 }}>
                          <div className="ss-card-row">
                            <input type="checkbox" checked={inc} onChange={() => toggleGroup(g.key)} style={{ width: 15, height: 15 }} />
                            <span className="ss-card-title" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 320 }}>{g.label}</span>
                            <span className={`ss-badge ${g.kind === 'CERTAIN' ? 'success' : 'warn'}`}>{g.kind === 'CERTAIN' ? 'identical' : 'suspect'}</span>
                            <span className="ss-dim" style={{ fontSize: 11 }}>{g.entries.length} copies · {g.totalRefs} refs</span>
                          </div>

                          <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
                            {g.entries.map((e: DupEntryInfo) => {
                              const isCanon = e.id === canon;
                              return (
                                <label key={e.id}
                                  style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 8px', borderRadius: 6, cursor: 'pointer', border: `1px solid ${isCanon ? 'var(--ss-green)' : 'var(--ss-border)'}`, background: isCanon ? 'var(--ss-green-bg)' : 'var(--ss-surface-2)' }}>
                                  <input type="radio" name={`canon-${g.key}`} checked={isCanon} onChange={() => setCanonicalByGroup((p) => ({ ...p, [g.key]: e.id }))} />
                                  <span className={`ss-badge ${isCanon ? 'success' : 'danger'}`} style={{ minWidth: 56, textAlign: 'center' }}>{isCanon ? 'keep' : 'remove'}</span>
                                  <span style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 240 }}>{e.label}</span>
                                  <span className="ss-dim" style={{ fontSize: 10.5 }}>#{e.id}{e.locale ? ` · ${e.locale}` : ''}</span>
                                  <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                                  <RefBadge e={e} />
                                </label>
                              );
                            })}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}

                {planReport && (
                  <div className="ss-card" style={{ marginTop: 12 }}>
                    <div className="ss-card-row" style={{ gap: 8 }}>
                      <i className={planReport.applied ? 'pi pi-check-circle' : 'pi pi-eye'} aria-hidden="true" />
                      <span style={{ fontSize: 13, fontWeight: 500 }}>{planReport.applied ? 'Applied' : 'Dry-run plan'}</span>
                      <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                      <span className="ss-dim" style={{ fontSize: 11 }}>
                        {planReport.groupsProcessed} groups · {planReport.refsRepointed} refs · {planReport.collisionsRemoved} collisions · {planReport.entriesDeleted} entries
                        {planReport.backupSchema ? ` · backup ${planReport.backupSchema}` : ''}
                      </span>
                    </div>
                    <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 3 }}>
                      {planReport.actions.map((a, i) => (
                        <div key={i} className="ss-dim" style={{ fontSize: 11.5 }}>
                          keep #{a.canonicalId} ← remove {a.redundantIds.map((r) => `#${r}`).join(', ')} · repoint {a.lnkRepoints} lnk rows · {a.collisionsRemoved} collisions · {a.cmpsDeleted} cmps · {a.ownLnkRowsDeleted} own-lnk
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <div className="ss-actionbar" style={{ marginTop: 14, borderRadius: 8, border: '1px solid var(--ss-border)' }}>
                  <span className="ss-muted" style={{ fontSize: 12 }}>
                    <strong style={{ color: 'var(--ss-text)', fontWeight: 500 }}>{plan.groups}</strong> groups · {plan.copies} entries to remove · {plan.refs} refs to repoint
                  </span>
                  <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                  <button className="ss-btn subtle" disabled={plan.groups === 0 || applying} onClick={dryRun}><i className="pi pi-eye" aria-hidden="true" /> Dry-run</button>
                  <button className="ss-btn primary" disabled={plan.groups === 0 || applying} onClick={apply}><i className="pi pi-check" aria-hidden="true" /> {applying ? 'Applying…' : 'Apply'}</button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      <Dialog header={refsState ? `Referenced by — ${refsState.entry.label} (#${refsState.entry.id})` : ''}
        visible={refsState != null} style={{ width: '70vw', maxWidth: 760 }} onHide={() => setRefsState(null)} dismissableMask>
        {refsState?.loading ? (
          <div className="flex justify-content-center p-4"><ProgressSpinner style={{ width: 40, height: 40 }} /></div>
        ) : refsState?.data ? (
          refsState.data.references.length === 0 ? (
            <div className="ss-empty"><i className="pi pi-info-circle" aria-hidden="true" />Nothing references this entry.</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div className="ss-dim" style={{ fontSize: 12, marginBottom: 4 }}>{refsState.data.total} reference{refsState.data.total !== 1 ? 's' : ''}</div>
              {Object.entries(
                refsState.data.references.reduce((acc, r) => {
                  const k = `${r.ownerTable}${r.field ? ` · ${r.field}` : ''}`;
                  (acc[k] = acc[k] || []).push(r);
                  return acc;
                }, {} as Record<string, ContentReferencesResponse['references']>)
              ).map(([k, list]) => (
                <div key={k} className="ss-card" style={{ padding: '8px 12px' }}>
                  <div className="ss-card-row" style={{ gap: 8 }}>
                    <i className="pi pi-database" style={{ fontSize: 13, color: 'var(--ss-text-2)' }} aria-hidden="true" />
                    <span style={{ fontSize: 12.5, fontWeight: 500 }}>{k}</span>
                    <span className="ss-dim" style={{ fontSize: 11, marginLeft: 'auto' }}>{list.length}×</span>
                  </div>
                  <div style={{ marginTop: 5, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    {list.map((r, i) => (
                      <div key={i} className="ss-dim" style={{ fontSize: 11.5, paddingLeft: 21 }}>
                        {r.ownerLabel ? <span style={{ color: 'var(--ss-text)' }}>{r.ownerLabel}</span> : <span className="ss-dim">id {r.ownerId}</span>}
                        {r.ownerDocumentId && <span className="ss-dim"> · {r.ownerDocumentId}</span>}
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

export default ContentCleanup;
