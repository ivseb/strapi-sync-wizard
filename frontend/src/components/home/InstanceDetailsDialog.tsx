import React, { useState } from 'react';
import { Dialog } from 'primereact/dialog';
import { Button } from 'primereact/button';
import { InputText } from 'primereact/inputtext';
import { Password } from 'primereact/password';
import { Message } from 'primereact/message';
import { StrapiInstance } from '../../types';

interface InstanceDetailsDialogProps {
  visible: boolean;
  instanceId: number | null;
  onHide: () => void;
  onVerifyPassword: (password: string, instanceId: number) => Promise<void>;
  fullInstanceData: StrapiInstance | null;
  loading: boolean;
  error: string | null;
}

const InstanceDetailsDialog: React.FC<InstanceDetailsDialogProps> = ({
  visible,
  instanceId,
  onHide,
  onVerifyPassword,
  fullInstanceData,
  loading,
  error
}) => {
  const [password, setPassword] = useState<string>('');

  const handleVerify = () => {
    if (instanceId) {
      onVerifyPassword(password, instanceId);
    }
  };

  const handleHide = () => {
    setPassword('');
    onHide();
  };

  const renderContent = () => {
    if (loading) {
      return <div className="p-3 text-center">Loading...</div>;
    }

    if (error) {
      return <Message severity="error" text={error} className="w-full mb-3" />;
    }

    if (fullInstanceData) {
      return (
        <div className="p-3">
          <h3>Instance Details</h3>
          <div className="p-grid p-fluid">
            <div className="p-col-12 p-md-6 mb-2">
              <label className="font-bold">Name</label>
              <div>{fullInstanceData.name}</div>
            </div>
            <div className="p-col-12 p-md-6 mb-2">
              <label className="font-bold">URL</label>
              <div>{fullInstanceData.url}</div>
            </div>
            <div className="p-col-12 p-md-6 mb-2">
              <label className="font-bold">Username</label>
              <div>{fullInstanceData.username}</div>
            </div>
            <div className="p-col-12 p-md-6 mb-2">
              <label className="font-bold">Password</label>
              <InputText value={fullInstanceData.password} readOnly className="w-full" />
            </div>
            <div className="p-col-12 mb-2">
              <label className="font-bold">API Key</label>
              <InputText value={fullInstanceData.apiKey} readOnly className="w-full" />
            </div>
          </div>
        </div>
      );
    }

    return (
      <div className="p-3">
        <Message severity="info" text="Enter the admin password to view sensitive instance details" className="w-full mb-3" />
        <div className="p-field mb-3">
          <label htmlFor="admin-password" className="font-bold">Admin Password</label>
          <Password
            id="admin-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full"
            feedback={false}
            toggleMask
          />
        </div>
        <Button
          label="Verify Password"
          icon="pi pi-lock-open"
          onClick={handleVerify}
          className="w-full"
        />
      </div>
    );
  };

  return (
    <Dialog
      header="Instance Details"
      visible={visible}
      onHide={handleHide}
      style={{ width: '500px' }}
      modal
      footer={
        <div>
          <Button label="Close" icon="pi pi-times" onClick={handleHide} className="p-button-text" />
        </div>
      }
    >
      {renderContent()}
    </Dialog>
  );
};

export default InstanceDetailsDialog;