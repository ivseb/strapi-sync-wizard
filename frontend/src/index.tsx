import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { queryClient } from './api/queryClient';

// PrimeReact imports
import 'primereact/resources/themes/lara-dark-blue/theme.css';     // theme (flat dark base; overridden by app.css tokens)
import 'primereact/resources/primereact.min.css';                  // core css
import 'primeicons/primeicons.css';                                // icons
import 'primeflex/primeflex.css';                                  // PrimeFlex for layout
import './styles/app.css';                                         // redesign tokens (sidebar shell, lighter type)

const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);
root.render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>
);
