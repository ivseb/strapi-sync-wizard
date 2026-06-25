import React from 'react';
import Sidebar from './Sidebar';

const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="ss-shell">
    <Sidebar />
    <main className="ss-main">
      <div className="ss-content">{children}</div>
    </main>
  </div>
);

export default Layout;
