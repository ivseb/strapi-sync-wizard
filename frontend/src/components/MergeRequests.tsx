import React, { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from 'primereact/button';
import { Message } from 'primereact/message';
import { Toast } from 'primereact/toast';
import { Dialog } from 'primereact/dialog';
import { InputText } from 'primereact/inputtext';
import { Dropdown } from 'primereact/dropdown';
import { InputTextarea } from 'primereact/inputtextarea';
import { Tag } from 'primereact/tag';
import { SelectButton } from 'primereact/selectbutton';
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

const statusMeta = (status: string, schemaCompatible: boolean | null): { label: string; severity: 'success' | 'info' | 'warning' | 'danger' } => {
  switch (status) {
    case 'CREATED': return { label: 'created', severity: 'info' };
    case 'SCHEMA_CHECKED': return schemaCompatible ? { label: 'schema ok', severity: 'success' } : { label: 'schema incompatible', severity: 'danger' };
    case 'COMPARED': return { label: 'compared', severity: 'info' };
    case 'MERGED_FILES': return { label: 'files merged', severity: 'info' };
    case 'MERGED_SINGLES': return { label: 'singles merged', severity: 'info' };
    case 'MERGED_COLLECTIONS': return { label: 'collections merged', severity: 'info' };
    case 'REVIEW': return { label: 'review', severity: 'warning' };
    case 'IN_PROGRESS': return { label: 'in progress', severity: 'warning' };
    case 'COMPLETED': return { label: 'completed', severity: 'success' };
    case 'FAILED': return { label: 'failed', severity: 'danger' };
    default: return { label: status.replace(/_/g, ' ').toLowerCase(), severity: 'info' };
  }
};

const iconColor = (sev: string) =>
  sev === 'success' ? 'var(--green-400)' : sev === 'danger' ? 'var(--red-400)' : sev === 'warning' ? 'var(--yellow-400)' : 'var(--primary-color)';

const MergeRequests: React.FC = () => {
  const navigate = useNavigate();
  const toast = useRef<Toast>(null);
  const [completed, setCompleted] = useState<boolean>(false);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState<{ name: string; description: string; sourceInstanceId: number | null; targetInstanceId: number | null }>({
    name: '', description: '', sourceInstanceId: null, targetInstanceId: null,
  });

  const { data: mergeRequests = [], isLoading } = useMergeRequests({ completed, sortBy: 'updatedAt', sortOrder: 'DESC' });
  const { data: instances = [] } = useInstances();
  const createMut = useCreateMergeRequest();
  const deleteMut = useDeleteMergeRequest();

  const rows = mergeRequests as MergeRequestRow[];

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
      });
      setShowCreate(false);
      setForm({ name: '', description: '', sourceInstanceId: null, targetInstanceId: null });
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

  return (
    <>
      <Toast ref={toast} />
      <ConfirmDialog />

      <div className="ss-page-head">
        <h2>Merge requests</h2>
        <Button label="New" icon="pi pi-plus" onClick={() => setShowCreate(true)} />
      </div>

      <SelectButton
        value={completed}
        onChange={(e) => e.value !== null && setCompleted(e.value)}
        options={[{ label: 'In progress', value: false }, { label: 'Completed', value: true }]}
        className="mb-3"
      />

      {isLoading ? (
        <div className="flex justify-content-center p-5"><ProgressSpinner /></div>
      ) : rows.length === 0 ? (
        <Message severity="info" text={completed ? 'No completed merge requests.' : 'No merge requests yet. Create your first one.'} className="w-full" />
      ) : (
        <div className="flex flex-column gap-2">
          {rows.map((mr) => {
            const meta = statusMeta(mr.status, mr.schemaCompatible);
            return (
              <div
                key={mr.id}
                onClick={() => navigate(`/merge-requests/${mr.id}`)}
                className="surface-card border-round p-3 flex align-items-center gap-3 cursor-pointer"
                style={{ border: '1px solid var(--surface-border)' }}
              >
                <i className="pi pi-arrows-h" style={{ fontSize: '1.1rem', color: iconColor(meta.severity) }} aria-hidden="true" />
                <div style={{ minWidth: 0, maxWidth: 420 }}>
                  <div style={{ fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{mr.name}</div>
                  <div className="ss-muted text-sm mt-1" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {mr.sourceInstance?.name || '?'} <i className="pi pi-arrow-right" style={{ fontSize: '.7rem', verticalAlign: 'baseline' }} aria-hidden="true" /> {mr.targetInstance?.name || '?'}
                  </div>
                </div>
                <Tag value={meta.label} severity={meta.severity} />
                <span className="flex-1" />
                <span className="ss-muted text-sm" style={{ whiteSpace: 'nowrap' }}>
                  {new Date(mr.updatedAt).toLocaleDateString()}
                </span>
                {!completed && (
                  <Button
                    icon="pi pi-trash"
                    rounded text severity="danger"
                    aria-label="Delete"
                    onClick={(e) => onDelete(e, mr.id)}
                  />
                )}
                <i className="pi pi-chevron-right ss-muted" aria-hidden="true" />
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
        </div>
      </Dialog>
    </>
  );
};

export default MergeRequests;
