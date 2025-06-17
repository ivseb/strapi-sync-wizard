import React, { useContext } from 'react';
import { Menubar } from 'primereact/menubar';
import { MenuItem } from 'primereact/menuitem';
import { Link, useNavigate } from 'react-router-dom';
import { LayoutContext } from '../../layout/context/layoutcontext';

interface HeaderProps {
  menuItems: MenuItem[];
}

const Header: React.FC<HeaderProps> = ({ menuItems }) => {
  const { layoutConfig } = useContext(LayoutContext);
  const navigate = useNavigate();

  // Convert menuItems to use React Router navigation
  const routerMenuItems = menuItems.map(item => ({
    ...item,
    command: () => {
      if (item.url) {
        navigate(item.url);
      }
    },
    url: undefined // Remove url property to prevent default navigation
  }));

  return (
    <div className={layoutConfig.colorScheme === 'dark' ? 'surface-card' : 'surface-900'}>
      <div className="container">
        <Menubar
          model={routerMenuItems}
          start={
            <Link to="/" className="text-decoration-none">
              <div className={`text-xl font-bold ${layoutConfig.colorScheme === 'dark' ? 'text-primary' : 'text-white'} mr-4`}>Strapi Sync</div>
            </Link>
          }
          className={`border-noround border-none ${layoutConfig.colorScheme === 'dark' ? 'surface-card' : 'surface-900'} p-3`}
        />
      </div>
    </div>
  );
};

export default Header;
