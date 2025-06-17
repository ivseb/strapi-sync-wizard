import React, { useContext } from 'react';
import { MenuItem } from 'primereact/menuitem';
import { LayoutContext } from '../../layout/context/layoutcontext';
import Header from './Header';
import Footer from './Footer';

interface LayoutProps {
  children: React.ReactNode;
  menuItems: MenuItem[];
}

const Layout: React.FC<LayoutProps> = ({ children, menuItems }) => {
  const { layoutConfig } = useContext(LayoutContext);

  return (
    <div className={`flex flex-column min-h-screen ${layoutConfig.colorScheme === 'dark' ? 'surface-ground' : ''}`}>
      <Header menuItems={menuItems} />
      <div className="container flex-grow-1 py-4">
        {children}
      </div>
      <Footer />
    </div>
  );
};

export default Layout;
