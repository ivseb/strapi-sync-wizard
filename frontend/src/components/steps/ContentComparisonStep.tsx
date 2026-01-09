import React, { useState } from 'react';
import { Button } from 'primereact/button';
import { Message } from 'primereact/message';
import axios from 'axios';

interface ContentComparisonStepProps {
  comparingContent: boolean;
  schemaCompatible: boolean | null;
  compareContent: () => void;
  status: string;
}

const ContentComparisonStep: React.FC<ContentComparisonStepProps> = ({
  comparingContent,
  schemaCompatible,
  compareContent,
  status
}) => {
  const [clearingCache, setClearingCache] = useState(false);
  // Check if comparison has been completed based on status
  const isComparisonCompleted = status === 'COMPARED' || 
                               status === 'MERGED_FILES' || 
                               status === 'MERGED_SINGLES' || 
                               status === 'MERGED_COLLECTIONS' || 
                               status === 'COMPLETED';

  return (
    <div>
      <h3>Content Comparison</h3>
      <p>
        This step compares the content between the source and target instances.
        It identifies content that exists only in the source, only in the target, or differs between the two.
      </p>

      <div className="flex flex-column align-items-center my-5">
        {isComparisonCompleted && (
          <div className="mb-3">
            <Message 
              severity="success"
              text="Content comparison completed. You can proceed to the next step."
              className="w-full"
            />
          </div>
        )}

        <div className="flex gap-2">
          <Button 
            label="Compare Content" 
            icon="pi pi-sync" 
            loading={comparingContent}
            disabled={comparingContent || !schemaCompatible}
            onClick={compareContent}
          />
          <Button 
            label="Clear File Cache" 
            icon="pi pi-trash" 
            className="p-button-secondary p-button-outlined"
            loading={clearingCache}
            disabled={comparingContent || clearingCache}
            onClick={async () => {
              if (window.confirm("Are you sure you want to clear the file analysis cache? Next comparison will be slower as it will re-download all files.")) {
                try {
                  setClearingCache(true);
                  await axios.post('/api/merge-requests/clear-file-cache');
                  alert("File analysis cache cleared successfully.");
                } catch (e) {
                  console.error("Error clearing cache", e);
                  alert("Error clearing cache");
                } finally {
                  setClearingCache(false);
                }
              }
            }}
          />
        </div>
      </div>
    </div>
  );
};

export default ContentComparisonStep;
