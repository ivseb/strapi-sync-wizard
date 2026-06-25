import React, { useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from 'primereact/button';
import { Toast } from 'primereact/toast';
import { Dialog } from 'primereact/dialog';
import { InputText } from 'primereact/inputtext';
import { Dropdown } from 'primereact/dropdown';
import { InputTextarea } from 'primereact/inputtextarea';
import { Checkbox } from 'primereact/checkbox';
import { ProgressSpinner } from 'primereact/progressspinner';
import { confirmDialog, ConfirmDialog } from 'primereact/confirmdialog';
import { useMergeRequests, useCreateMergeRequest, useDeleteMergeRequest } from '../api/mergeRequests';
import { useInstances } from '../api/instances';
import { apiErrorMessage } from '../api/http';

interface MergeRequestRow {
  id: number;
  name: string;
  description: string;
  sourceInstance?: { name: string };
  targetInstance?: { name: string };
  status: string;
  schemaCompatible: boolean | null;
  updatedAt: string;
}

type BadgeCls = '' | 'info' | 'success' | 'warn' | 'danger';

const statusMeta = (status: string, schemaCompatible: boolean | null): { label: string; cls: BadgeCls; progress: number } => {
  switch (status) {
    case 'CREATED': return { label: 'Created', cls: '', progress: 8 };
    case 'SCHEMA_CHECKED': return schemaCompatible === false
      ? { label: 'Schema incompatible', cls: 'danger', progress: 20 }
      : { label: 'Schema checked', cls: 'success', progress: 22 };
    case 'COMPARED': return { label: 'Compared', cls: 'warn', progress: 45 };
    case 'MERGED_FILES': return { label: 'Files merged', cls: 'info', progress: 60 };
    case 'MERGED_SINGLES': return { label: 'Singles merged', cls: 'info', progress: 72 };
    case 'MERGED_COLLECTIONS': return { label: 'Collections merged', cls: 'info', progress: 88 };
    case 'REVIEW': return { label: 'Review', cls: 'warn', progress: 90 };
    case 'IN_PROGRESS': return { label: 'In progress', cls: 'warn', progress: 90 };
    case 'COMPLETED': return { label: 'Completed', cls: 'success', progress: 100 };
    case 'FAILED': return { label: 'Failed', cls: 'danger', progress: 100 };
    default: return { label: status.replace(/_/g, ' ').toLowerCase(), cls: 'info', progress: 50 };
  }
};

const relTime = (iso: string): string => {
  const d = new Date(iso).getTime();
  if (!Number.isFinite(d)) return '';
  const s = Math.round((Date.now() - d) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  if (s < 86400 * 7) return `${Math.round(s / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
};

type TabKey = 'progress' | 'completed' | 'all';

const MergeRequests: React.FC = () => {
  const navigate = useNavigate();
  const toast = useRef<Toast>(null);
  const [tab, setTab] = useState<TabKey>('progress');
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<{ name: string; description: string; sourceInstanceId: number | null; targetInstanceId: number | null; includeDrafts: boolean }>({
    name: '', description: '', sourceInstanceId: null, targetInstanceId: null, includeDrafts: false,
  });

  const completedParam = tab === 'all' ? undefined : tab === 'completed';
  const { data: mergeRequests = [], isLoading } = useMergeRequests({ completed: completedParam, sortBy: 'updatedAt', sortOrder: 'DESC' } as any);
  const { data: allForCounts = [] } = useMergeRequests({ sortBy: 'updatedAt', sortOrder: 'DESC' } as any);
  const { data: instances = [] } = useInstances();
  const createMut = useCreateMergeRequest();
  const deleteMut = useDeleteMergeRequest();

  const counts = useMemo(() => {
    const all = allForCounts as MergeRequestRow[];
    const completed = all.filter((m) => m.status === 'COMPLETED').length;
    return { all: all.length, completed, progress: all.length - completed };
  }, [allForCounts]);

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (mergeRequests as MergeRequestRow[]).filter((m) =>
      !q || m.name.toLowerCase().includes(q) ||
      m.sourceInstance?.name?.toLowerCase().includes(q) ||
      m.targetInstance?.name?.toLowerCase().includes(q)
    );
  }, [mergeRequests, search]);

  const submit = async () => {
    if (!form.name.trim() || form.sourceInstanceId == null || form.targetInstanceId == null) {
      toast.current?.show({ severity: 'warn', summary: 'Missing fields', detail: 'Name, source and target are required.', life: 3000 });
      return;
    }
    if (form.sourceInstanceId === form.targetInstanceId) {
      toast.current?.show({ severity: 'warn', summary: 'Invalid', detail: 'Source and target must differ.', life: 3000 });
      return;
    }
    try {
      const created: any = await createMut.mutateAsync({
        name: form.name.trim(),
        description: (form.description || '').trim(),
        sourceInstanceId: form.sourceInstanceId,
        targetInstanceId: form.targetInstanceId,
        includeDrafts: form.includeDrafts,
      });
      setShowCreate(false);
      setForm({ name: '', description: '', sourceInstanceId: null, targetInstanceId: null, includeDrafts: false });
      if (created?.id) navigate(`/merge-requests/${created.id}`);
    } catch (e) {
      toast.current?.show({ severity: 'error', summary: 'Error', detail: apiErrorMessage(e), life: 4000 });
    }
  };

  const onDelete = (e: React.MouseEvent, id: number) => {
    e.stopPropagation();
    confirmDialog({
      message: 'Delete this merge request?',
      header: 'Confirm delete',
      icon: 'pi pi-exclamation-triangle',
      acceptClassName: 'p-button-danger',
      accept: async () => {
        try {
          await deleteMut.mutateAsync(id);
          toast.current?.show({ severity: 'success', summary: 'Deleted', life: 2500 });
        } catch (err) {
          toast.current?.show({ severity: 'error', summary: 'Error', detail: apiErrorMessage(err), life: 4000 });
        }
      },
    });
  };

  const Tab: React.FC<{ k: TabKey; label: string; n: number }> = ({ k, label, n }) => (
    <button className={`ss-tab${tab === k ? ' active' : ''}`} onClick={() => setTab(k)}>
      {label} <span className="ss-tab-n">{n}</span>
    </button>
  );

  return (
    <>
      <Toast ref={toast} />
      <ConfirmDialog />

      <div className="ss-page-head">
        <h1>Merge requests</h1>
        <span className="ss-spacer" />
        <button className="ss-btn primary" onClick={() => setShowCreate(true)}>
          <i className="pi pi-plus" aria-hidden="true" /> New
        </button>
      </div>

      <div className="ss-tabs">
        <Tab k="progress" label="In progress" n={counts.progress} />
        <Tab k="completed" label="Completed" n={counts.completed} />
        <Tab k="all" label="All" n={counts.all} />
        <span className="ss-spacer" />
        <div className="ss-search" style={{ width: 200, paddingBottom: 6 }}>
          <i className="pi pi-search" aria-hidden="true" />
          <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search…" />
        </div>
      </div>

      {isLoading ? (
        <div className="flex justify-content-center p-5"><ProgressSpinner /></div>
      ) : rows.length === 0 ? (
        <div className="ss-empty">
          <i className="pi pi-git-pull-request" aria-hidden="true" />
          {search ? 'No merge requests match your search.' : tab === 'completed' ? 'No completed merge requests.' : 'No merge requests yet — create your first one.'}
        </div>
      ) : (
        <div className="ss-list">
          {rows.map((mr) => {
            const meta = statusMeta(mr.status, mr.schemaCompatible);
            return (
              <div key={mr.id} className="ss-card clickable" onClick={() => navigate(`/merge-requests/${mr.id}`)}>
                <div className="ss-card-row">
                  <span className="ss-card-title" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 360 }}>{mr.name}</span>
                  <span className={`ss-badge ${meta.cls}`}>{meta.label}</span>
                  <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                  <span className="ss-dim" style={{ fontSize: 11, whiteSpace: 'nowrap' }}>{relTime(mr.updatedAt)}</span>
                  {tab !== 'completed' && (
                    <Button icon="pi pi-trash" rounded text severity="danger" aria-label="Delete" onClick={(e) => onDelete(e, mr.id)} />
                  )}
                  <i className="pi pi-chevron-right ss-dim" aria-hidden="true" />
                </div>
                <div className="ss-route" style={{ marginTop: 8 }}>
                  <span className="ss-node"><span className="ss-dot" />{mr.sourceInstance?.name || '?'}</span>
                  <i className="pi pi-arrow-right" aria-hidden="true" />
                  <span className="ss-node"><span className="ss-dot target" />{mr.targetInstance?.name || '?'}</span>
                </div>
                <div className={`ss-progress${meta.progress >= 100 ? ' done' : ''}`} style={{ marginTop: 9 }}>
                  <span style={{ width: `${meta.progress}%` }} />
                </div>
              </div>
            );
          })}
        </div>
      )}

      <Dialog
        header="New merge request"
        visible={showCreate}
        style={{ width: '480px' }}
        onHide={() => setShowCreate(false)}
        footer={
          <div>
            <Button label="Cancel" text onClick={() => setShowCreate(false)} disabled={createMut.isPending} />
            <Button label="Create" icon="pi pi-check" onClick={submit} loading={createMut.isPending} />
          </div>
        }
      >
        <div className="p-fluid">
          <div className="field">
            <label htmlFor="mr-name">Name</label>
            <InputText id="mr-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="mr-desc">Description</label>
            <InputTextarea id="mr-desc" rows={3} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="mr-src">Source instance</label>
            <Dropdown id="mr-src" value={form.sourceInstanceId} options={instances as any[]} optionLabel="name" optionValue="id"
              onChange={(e) => setForm({ ...form, sourceInstanceId: e.value })} placeholder="Select source" />
          </div>
          <div className="field">
            <label htmlFor="mr-tgt">Target instance</label>
            <Dropdown id="mr-tgt" value={form.targetInstanceId} options={instances as any[]} optionLabel="name" optionValue="id"
              onChange={(e) => setForm({ ...form, targetInstanceId: e.value })} placeholder="Select target" />
          </div>
          <div className="field">
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
              <Checkbox inputId="mr-drafts" checked={form.includeDrafts}
                onChange={(e) => setForm({ ...form, includeDrafts: !!e.checked })} />
              <label htmlFor="mr-drafts" style={{ margin: 0, cursor: 'pointer' }}>Include drafts (Strapi Draft &amp; Publish)</label>
            </div>
            <small className="ss-muted" style={{ display: 'block', marginTop: '0.35rem' }}>
              Sync the draft channel too: divergent “modified” drafts, draft-only entries and unpublished
              state are reproduced on the target. Leave off to sync only published content.
            </small>
          </div>
        </div>
      </Dialog>
    </>
  );
};

export default MergeRequests;
