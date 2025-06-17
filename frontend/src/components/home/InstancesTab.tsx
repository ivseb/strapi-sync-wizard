import React from 'react';
import { Card } from 'primereact/card';
import { Button } from 'primereact/button';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Message } from 'primereact/message';
import { StrapiInstance } from '../../types';

interface InstancesTabProps {
  instances: StrapiInstance[];
  error: string | null;
  onAddInstance: () => void;
  onEditInstance: (instance: StrapiInstance) => void;
  onDeleteInstance: (id: number) => void;
}

const InstancesTab: React.FC<InstancesTabProps> = ({
  instances,
  error,
  onAddInstance,
  onEditInstance,
  onDeleteInstance
}) => {
  return (
    <Card className="mb-4">
      <div className="flex justify-content-between align-items-center p-3 bg-primary text-white">
        <h2 className="m-0">Strapi Instances</h2>
        <Button icon="pi pi-plus" label="Add Instance" onClick={onAddInstance} />
      </div>
      <div className="p-3">
        {error && <Message severity="error" text={error} className="w-full mb-3" />}

        {instances.length === 0 ? (
          <Message severity="info" text="No instances found. Add your first Strapi instance to get started." className="w-full" />
        ) : (
          <DataTable value={instances} responsiveLayout="scroll" stripedRows>
            <Column field="name" header="Name" />
            <Column field="url" header="URL" />
            <Column body={(instance) => (
              <div className="flex gap-2">
                <Button 
                  icon="pi pi-pencil" 
                  className="p-button-outlined p-button-primary p-button-sm" 
                  onClick={() => onEditInstance(instance)}
                  tooltip="Edit"
                />
                <Button 
                  icon="pi pi-trash" 
                  className="p-button-outlined p-button-danger p-button-sm"
                  onClick={() => onDeleteInstance(instance.id)}
                  tooltip="Delete"
                />
              </div>
            )} header="Actions" />
          </DataTable>
        )}
      </div>
    </Card>
  );
};

export default InstancesTab;