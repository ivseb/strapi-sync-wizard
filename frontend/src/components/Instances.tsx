import React, { useMemo, useRef, useState } from 'react';
import { Button } from 'primereact/button';
import { Message } from 'primereact/message';
import { Toast } from 'primereact/toast';
import { Menu } from 'primereact/menu';
import { MenuItem } from 'primereact/menuitem';
import { useInstanceManagement } from '../hooks/useInstanceManagement';
import LoadingSpinner from './shared/LoadingSpinner';
import InstanceFormDialog from './home/InstanceFormDialog';
import InstanceDetailsDialog from './home/InstanceDetailsDialog';
import { StrapiInstance } from '../types';
import { instancesApi } from '../api/instances';
import { apiErrorMessage } from '../api/http';

const Instances: React.FC = () => {
  const {
    instances, loading, error,
    showModal, formData, isEditing, testingConnection, connectionStatus,
    toast: instanceToast,
    handleCloseModal, handleShowModal, handleInputChange, handleTestConnection, handleSubmit, handleDelete,
    showDetailsModal, selectedInstanceId, fullInstanceData, loadingDetails, detailsError,
    handleShowDetailsModal, handleCloseDetailsModal, handleVerifyPassword,
  } = useInstanceManagement();

  const menu = useRef<Menu>(null);
  const [menuModel, setMenuModel] = useState<MenuItem[]>([]);
  const [search, setSearch] = useState('');

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return instances.filter((i) => !q || i.name.toLowerCase().includes(q) || (i.url || '').toLowerCase().includes(q));
  }, [instances, search]);

  const notify = (summary: string, r: { connected: boolean; message: string }) =>
    instanceToast.current?.show({ severity: r.connected ? 'success' : 'warn', summary, detail: r.message, life: 3000 });
  const notifyErr = (summary: string, e: unknown) =>
    instanceToast.current?.show({ severity: 'error', summary, detail: apiErrorMessage(e), life: 3500 });

  const runTest = async (id: number, label: string, fn: (id: number) => Promise<{ connected: boolean; message: string }>) => {
    try { notify(label, await fn(id)); } catch (e) { notifyErr(label, e); }
  };

  const backfill = async (id: number) => {
    try {
      const r = await instancesApi.backfillIdentity(id);
      instanceToast.current?.show({ severity: 'success', summary: 'Identity backfill', detail: `${r.inserted} new sync_id assigned`, life: 4000 });
    } catch (e) { notifyErr('Identity backfill', e); }
  };

  const download = async (path: string, filename: string, asJson: boolean) => {
    try {
      const resp = await fetch(path);
      if (!resp.ok) throw new Error(await resp.text());
      const blob = asJson ? new Blob([JSON.stringify(await resp.json(), null, 2)], { type: 'application/json' }) : await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename; a.click();
      URL.revokeObjectURL(url);
    } catch (e) { notifyErr('Export', e); }
  };

  const buildMenu = (instance: StrapiInstance): MenuItem[] => {
    const id = instance.id;
    const items: MenuItem[] = [
      { label: 'View details', icon: 'pi pi-eye', command: () => handleShowDetailsModal(id) },
      { label: 'Edit', icon: 'pi pi-pencil', command: () => handleShowModal(instance) },
      { separator: true },
      { label: 'Test DB', icon: 'pi pi-database', command: () => runTest(id, 'DB test', instancesApi.testDb) },
      { label: 'Test login', icon: 'pi pi-sign-in', command: () => runTest(id, 'Login test', instancesApi.testLogin) },
      { label: 'Test token', icon: 'pi pi-key', command: () => runTest(id, 'Token test', instancesApi.testToken) },
    ];
    if (!instance.isVirtual) {
      items.push({ label: 'Backfill identity', icon: 'pi pi-id-card', command: () => backfill(id) });
    }
    items.push(
      { separator: true },
      { label: 'Export schema', icon: 'pi pi-download', command: () => download(`/api/instances/${id}/export/schema`, `instance_${id}_schema.json`, true) },
      { label: 'Export prefetch', icon: 'pi pi-download', command: () => download(`/api/instances/${id}/export/prefetch`, `instance_${id}_prefetch.json`, true) },
      { label: 'Export bundle (zip)', icon: 'pi pi-file-zip', command: () => download(`/api/instances/${id}/export/bundle`, `instance_${id}_bundle.zip`, false) },
      { separator: true },
      { label: 'Delete', icon: 'pi pi-trash', command: () => handleDelete(id) },
    );
    return items;
  };

  if (loading && instances.length === 0) return <LoadingSpinner message="Loading instances..." />;

  return (
    <>
      <Toast ref={instanceToast} />
      <Menu model={menuModel} popup ref={menu} />

      <div className="ss-page-head">
        <h1>Instances</h1>
        <span className="ss-spacer" />
        {instances.length > 0 && (
          <div className="ss-search" style={{ width: 200 }}>
            <i className="pi pi-search" aria-hidden="true" />
            <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search…" />
          </div>
        )}
        <button className="ss-btn primary" onClick={() => handleShowModal()}>
          <i className="pi pi-plus" aria-hidden="true" /> Add instance
        </button>
      </div>

      {error && <Message severity="error" text={error} className="w-full mb-3" />}

      {instances.length === 0 ? (
        <div className="ss-empty">
          <i className="pi pi-server" aria-hidden="true" />
          No instances yet — add your first Strapi instance to get started.
        </div>
      ) : (
        <div className="ss-list">
          {rows.map((instance) => {
            const virtual = !!instance.isVirtual;
            return (
              <div key={instance.id} className="ss-card">
                <div className="ss-card-row">
                  <span className="ss-avatar" style={{ width: 36, height: 36, fontSize: 15, color: virtual ? 'var(--ss-text-2)' : 'var(--ss-accent-soft-fg)', background: virtual ? 'var(--ss-surface-3)' : 'var(--ss-accent-soft-bg)' }}>
                    <i className={virtual ? 'pi pi-cloud' : 'pi pi-server'} aria-hidden="true" />
                  </span>
                  <div style={{ minWidth: 0 }}>
                    <div className="ss-card-row" style={{ gap: 8 }}>
                      <span className="ss-card-title">{instance.name}</span>
                      <span className="ss-badge">{virtual ? 'virtual' : 'real'}</span>
                    </div>
                    <div className="ss-dim" style={{ fontSize: 12, marginTop: 3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 460 }}>
                      {virtual ? 'offline · via pipeline artifact' : instance.url}
                    </div>
                  </div>
                  <span className="ss-spacer" style={{ marginLeft: 'auto' }} />
                  {!virtual && instance.dbName && (
                    <span className="ss-dim" style={{ fontSize: 12 }}><i className="pi pi-database" style={{ fontSize: '.8rem' }} aria-hidden="true" /> {instance.dbName}</span>
                  )}
                  <Button icon="pi pi-ellipsis-v" rounded text aria-label="Actions"
                    onClick={(e) => { setMenuModel(buildMenu(instance)); menu.current?.toggle(e); }} />
                </div>
              </div>
            );
          })}
        </div>
      )}

      <InstanceFormDialog
        visible={showModal}
        formData={formData}
        isEditing={isEditing}
        testingConnection={testingConnection}
        connectionStatus={connectionStatus}
        onHide={handleCloseModal}
        onInputChange={handleInputChange}
        onTestConnection={handleTestConnection}
        onSubmit={handleSubmit}
      />

      <InstanceDetailsDialog
        visible={showDetailsModal}
        instanceId={selectedInstanceId}
        onHide={handleCloseDetailsModal}
        onVerifyPassword={handleVerifyPassword}
        fullInstanceData={fullInstanceData}
        loading={loadingDetails}
        error={detailsError}
      />
    </>
  );
};

export default Instances;
