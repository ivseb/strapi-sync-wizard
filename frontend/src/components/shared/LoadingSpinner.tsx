import React from 'react';
import { ProgressSpinner } from 'primereact/progressspinner';

interface LoadingSpinnerProps {
  message?: string;
}

const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ message = 'Loading...' }) => {
  return (
    <div className="flex flex-column align-items-center justify-content-center p-5">
      <ProgressSpinner />
      <div className="mt-3">{message}</div>
    </div>
  );
};

export default LoadingSpinner;