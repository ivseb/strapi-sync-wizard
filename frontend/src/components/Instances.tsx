import React, { useRef, useState } from 'react';
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
        <h2>Instances</h2>
        <Button label="Add instance" icon="pi pi-plus" onClick={() => handleShowModal()} />
      </div>

      {error && <Message severity="error" text={error} className="w-full mb-3" />}

      {instances.length === 0 ? (
        <Message severity="info" text="No instances yet. Add your first Strapi instance to get started." className="w-full" />
      ) : (
        <div className="flex flex-column gap-2">
          {instances.map((instance) => {
            const virtual = !!instance.isVirtual;
            return (
              <div key={instance.id} className="surface-card border-round p-3 flex align-items-center gap-3" style={{ border: '1px solid var(--surface-border)' }}>
                <span
                  className="flex align-items-center justify-content-center border-circle"
                  style={{ width: 38, height: 38, flex: 'none', background: 'var(--surface-100)', color: virtual ? 'var(--text-color-secondary)' : 'var(--primary-color)' }}
                >
                  <i className={virtual ? 'pi pi-cloud' : 'pi pi-server'} aria-hidden="true" />
                </span>
                <div style={{ minWidth: 0, maxWidth: 460 }}>
                  <div className="flex align-items-center gap-2">
                    <span style={{ fontWeight: 500 }}>{instance.name}</span>
                    <span className="ss-muted" style={{ fontSize: 11, border: '1px solid var(--surface-border)', borderRadius: 6, padding: '1px 7px' }}>
                      {virtual ? 'virtual' : 'real'}
                    </span>
                  </div>
                  <div className="ss-muted" style={{ fontFamily: 'var(--font-family, monospace)', fontSize: 12, marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {virtual ? 'offline · via pipeline artifact' : instance.url}
                  </div>
                </div>
                <span className="flex-1" />
                {!virtual && instance.dbName && (
                  <span className="ss-muted text-sm"><i className="pi pi-database" style={{ fontSize: '.8rem' }} aria-hidden="true" /> {instance.dbName}</span>
                )}
                <Button
                  icon="pi pi-ellipsis-v" rounded text aria-label="Actions"
                  onClick={(e) => { setMenuModel(buildMenu(instance)); menu.current?.toggle(e); }}
                />
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
