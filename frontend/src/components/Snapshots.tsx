import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Toast } from 'primereact/toast';
import { instancesApi, InstanceSnapshot } from '../api/instances';
import { apiErrorMessage } from '../api/http';
import type { StrapiInstance } from '../types';

const fmt = (s: string) => new Date(s).toLocaleString();

const InstanceSnapshots: React.FC<{
  instance: StrapiInstance;
  onToast: (sev: 'success' | 'warn' | 'error', summary: string, detail?: string) => void;
}> = ({ instance, onToast }) => {
  const { data: snaps, isLoading, refetch } = useQuery({
    queryKey: ['instance-snapshots', instance.id],
    queryFn: () => instancesApi.listSnapshots(instance.id),
  });
  const [busy, setBusy] = useState<number | null>(null);

  const restore = async (s: InstanceSnapshot) => {
    if (!window.confirm(`Restore snapshot of "${s.mergeRequestName}" taken on ${fmt(s.createdAt)}?\nAll current data in the target schema will be overwritten.`)) return;
    setBusy(s.id);
    try {
      await instancesApi.restoreSnapshot(instance.id, s.id);
      onToast('success', 'Snapshot restored', `${instance.name} ← ${s.snapshotSchemaName}`);
    } catch (e) {
      onToast('error', 'Restore failed', apiErrorMessage(e) || undefined);
    } finally { setBusy(null); refetch(); }
  };

  const remove = async (s: InstanceSnapshot) => {
    if (!window.confirm(`Delete snapshot ${s.snapshotSchemaName}? This drops its schema and cannot be undone.`)) return;
    setBusy(s.id);
    try {
      await instancesApi.deleteSnapshot(instance.id, s.id);
      onToast('success', 'Snapshot deleted', s.snapshotSchemaName);
    } catch (e) {
      onToast('error', 'Delete failed', apiErrorMessage(e) || undefined);
    } finally { setBusy(null); refetch(); }
  };

  return (
    <div className="ss-review" style={{ marginBottom: 16 }}>
      <div className="ss-review-toolbar">
        <span className="ss-state-dot target" style={{ background: 'var(--ss-green)' }} />
        <span style={{ fontSize: 13, color: 'var(--ss-text)' }}>{instance.name}</span>
        <span className="ss-dim" style={{ fontSize: 11 }}>{instance.url}</span>
        {snaps && snaps.length > 0 && <span className="ss-dim" style={{ fontSize: 11.5 }}>{snaps.length} snapshot{snaps.length === 1 ? '' : 's'}</span>}
        <span style={{ marginLeft: 'auto' }} />
        <button className="ss-btn subtle" onClick={() => refetch()} disabled={isLoading} title="Refresh">
          <i className={`pi ${isLoading ? 'pi-spin pi-spinner' : 'pi-refresh'}`} aria-hidden="true" />
        </button>
      </div>
      {isLoading ? (
        <div className="ss-dim" style={{ padding: '14px 16px', fontSize: 12 }}>Loading…</div>
      ) : !snaps || snaps.length === 0 ? (
        <div className="ss-empty" style={{ padding: '24px 16px' }}>
          <i className="pi pi-camera" aria-hidden="true" />
          No snapshots for this instance.
        </div>
      ) : snaps.map((s) => (
        <div className="ss-erow" key={s.id}>
          <div className="ss-erow-head" style={{ cursor: 'default' }}>
            <i className="pi pi-camera" style={{ fontSize: 12, color: 'var(--ss-text-3)' }} aria-hidden="true" />
            <span className="ss-erow-name">{fmt(s.createdAt)}</span>
            <span className="ss-erow-summary" style={{ flex: '1 1 auto' }}>
              <span className="ss-badge" style={{ fontSize: 9, marginRight: 6 }}>MR #{s.mergeRequestId}</span>
              {s.mergeRequestName} · <code>{s.snapshotSchemaName}</code>
            </span>
            <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 8 }}>
              <button className="ss-btn" style={{ color: 'var(--ss-amber)', borderColor: 'var(--ss-amber-bg)' }}
                onClick={() => restore(s)} disabled={busy === s.id}>
                <i className={`pi ${busy === s.id ? 'pi-spin pi-spinner' : 'pi-undo'}`} aria-hidden="true" /> Restore
              </button>
              <button className="ss-btn" style={{ color: 'var(--ss-red)', borderColor: 'var(--ss-red-bg)' }}
                onClick={() => remove(s)} disabled={busy === s.id} title="Delete snapshot">
                <i className="pi pi-trash" aria-hidden="true" />
              </button>
            </span>
          </div>
        </div>
      ))}
    </div>
  );
};

const Snapshots: React.FC = () => {
  const toast = React.useRef<Toast>(null);
  const onToast = (severity: 'success' | 'warn' | 'error', summary: string, detail?: string) =>
    toast.current?.show({ severity, summary, detail, life: 5000 });

  const { data: instances, isLoading } = useQuery({ queryKey: ['instances', 'list'], queryFn: () => instancesApi.list() });
  // real (non-virtual) instances only — snapshots live in a real target DB
  const real = (instances || []).filter((i) => !i.isVirtual);

  return (
    <div className="ss-content">
      <Toast ref={toast} />
      <div className="ss-card-row" style={{ marginBottom: 14 }}>
        <h2><i className="pi pi-database" style={{ marginRight: 8 }} aria-hidden="true" />Database snapshots</h2>
        <span className="ss-dim" style={{ fontSize: 12, marginLeft: 10 }}>Monitor and manage DB snapshots per instance. Snapshots are taken automatically before each merge.</span>
      </div>
      {isLoading ? (
        <div className="ss-dim" style={{ fontSize: 12 }}>Loading instances…</div>
      ) : real.length === 0 ? (
        <div className="ss-empty"><i className="pi pi-server" aria-hidden="true" />No real instances configured.</div>
      ) : real.map((inst) => <InstanceSnapshots key={inst.id} instance={inst} onToast={onToast} />)}
    </div>
  );
};

export default Snapshots;
