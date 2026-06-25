import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { LayoutProvider } from './layout/context/layoutcontext';
import Layout from './components/layout/Layout';
import Instances from './components/Instances';
import MergeRequests from './components/MergeRequests';
import MergeRequestDetails from './components/MergeRequestDetails';
import MediaCleanup from './components/MediaCleanup';
import ContentCleanup from './components/ContentCleanup';
import Snapshots from './components/Snapshots';

const App: React.FC = () => (
  <Router>
    <LayoutProvider>
      <Layout>
        <Routes>
          <Route path="/" element={<Navigate to="/merge-requests" replace />} />
          <Route path="/instances" element={<Instances />} />
          <Route path="/media-cleanup" element={<MediaCleanup />} />
          <Route path="/content-cleanup" element={<ContentCleanup />} />
          <Route path="/snapshots" element={<Snapshots />} />
          <Route path="/merge-requests" element={<MergeRequests />} />
          <Route path="/merge-requests/:id" element={<MergeRequestDetails />} />
          <Route path="/merge-requests/:id/complete" element={<MergeRequestDetails />} />
        </Routes>
      </Layout>
    </LayoutProvider>
  </Router>
);

export default App;
