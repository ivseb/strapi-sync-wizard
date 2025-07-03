import React, { useState } from 'react';
import { Card } from 'primereact/card';
import { Button } from 'primereact/button';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Message } from 'primereact/message';
import { Toast } from 'primereact/toast';
import { useInstanceManagement } from '../hooks/useInstanceManagement';
import LoadingSpinner from './shared/LoadingSpinner';
import InstanceFormDialog from './home/InstanceFormDialog';
import InstanceDetailsDialog from './home/InstanceDetailsDialog';
import { StrapiInstance } from '../types';

const Instances: React.FC = () => {
  const {
    instances,
    loading,
    error,
    showModal,
    formData,
    isEditing,
    testingConnection,
    connectionStatus,
    toast: instanceToast,
    handleCloseModal,
    handleShowModal,
    handleInputChange,
    handleTestConnection,
    handleSubmit,
    handleDelete,
    // Instance details dialog
    showDetailsModal,
    selectedInstanceId,
    fullInstanceData,
    loadingDetails,
    detailsError,
    handleShowDetailsModal,
    handleCloseDetailsModal,
    handleVerifyPassword
  } = useInstanceManagement();

  if (loading && instances.length === 0) {
    return <LoadingSpinner message="Loading instances..." />;
  }

  return (
    <div className="container py-4">
      <Toast ref={instanceToast} />

      <Card className="mb-4">
        <div className="flex justify-content-between align-items-center p-3 bg-primary text-white">
          <h2 className="m-0">Strapi Instances</h2>
          <Button icon="pi pi-plus" label="Add Instance" onClick={() => handleShowModal()} />
        </div>
        <div className="p-3">
          {error && <Message severity="error" text={error} className="w-full mb-3" />}

          {instances.length === 0 ? (
            <Message severity="info" text="No instances found. Add your first Strapi instance to get started." className="w-full" />
          ) : (
            <DataTable value={instances} responsiveLayout="scroll" stripedRows>
              <Column field="name" header="Name" />
              <Column field="url" header="URL" />
              <Column body={(instance: StrapiInstance) => (
                <div className="flex gap-2">
                  <Button 
                    icon="pi pi-eye" 
                    className="p-button-outlined p-button-info p-button-sm" 
                    onClick={() => handleShowDetailsModal(instance.id)}
                    tooltip="View Details"
                  />
                  <Button 
                    icon="pi pi-pencil" 
                    className="p-button-outlined p-button-primary p-button-sm" 
                    onClick={() => handleShowModal(instance)}
                    tooltip="Edit"
                  />
                  <Button 
                    icon="pi pi-trash" 
                    className="p-button-outlined p-button-danger p-button-sm"
                    onClick={() => handleDelete(instance.id)}
                    tooltip="Delete"
                  />
                </div>
              )} header="Actions" />
            </DataTable>
          )}
        </div>
      </Card>

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
    </div>
  );
};

export default Instances;
