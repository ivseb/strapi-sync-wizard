import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

// PrimeReact imports
import 'primereact/resources/themes/mdc-dark-indigo/theme.css';    // theme
import 'primereact/resources/primereact.min.css';                  // core css
import 'primeicons/primeicons.css';                                // icons
import 'primeflex/primeflex.css';                                  // PrimeFlex for layout

const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
