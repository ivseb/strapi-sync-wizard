import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { MenuItem } from 'primereact/menuitem';
import { LayoutProvider } from './layout/context/layoutcontext';
import Layout from './components/layout/Layout';
import Instances from './components/Instances';
import MergeRequests from './components/MergeRequests';
import MergeRequestDetails from './components/MergeRequestDetails';

const App: React.FC = () => {
  const menuItems: MenuItem[] = [
    {
      label: 'Merge Requests',
      icon: 'pi pi-code-branch',
      url: '/merge-requests'
    },
    {
      label: 'Instances',
      icon: 'pi pi-server',
      url: '/instances'
    }
  ];

  return (
    <Router>
      <LayoutProvider>
        <Layout menuItems={menuItems}>
          <Routes>
            <Route path="/" element={<Navigate to="/merge-requests" replace />} />
            <Route path="/instances" element={<Instances />} />
            <Route path="/merge-requests" element={<MergeRequests />} />
            <Route path="/merge-requests/:id" element={<MergeRequestDetails />} />
            <Route path="/merge-requests/:id/complete" element={<MergeRequestDetails />} />
          </Routes>
        </Layout>
      </LayoutProvider>
    </Router>
  );
}

export default App;
