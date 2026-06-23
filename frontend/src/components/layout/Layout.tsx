import React from 'react';
import { MenuItem } from 'primereact/menuitem';
import Sidebar from './Sidebar';

interface LayoutProps {
  children: React.ReactNode;
  menuItems: MenuItem[];
}

const Layout: React.FC<LayoutProps> = ({ children, menuItems }) => (
  <div className="ss-shell surface-ground">
    <Sidebar menuItems={menuItems} />
    <main className="ss-main">
      <div className="ss-content">{children}</div>
    </main>
  </div>
);

export default Layout;
