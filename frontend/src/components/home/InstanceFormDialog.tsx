import React from 'react';
import { Dialog } from 'primereact/dialog';
import { Button } from 'primereact/button';
import { InputText } from 'primereact/inputtext';
import { Password } from 'primereact/password';
import { Message } from 'primereact/message';
import { FormData } from '../../types';

interface InstanceFormDialogProps {
  visible: boolean;
  formData: FormData;
  isEditing: boolean;
  testingConnection: boolean;
  connectionStatus: { success: boolean, message: string } | null;
  onHide: () => void;
  onInputChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onTestConnection: () => void;
  onSubmit: (e: React.FormEvent) => void;
}

const InstanceFormDialog: React.FC<InstanceFormDialogProps> = ({
  visible,
  formData,
  isEditing,
  testingConnection,
  connectionStatus,
  onHide,
  onInputChange,
  onTestConnection,
  onSubmit
}) => {
  // Modal footer buttons
  const modalFooter = (
    <div className="flex justify-content-end gap-2">
      <Button label="Cancel" icon="pi pi-times" className="p-button-text" onClick={onHide} />
      <Button 
        label={isEditing ? 'Update' : 'Save'} 
        icon="pi pi-check" 
        disabled={
          !formData.name || 
          !formData.url || 
          !formData.username || 
          (!isEditing && (!formData.password || !formData.apiKey)) ||
          (!isEditing && (!formData.dbHost || !formData.dbPort || !formData.dbName || !formData.dbUser || !formData.dbPassword))
        }
        onClick={onSubmit}
      />
    </div>
  );

  return (
    <Dialog 
      header={`${isEditing ? 'Edit' : 'Add'} Strapi Instance`} 
      visible={visible} 
      style={{ width: '50vw' }} 
      onHide={onHide}
      footer={modalFooter}
    >
      <div className="p-fluid">
        <div className="field mb-3">
          <label htmlFor="name" className="block mb-2">Instance Name</label>
          <InputText
            id="name"
            name="name"
            value={formData.name}
            onChange={onInputChange}
            placeholder="My Strapi Instance"
            required
          />
        </div>

        <div className="field mb-3">
          <label htmlFor="url" className="block mb-2">Strapi URL</label>
          <InputText
            id="url"
            name="url"
            value={formData.url}
            onChange={onInputChange}
            placeholder="https://my-strapi-instance.com"
            required
          />
        </div>

        <div className="field mb-3">
          <label htmlFor="username" className="block mb-2">Username</label>
          <InputText
            id="username"
            name="username"
            value={formData.username}
            onChange={onInputChange}
            placeholder="Username for authentication"
            required
          />
        </div>

        <div className="field mb-3">
          <label htmlFor="password" className="block mb-2">
            Password {isEditing && <span className="text-sm text-gray-500">(leave empty to keep current)</span>}
          </label>
          <Password
            id="password"
            name="password"
            value={formData.password}
            onChange={onInputChange}
            placeholder={isEditing ? "Leave empty to keep current password" : "Password for authentication"}
            feedback={false}
            toggleMask
            required={!isEditing}
          />
        </div>

        <div className="field mb-3">
          <label htmlFor="apiKey" className="block mb-2">
            API Key {isEditing && <span className="text-sm text-gray-500">(leave empty to keep current)</span>}
          </label>
          <Password
            id="apiKey"
            name="apiKey"
            value={formData.apiKey}
            onChange={onInputChange}
            placeholder={isEditing ? "Leave empty to keep current API key" : "Your Strapi API Key"}
            feedback={false}
            toggleMask
            required={!isEditing}
          />
        </div>

        <hr />
        <h3>Postgres connection (per-instance)</h3>
        <div className="field mb-3">
          <label htmlFor="dbHost" className="block mb-2">DB Host{!isEditing && ' *'}</label>
          <InputText
            id="dbHost"
            name="dbHost"
            value={formData.dbHost || ''}
            onChange={onInputChange}
            placeholder="e.g. mydb.example.com"
            required={!isEditing}
          />
        </div>
        <div className="field mb-3">
          <label htmlFor="dbPort" className="block mb-2">DB Port{!isEditing && ' *'}</label>
          <InputText
            id="dbPort"
            name="dbPort"
            value={(formData.dbPort ?? '').toString()}
            onChange={onInputChange}
            placeholder="5432"
            required={!isEditing}
          />
        </div>
        <div className="field mb-3">
          <label htmlFor="dbName" className="block mb-2">DB Name{!isEditing && ' *'}</label>
          <InputText
            id="dbName"
            name="dbName"
            value={formData.dbName || ''}
            onChange={onInputChange}
            placeholder="database name"
            required={!isEditing}
          />
        </div>
        <div className="field mb-3">
          <label htmlFor="dbSchema" className="block mb-2">DB Schema</label>
          <InputText
            id="dbSchema"
            name="dbSchema"
            value={formData.dbSchema || ''}
            onChange={onInputChange}
            placeholder="public"
          />
        </div>
        <div className="field mb-3">
          <label htmlFor="dbUser" className="block mb-2">DB User{!isEditing && ' *'}</label>
          <InputText
            id="dbUser"
            name="dbUser"
            value={formData.dbUser || ''}
            onChange={onInputChange}
            placeholder="db username"
            required={!isEditing}
          />
        </div>
        <div className="field mb-3">
          <label htmlFor="dbPassword" className="block mb-2">DB Password{isEditing && <span className="text-sm text-gray-500"> (leave empty to keep current)</span>}</label>
          <Password
            id="dbPassword"
            name="dbPassword"
            value={formData.dbPassword || ''}
            onChange={onInputChange}
            placeholder={isEditing ? "Leave empty to keep current DB password" : "DB password"}
            feedback={false}
            toggleMask
            required={!isEditing}
          />
        </div>
        <div className="field mb-3">
          <label htmlFor="dbSslMode" className="block mb-2">DB SSL Mode</label>
          <InputText
            id="dbSslMode"
            name="dbSslMode"
            value={formData.dbSslMode || ''}
            onChange={onInputChange}
            placeholder="disable | require | verify-ca | verify-full"
          />
        </div>

        <div className="field mb-3">
          <Button 
            label="Test Connection" 
            icon={testingConnection ? "pi pi-spin pi-spinner" : "pi pi-sync"}
            className="p-button-info w-full"
            onClick={onTestConnection}
            disabled={
              testingConnection || 
              !formData.url || 
              !formData.username || 
              !formData.password || 
              !formData.apiKey
            }
            tooltip={
              isEditing && (!formData.password || !formData.apiKey) 
                ? "Please enter password and API key to test connection" 
                : undefined
            }
          />
        </div>

        {connectionStatus && (
          <Message 
            severity={connectionStatus.success ? "success" : "error"} 
            text={connectionStatus.message}
            className="w-full"
          />
        )}
      </div>
    </Dialog>
  );
};

export default InstanceFormDialog;
