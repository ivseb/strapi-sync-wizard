import React from 'react';
import { Card } from 'primereact/card';
import { Message } from 'primereact/message';
import { Button } from 'primereact/button';

interface ErrorDisplayProps {
  error: string;
  onBack?: () => void;
}

const ErrorDisplay: React.FC<ErrorDisplayProps> = ({ error, onBack }) => {
  return (
    <div className="p-4">
      <Card className="mb-4">
        <div className="p-3 bg-danger text-white">
          <h2 className="m-0">Error</h2>
        </div>
        <div className="p-3">
          <Message severity="error" text={error} className="w-full mb-3" />
        </div>
      </Card>

      {onBack && (
        <div className="flex justify-content-end">
          <Button label="Back" icon="pi pi-arrow-left" onClick={onBack} />
        </div>
      )}
    </div>
  );
};

export default ErrorDisplay;