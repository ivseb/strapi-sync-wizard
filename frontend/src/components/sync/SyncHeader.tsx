import React from 'react';
import { Card } from 'primereact/card';
import { Button } from 'primereact/button';
import { InputText } from 'primereact/inputtext';
import { Tag } from 'primereact/tag';
import { StrapiInstance } from '../../types';

interface SyncHeaderProps {
  sourceInstance: StrapiInstance;
  targetInstance: StrapiInstance;
  globalFilter: string;
  onFilterChange: (value: string) => void;
  onBack: () => void;
  onSync: () => void;
  totalSelectedCount: number;
  syncInProgress: boolean;
}

const SyncHeader: React.FC<SyncHeaderProps> = ({
  sourceInstance,
  targetInstance,
  globalFilter,
  onFilterChange,
  onBack,
  onSync,
  totalSelectedCount,
  syncInProgress
}) => {
  return (
    <Card className="mb-4 shadow-4">
      <div className="flex flex-column md:flex-row justify-content-between align-items-center p-3">
        <div className="flex align-items-center mb-3 md:mb-0">
          <Button 
            label="Back" 
            icon="pi pi-arrow-left" 
            className="p-button-text mr-3" 
            onClick={onBack} 
          />
          <div>
            <h2 className="m-0 text-primary">Synchronization</h2>
            <div className="flex align-items-center mt-2">
              <Tag severity="info" value={sourceInstance.name} className="mr-2" />
              <i className="pi pi-arrow-right mx-2" />
              <Tag severity="warning" value={targetInstance.name} />
            </div>
          </div>
        </div>

        <div className="flex align-items-center">
          <span className="p-input-icon-left mr-3">
            <i className="pi pi-search" />
            <InputText 
              placeholder="Search content types..." 
              value={globalFilter} 
              onChange={(e) => onFilterChange(e.target.value)} 
              className="p-inputtext-sm"
            />
          </span>
          <Button 
            label={`Sync (${totalSelectedCount})`}
            icon="pi pi-sync" 
            className="p-button-success"
            disabled={totalSelectedCount === 0 || syncInProgress}
            onClick={onSync}
            tooltip="Review and synchronize selected entries"
            tooltipOptions={{ position: 'bottom' }}
          />
        </div>
      </div>
    </Card>
  );
};

export default SyncHeader;